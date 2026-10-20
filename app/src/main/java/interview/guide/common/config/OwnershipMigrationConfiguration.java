package interview.guide.common.config;

import interview.guide.modules.auth.service.UsernameNormalizer;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HexFormat;
import org.springframework.boot.flyway.autoconfigure.FlywayConfigurationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.StandardEnvironment;

@Configuration(proxyBeanMethods = false)
public class OwnershipMigrationConfiguration {
  @Bean
  public FlywayConfigurationCustomizer ownershipMigrationCustomizer(
      ConfigurableEnvironment environment, UsernameNormalizer normalizer) {
    // Read the raw source directly: Environment.getProperty and configuration binding
    // recursively resolve ${...} inside usernames and can select a different account.
    var systemEnvironment = environment.getPropertySources()
        .get(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
    Object raw = systemEnvironment == null ? null
        : systemEnvironment.getProperty("APP_LEGACY_OWNER_USERNAME");
    String configured = raw == null ? null : raw.toString();
    String normalized = configured == null || configured.isBlank()
        ? "" : normalizer.normalize(configured);
    // Flyway substitutes text, not bind parameters. Hex cannot terminate SQL literals or
    // dollar-quoted blocks, even when a username contains quotes or placeholder syntax.
    String encoded = HexFormat.of().formatHex(normalized.getBytes(StandardCharsets.UTF_8));
    return configuration -> {
      var placeholders = new HashMap<>(configuration.getPlaceholders());
      placeholders.put("legacy-owner-username", encoded);
      configuration.placeholders(placeholders);
    };
  }
}
