package interview.guide.modules.auth.security;

import interview.guide.modules.auth.config.AuthSessionProperties;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.session.config.SessionRepositoryCustomizer;
import org.springframework.session.data.redis.RedisIndexedSessionRepository;
import org.springframework.session.data.redis.config.annotation.web.http.EnableRedisIndexedHttpSession;
import org.springframework.session.web.http.CookieSerializer;
import org.springframework.session.web.http.DefaultCookieSerializer;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
@EnableConfigurationProperties(AuthSessionProperties.class)
@EnableRedisIndexedHttpSession(
    redisNamespace = "${spring.session.redis.namespace:interview-guide:sessions}")
public class SecurityConfiguration {
  @Bean
  SessionRepositoryCustomizer<RedisIndexedSessionRepository> sessionTimeout(
      AuthSessionProperties properties) {
    return repository -> repository.setDefaultMaxInactiveInterval(properties.timeout());
  }

  @Bean
  CookieCsrfTokenRepository csrfTokenRepository() {
    return CookieCsrfTokenRepository.withHttpOnlyFalse();
  }

  @Bean
  HttpSessionSecurityContextRepository securityContextRepository() {
    var repository = new HttpSessionSecurityContextRepository();
    repository.setDisableUrlRewriting(true);
    return repository;
  }

  @Bean
  CookieSerializer sessionCookieSerializer() {
    var serializer = new DefaultCookieSerializer();
    serializer.setUseHttpOnlyCookie(true);
    serializer.setSameSite("Lax");
    return serializer;
  }

  @Bean
  SecurityFilterChain securityFilterChain(HttpSecurity http, CookieCsrfTokenRepository csrf,
      HttpSessionSecurityContextRepository contexts, CurrentUserProvider currentUsers,
      ResultAuthenticationEntryPoint entryPoint, ResultAccessDeniedHandler denied,
      @Qualifier("corsConfigurationSource") CorsConfigurationSource corsSource) throws Exception {
    var corsFilter = new CorsFilter(corsSource);
    corsFilter.setCorsProcessor(new ResultCorsProcessor());
    http.cors(config -> config.disable())
        .addFilterAt(corsFilter, CorsFilter.class)
        .csrf(config -> config.csrfTokenRepository(csrf)
            .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler()))
        .securityContext(config -> config.securityContextRepository(contexts))
        .requestCache(config -> config.disable())
        .formLogin(config -> config.disable())
        .httpBasic(config -> config.disable())
        .logout(config -> config.disable())
        .exceptionHandling(config -> config.authenticationEntryPoint(entryPoint)
            .accessDeniedHandler(denied))
        .authorizeHttpRequests(config -> config
            .requestMatchers(HttpMethod.POST, "/api/auth/login", "/api/auth/register-by-invite")
            .permitAll()
            .requestMatchers(HttpMethod.GET, "/api/auth/csrf", "/actuator/health",
                "/actuator/health/liveness", "/actuator/health/readiness").permitAll()
            .requestMatchers("/api/admin/**").hasRole("ADMIN")
            .anyRequest().authenticated())
        .addFilterAfter(new ActiveUserFilter(currentUsers, entryPoint),
            CorsFilter.class);
    return http.build();
  }
}
