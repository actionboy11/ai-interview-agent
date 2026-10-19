package interview.guide.modules.auth.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "spring.session")
public record AuthSessionProperties(@DefaultValue("30m") Duration timeout) {
  public AuthSessionProperties {
    if (timeout == null || timeout.isNegative() || timeout.isZero()) {
      throw new IllegalArgumentException("spring.session.timeout must be positive");
    }
  }
}
