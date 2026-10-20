package interview.guide.common.config;

import interview.guide.modules.auth.service.UsernameNormalizer;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HexFormat;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.flyway.autoconfigure.FlywayConfigurationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(OwnershipMigrationProperties.class)
public class OwnershipMigrationConfiguration {
  @Bean
  public FlywayConfigurationCustomizer ownershipMigrationCustomizer(
      OwnershipMigrationProperties properties, UsernameNormalizer normalizer) {
    String configured = properties.legacyOwnerUsername();
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
