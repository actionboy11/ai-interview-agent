package interview.guide.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import interview.guide.common.config.OwnershipMigrationConfiguration;
import interview.guide.common.config.OwnershipMigrationProperties;
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

@DisplayName("旧数据所有者 Flyway 参数安全编码")
class OwnershipMigrationConfigurationTest {
  @ParameterizedTest
  @ValueSource(strings = {" O'Brien ", "${missing}", "$$; DROP TABLE users;--",
      "$ownership$", "管理员Ａ"})
  @DisplayName("任意合法登录名归一化后仅以十六进制进入 SQL，保留其他占位符")
  void encodesNormalizedUsernameWithoutSqlSyntax(String username) {
    var configuration = Flyway.configure().placeholders(Map.of("unrelated", "unchanged"));
    new OwnershipMigrationConfiguration().ownershipMigrationCustomizer(
        new OwnershipMigrationProperties(username), new UsernameNormalizer()).customize(configuration);

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
        new OwnershipMigrationProperties(username), new UsernameNormalizer()).customize(configuration);
    assertThat(configuration.getPlaceholders()).containsEntry("legacy-owner-username", "");
  }

  @Test
  @DisplayName("非法非空登录名在运行 SQL 前失败")
  void rejectsInvalidConfiguredUsername() {
    assertThatThrownBy(() -> new OwnershipMigrationConfiguration().ownershipMigrationCustomizer(
        new OwnershipMigrationProperties("invalid\u0001name"), new UsernameNormalizer()))
        .isInstanceOf(BusinessException.class);
  }

  @ParameterizedTest
  @ValueSource(strings = {" O'Brien ", "${missing}", "$ownership$", "管理员Ａ"})
  @DisplayName("环境变量经配置绑定和 Spring 自定义器后仍只输出安全的归一化编码")
  void bindsConfiguredEnvironmentValue(String username) {
    new ApplicationContextRunner()
        .withUserConfiguration(OwnershipMigrationConfiguration.class, UsernameNormalizer.class)
        .withPropertyValues("app.migration.legacy-owner-username=${APP_LEGACY_OWNER_USERNAME:}")
        .withInitializer(context -> context.getEnvironment().getPropertySources().addFirst(
            new MapPropertySource("legacy-owner-test-env", Map.of("APP_LEGACY_OWNER_USERNAME", username))))
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
}
