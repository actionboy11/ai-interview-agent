package interview.guide.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@DisplayName("生产 Compose 身份环境变量传递")
class ProductionComposeEnvironmentTest {
  private static final String OWNER = "APP_LEGACY_OWNER_USERNAME";
  private static final String BOOTSTRAP_USER = "APP_AUTH_BOOTSTRAP_USERNAME";
  private static final String BOOTSTRAP_PASSWORD = "APP_AUTH_BOOTSTRAP_PASSWORD";
  private static final String BOOTSTRAP_DISPLAY = "APP_AUTH_BOOTSTRAP_DISPLAY_NAME";
  private static final Set<String> VARIABLES = Set.of(OWNER, BOOTSTRAP_USER, BOOTSTRAP_PASSWORD,
      BOOTSTRAP_DISPLAY, "SITE_ADDRESS", "DEMO_USERNAME", "DEMO_PASSWORD_HASH", "POSTGRES_DB",
      "POSTGRES_USER", "POSTGRES_PASSWORD", "RABBITMQ_USERNAME", "RABBITMQ_PASSWORD",
      "MINIO_ROOT_USER", "MINIO_ROOT_PASSWORD", "APP_STORAGE_BUCKET", "AI_BAILIAN_API_KEY",
      "AI_MODEL", "APP_AI_CONFIG_ENCRYPTION_KEY", "APP_RESUME_MESSAGING_PROVIDER",
      "APP_VOICE_EVALUATION_MESSAGING_PROVIDER", "APP_INTERVIEW_EVALUATION_MESSAGING_PROVIDER",
      "APP_KNOWLEDGE_VECTORIZATION_MESSAGING_PROVIDER", "REVIEW_TEST_VALUE");
  private static final String FIXTURE = """
      SITE_ADDRESS=example.invalid
      DEMO_USERNAME=demo
      DEMO_PASSWORD_HASH='$2a$test-only'
      POSTGRES_DB=compose_test
      POSTGRES_USER=compose_test
      POSTGRES_PASSWORD=fixture-only-postgres
      RABBITMQ_USERNAME=compose_test
      RABBITMQ_PASSWORD=fixture-only-rabbit
      MINIO_ROOT_USER=compose_test
      MINIO_ROOT_PASSWORD=fixture-only-storage
      AI_BAILIAN_API_KEY=fixture-only-key
      APP_AI_CONFIG_ENCRYPTION_KEY=fixture-only-encryption
      REVIEW_TEST_VALUE=other-user
      """;

  @TempDir
  Path temporary;

  @ParameterizedTest
  @ValueSource(strings = {"legacy-user", "${REVIEW_TEST_VALUE}", "${REVIEW_TEST_VALUE:default-user}",
      "${review.test.value}", "$ownership$", "$$; DROP TABLE users;--", "管理员Ａ", "O'Brien",
      "back\\slash", "double\"and'single"})
  @DisplayName("单引号 dotenv 用户名经真实 Compose 解析后完整进入后端容器环境")
  void propagatesLiteralUsernameFromSingleQuotedEnvFile(String username) throws Exception {
    var environment = render(FIXTURE + OWNER + "=" + singleQuoted(username) + "\n");
    assertThat(containerValue(environment, OWNER)).isEqualTo(username);
  }

  @Test
  @DisplayName("阶段一管理员三项配置传入后端且密码不出现在测试诊断中")
  void propagatesBootstrapConfigurationWithoutPrintingPassword() throws Exception {
    String syntheticPassword = "fixture-only-not-an-account";
    var environment = render(FIXTURE + BOOTSTRAP_USER + "='legacy-user'\n"
        + BOOTSTRAP_PASSWORD + "=" + singleQuoted(syntheticPassword) + "\n"
        + BOOTSTRAP_DISPLAY + "='Legacy owner'\n");
    assertThat(environment.path(BOOTSTRAP_USER).asString()).isEqualTo("legacy-user");
    assertThat(syntheticPassword.equals(environment.path(BOOTSTRAP_PASSWORD).asString()))
        .as("bootstrap password is propagated without logging its value").isTrue();
    assertThat(environment.path(BOOTSTRAP_DISPLAY).asString()).isEqualTo("Legacy owner");
  }

  @Test
  @DisplayName("全新数据库的旧所有者和 bootstrap 配置均默认留空，没有默认账号密码")
  void leavesOptionalIdentityEnvironmentEmpty() throws Exception {
    var environment = render(FIXTURE);
    for (String variable : List.of(OWNER, BOOTSTRAP_USER, BOOTSTRAP_PASSWORD, BOOTSTRAP_DISPLAY)) {
      assertThat(environment.path(variable).isString() && environment.path(variable).asString().isEmpty())
          .as("optional identity variable %s is an explicit empty string", variable).isTrue();
    }
  }

  @Test
  @DisplayName("停机前预检逐字核对表达式用户名并排除 shell 的同名变量覆盖")
  void preflightVerifiesLiteralIdentityWithoutDumpingEnvironment() throws Exception {
    String username = "${REVIEW_TEST_VALUE:default-user}";
    var result = preflight(FIXTURE + OWNER + "=" + singleQuoted(username) + "\n"
        + BOOTSTRAP_USER + "=" + singleQuoted(username) + "\n"
        + BOOTSTRAP_PASSWORD + "='fixture-only-not-an-account'\n", username);
    assertThat(result.exitCode()).isZero();
    assertThat(result.output().strip()).isEqualTo("literal legacy-owner environment preflight passed");
    assertThat(result.errors()).isEmpty();
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "other-user"})
  @DisplayName("停机前预检拒绝缺失或已被 Compose 展开为另一个账号的用户名")
  void preflightRejectsMissingOrWrongIdentity(String actual) throws Exception {
    String unsafeDotenv = actual.isEmpty() ? "''" : "${REVIEW_TEST_VALUE}";
    var result = preflight(FIXTURE + OWNER + "=" + unsafeDotenv + "\n",
        "${REVIEW_TEST_VALUE}");
    assertThat(result.exitCode()).isNotZero();
    assertThat(result.output()).isEmpty();
    assertThat(result.errors().strip()).isEqualTo("legacy-owner/bootstrap username mismatch");
  }

  @Test
  @DisplayName("停机前预检拒绝与旧所有者不同的非空 bootstrap 用户名")
  void preflightRejectsDifferentBootstrapUsername() throws Exception {
    var result = preflight(FIXTURE + OWNER + "='legacy-user'\n"
        + BOOTSTRAP_USER + "='other-user'\n", "legacy-user");
    assertThat(result.exitCode()).isNotZero();
    assertThat(result.output()).isEmpty();
    assertThat(result.errors().strip()).isEqualTo("legacy-owner/bootstrap username mismatch");
  }

  @Test
  @DisplayName("Compose 配置失败时预检只返回通用错误，不转储环境或密码")
  void preflightRedactsComposeFailure() throws Exception {
    var result = preflight(OWNER + "='legacy-user'\n", "legacy-user");
    assertThat(result.exitCode()).isNotZero();
    assertThat(result.output()).isEmpty();
    assertThat(result.errors().strip()).isEqualTo("cannot render production Compose configuration");
  }

  private String containerValue(JsonNode environment, String name) {
    // Compose 5 config serializes every $ as $$ after marshaling; undo that layer once.
    return environment.path(name).asString().replace("$$", "$");
  }

  private PreflightResult preflight(String dotenv, String expected) throws Exception {
    Path root = Files.isRegularFile(Path.of("docker-compose.prod.yml")) ? Path.of(".") : Path.of("..");
    Path env = temporary.resolve("preflight.env");
    Path input = temporary.resolve("expected-username.txt");
    Path output = temporary.resolve("preflight.stdout");
    Path errors = temporary.resolve("preflight.stderr");
    Files.writeString(env, dotenv, StandardCharsets.UTF_8);
    Files.writeString(input, expected + "\n", StandardCharsets.UTF_8);
    String python = System.getProperty("os.name").startsWith("Windows") ? "python" : "python3";
    var builder = new ProcessBuilder(python, "-X", "utf8", root.resolve(
        "deploy/verify-legacy-owner-environment.py").toString(), "--env-file", env.toString(),
        "--expected-username-stdin");
    builder.environment().keySet().removeIf(name -> VARIABLES.contains(name) || name.startsWith("COMPOSE_"));
    builder.environment().put(OWNER, "other-user");
    builder.environment().put(BOOTSTRAP_USER, "other-user");
    builder.redirectInput(input.toFile()).redirectOutput(output.toFile()).redirectError(errors.toFile());
    var process = builder.start();
    try {
      assertThat(process.waitFor(40, TimeUnit.SECONDS)).as("preflight finishes without stopping services").isTrue();
      return new PreflightResult(process.exitValue(), Files.readString(output, StandardCharsets.UTF_8),
          Files.readString(errors, StandardCharsets.UTF_8));
    } finally {
      if (process.isAlive()) {
        process.destroyForcibly();
      }
    }
  }

  private record PreflightResult(int exitCode, String output, String errors) {
  }

  private String singleQuoted(String value) {
    return "'" + value.replace("'", "\\'") + "'";
  }

  private JsonNode render(String dotenv) throws Exception {
    Path compose = Path.of("docker-compose.prod.yml");
    if (!Files.isRegularFile(compose)) {
      compose = Path.of("..", "docker-compose.prod.yml");
    }
    assertThat(Files.isRegularFile(compose)).as("production compose file exists").isTrue();
    Path env = temporary.resolve("production.env");
    Path output = temporary.resolve("rendered.json");
    Path errors = temporary.resolve("compose.stderr");
    Files.writeString(env, dotenv, StandardCharsets.UTF_8);
    var builder = new ProcessBuilder("docker", "compose", "--env-file", env.toString(),
        "-f", compose.toAbsolutePath().normalize().toString(), "config", "--format", "json");
    builder.environment().keySet().removeIf(name -> VARIABLES.contains(name) || name.startsWith("COMPOSE_"));
    builder.redirectOutput(output.toFile()).redirectError(errors.toFile());
    var process = builder.start();
    try {
      assertThat(process.waitFor(30, TimeUnit.SECONDS)).as("Compose config finishes without starting services")
          .isTrue();
      assertThat(process.exitValue()).as("Compose config succeeds; full environment is not logged").isZero();
      return new ObjectMapper().readTree(Files.readString(output, StandardCharsets.UTF_8))
          .path("services").path("app").path("environment");
    } finally {
      if (process.isAlive()) {
        process.destroyForcibly();
      }
    }
  }
}
