package interview.guide.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.migration")
public record OwnershipMigrationProperties(String legacyOwnerUsername) {
}
