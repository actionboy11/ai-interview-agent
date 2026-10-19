package interview.guide.common.config;

import java.util.Arrays;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/** Shared explicit-origin policy applied by the security chain before authentication failures. */
@Configuration(proxyBeanMethods = false)
@RequiredArgsConstructor
public class CorsConfig {
  private final CorsProperties corsProperties;

  @Bean
  public UrlBasedCorsConfigurationSource corsConfigurationSource() {
    var config = new CorsConfiguration();
    Arrays.stream(corsProperties.getAllowedOrigins().split(","))
        .map(String::trim).filter(origin -> !origin.isEmpty()).forEach(config::addAllowedOrigin);
    config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS"));
    config.setAllowedHeaders(List.of("*"));
    config.setAllowCredentials(true);
    config.validateAllowCredentials();
    config.setMaxAge(3600L);

    var source = new UrlBasedCorsConfigurationSource();
    source.registerCorsConfiguration("/**", config);
    return source;
  }
}
