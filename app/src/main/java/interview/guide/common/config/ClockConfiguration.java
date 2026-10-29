package interview.guide.common.config;

import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class ClockConfiguration {
  @Bean
  @ConditionalOnMissingBean(Clock.class)
  public Clock clock() { return Clock.systemUTC(); }
}
