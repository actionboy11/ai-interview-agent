package interview.guide.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import interview.guide.common.config.OwnershipMigrationConfiguration;
import interview.guide.common.exception.BusinessException;
import interview.guide.modules.auth.service.UsernameNormalizer;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.flyway.autoconfigure.FlywayConfigurationCustomizer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

@DisplayName("旧数据所有者 Flyway 参数安全编码")
class OwnershipMigrationConfigurationTest {
  @ParameterizedTest
  @ValueSource(strings = {" O'Brien ", "${missing}", "$$; DROP TABLE users;--",
      "$ownership$", "管理员Ａ"})
  @DisplayName("任意合法登录名归一化后仅以十六进制进入 SQL，保留其他占位符")
  void encodesNormalizedUsernameWithoutSqlSyntax(String username) {
    var configuration = Flyway.configure().placeholders(Map.of("unrelated", "unchanged"));
    new OwnershipMigrationConfiguration().ownershipMigrationCustomizer(
        environment(username), new UsernameNormalizer()).customize(configuration);

    String encoded = configuration.getPlaceholders().get("legacy-owner-username");
    assertThat(encoded).matches("[0-9a-f]+");
    String decoded = new String(HexFormat.of().parseHex(encoded), StandardCharsets.UTF_8);
    String expected = switch (username) {
      case " O'Brien " -> "o'brien";
      case "$$; DROP TABLE users;--" -> "$$; drop table users;--";
      case "管理员Ａ" -> "管理员a";
      default -> username;
    };
    assertThat(decoded).isEqualTo(expected);
    assertThat(configuration.getPlaceholders().get("unrelated")).isEqualTo("unchanged");
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {" ", "\t"})
  @DisplayName("未配置或全空白值生成空占位符，交由 V3 按有无旧数据决定是否拒绝")
  void allowsAbsentLegacyOwnerForCleanDatabase(String username) {
    var configuration = Flyway.configure();
    new OwnershipMigrationConfiguration().ownershipMigrationCustomizer(
        environment(username), new UsernameNormalizer()).customize(configuration);
    assertThat(configuration.getPlaceholders()).containsEntry("legacy-owner-username", "");
  }

  @Test
  @DisplayName("非法非空登录名在运行 SQL 前失败")
  void rejectsInvalidConfiguredUsername() {
    assertThatThrownBy(() -> new OwnershipMigrationConfiguration().ownershipMigrationCustomizer(
        environment("invalid\u0001name"), new UsernameNormalizer()))
        .isInstanceOf(BusinessException.class);
  }

  @ParameterizedTest
  @ValueSource(strings = {" O'Brien ", "${missing}", "$ownership$", "管理员Ａ"})
  @DisplayName("Spring 自定义器从原始环境属性源读取引号、dollar quote 和 Unicode 登录名")
  void readsRawEnvironmentValue(String username) {
    new ApplicationContextRunner()
        .withUserConfiguration(OwnershipMigrationConfiguration.class, UsernameNormalizer.class)
        .withInitializer(context -> context.getEnvironment().getPropertySources().replace(
            StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
            new SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                Map.of("APP_LEGACY_OWNER_USERNAME", username))))
        .run(context -> {
          assertThat(context).hasNotFailed();
          var configuration = Flyway.configure();
          context.getBean(FlywayConfigurationCustomizer.class).customize(configuration);
          String encoded = configuration.getPlaceholders().get("legacy-owner-username");
          assertThat(encoded).matches("[0-9a-f]+");
          String decoded = new String(HexFormat.of().parseHex(encoded), StandardCharsets.UTF_8);
          assertThat(decoded).isEqualTo(switch (username) {
            case " O'Brien " -> "o'brien";
            case "管理员Ａ" -> "管理员a";
            default -> username;
          });
        });
  }

  @ParameterizedTest
  @ValueSource(strings = {"${review.test.value}", "${review.test.value:default-user}"})
  @DisplayName("环境用户名中的可解析表达式必须作为字面登录名，不能改成另一个账号")
  void preservesResolvablePlaceholderExpressionLiterally(String username) {
    new ApplicationContextRunner()
        .withUserConfiguration(OwnershipMigrationConfiguration.class, UsernameNormalizer.class)
        .withPropertyValues("app.migration.legacy-owner-username=${APP_LEGACY_OWNER_USERNAME:}",
            "review.test.value=other-user")
        .withInitializer(context -> context.getEnvironment().getPropertySources().replace(
            StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
            new SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                Map.of("APP_LEGACY_OWNER_USERNAME", username))))
        .run(context -> {
          assertThat(context).hasNotFailed();
          var configuration = Flyway.configure();
          context.getBean(FlywayConfigurationCustomizer.class).customize(configuration);
          String encoded = configuration.getPlaceholders().get("legacy-owner-username");
          assertThat(new String(HexFormat.of().parseHex(encoded), StandardCharsets.UTF_8))
              .isEqualTo(username);
        });
  }

  @Test
  @DisplayName("环境用户名中的默认值表达式必须保持字面值，不能选择默认账号")
  void preservesDefaultExpressionWithoutResolvingFallbackAccount() {
    String username = "${review.test.value:default-user}";
    new ApplicationContextRunner()
        .withUserConfiguration(OwnershipMigrationConfiguration.class, UsernameNormalizer.class)
        .withPropertyValues("app.migration.legacy-owner-username=${APP_LEGACY_OWNER_USERNAME:}")
        .withInitializer(context -> context.getEnvironment().getPropertySources().replace(
            StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
            new SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                Map.of("APP_LEGACY_OWNER_USERNAME", username))))
        .run(context -> {
          assertThat(context).hasNotFailed();
          var configuration = Flyway.configure();
          context.getBean(FlywayConfigurationCustomizer.class).customize(configuration);
          String encoded = configuration.getPlaceholders().get("legacy-owner-username");
          assertThat(new String(HexFormat.of().parseHex(encoded), StandardCharsets.UTF_8))
              .isEqualTo(username);
        });
  }

  @Test
  @DisplayName("原始环境属性源缺失时按未设置处理，拒绝从解析后的同名属性补值")
  void ignoresResolvedPropertiesWhenRawEnvironmentSourceIsAbsent() {
    var environment = environment(null);
    environment.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
    environment.getPropertySources().addFirst(new MapPropertySource("resolved-configuration", Map.of(
        "APP_LEGACY_OWNER_USERNAME", "other-user", "app.migration.legacy-owner-username", "other-user")));
    var configuration = Flyway.configure();
    new OwnershipMigrationConfiguration().ownershipMigrationCustomizer(
        environment, new UsernameNormalizer()).customize(configuration);
    assertThat(configuration.getPlaceholders()).containsEntry("legacy-owner-username", "");
  }

  private StandardEnvironment environment(String username) {
    var environment = new StandardEnvironment();
    Map<String, Object> values = username == null ? Map.of()
        : Map.of("APP_LEGACY_OWNER_USERNAME", username);
    environment.getPropertySources().replace(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
        new SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
            values));
    return environment;
  }
}
