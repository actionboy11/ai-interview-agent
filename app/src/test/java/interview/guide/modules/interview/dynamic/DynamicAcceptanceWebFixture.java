package interview.guide.modules.interview.dynamic;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

import interview.guide.common.aspect.RateLimitAspect;
import interview.guide.common.exception.GlobalExceptionHandler;
import interview.guide.infrastructure.redis.InterviewSessionCache;
import interview.guide.infrastructure.redis.RedisService;
import interview.guide.modules.auth.mapper.AuthResponseMapper;
import interview.guide.modules.auth.model.UserEntity;
import interview.guide.modules.auth.repository.UserRepository;
import interview.guide.modules.auth.security.CurrentUser;
import interview.guide.modules.auth.security.CurrentUserProvider;
import interview.guide.modules.auth.security.ResultAccessDeniedHandler;
import interview.guide.modules.auth.security.ResultAuthenticationEntryPoint;
import interview.guide.modules.auth.security.SecurityConfiguration;
import interview.guide.modules.auth.service.AuthUserQueryService;
import interview.guide.modules.interview.dynamic.service.DynamicInterviewSessionSupport;
import jakarta.servlet.Filter;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import org.mapstruct.factory.Mappers;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.MapPropertySource;
import org.redisson.spring.data.connection.RedissonConnectionFactory;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.session.data.redis.RedisIndexedSessionRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import tools.jackson.databind.ObjectMapper;
import java.util.List;

/** Actual application security and Redis sessions, with the test's PG services as parent beans. */
final class DynamicAcceptanceWebFixture implements AutoCloseable {
  final AnnotationConfigWebApplicationContext context = new AnnotationConfigWebApplicationContext();
  final MockMvc mvc;

  DynamicAcceptanceWebFixture(ApplicationContext parent) {
    context.setParent(parent);
    context.setServletContext(new MockServletContext());
    context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("isolated-session",
        Map.of("spring.session.redis.namespace", "task10:sessions:" + UUID.randomUUID())));
    context.register(WebConfiguration.class);
    context.refresh();
    // SessionRepositoryFilter must wrap the security chain so HttpSession restores from Redis.
    mvc = MockMvcBuilders.webAppContextSetup(context)
        .addFilters(context.getBean("springSessionRepositoryFilter", Filter.class))
        .apply(springSecurity()).build();
  }

  Cookie cookie(UserEntity user) {
    var repository = context.getBean(RedisIndexedSessionRepository.class);
    var session = repository.createSession();
    var principal = new CurrentUser(user.getId(), user.getUsername(), user.getDisplayName(),
        user.getRole(), false);
    var authentication = UsernamePasswordAuthenticationToken.authenticated(principal, null,
        List.of(new SimpleGrantedAuthority("ROLE_USER")));
    session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
        new SecurityContextImpl(authentication));
    repository.save(session);
    return new Cookie("SESSION", Base64.getEncoder()
        .encodeToString(session.getId().getBytes(StandardCharsets.UTF_8)));
  }

  @Override public void close() { context.close(); }

  @Configuration(proxyBeanMethods = false)
  @EnableWebMvc
  @EnableAspectJAutoProxy(proxyTargetClass = true)
  @Import({SecurityConfiguration.class, ResultAuthenticationEntryPoint.class,
      ResultAccessDeniedHandler.class, DynamicInterviewController.class,
      DynamicInterviewSessionSupport.class, GlobalExceptionHandler.class, RateLimitAspect.class})
  static class WebConfiguration {
    @Bean RedissonConnectionFactory redisConnectionFactory(RedissonClient client) {
      return new RedissonConnectionFactory(client);
    }
    @Bean(destroyMethod = "shutdown") RedissonClient redissonClient() {
      var config = new Config();
      config.useSingleServer().setAddress("redis://" + DynamicPostgresTestSupport.REDIS.getHost()
          + ":" + DynamicPostgresTestSupport.REDIS.getMappedPort(6379)).setDatabase(1)
          .setConnectionMinimumIdleSize(1).setConnectionPoolSize(4);
      return Redisson.create(config);
    }
    @Bean @Primary RedisService realRedis(RedissonClient client) { return new RedisService(client); }
    @Bean @Primary CurrentUserProvider webCurrentUser(UserRepository users) {
      return new CurrentUserProvider(new AuthUserQueryService(users, Mappers.getMapper(AuthResponseMapper.class)));
    }
    @Bean InterviewSessionCache sessionCache(RedisService redis, ObjectMapper mapper, CurrentUserProvider users) {
      return new InterviewSessionCache(redis, mapper, users);
    }
    @Bean CorsConfigurationSource corsConfigurationSource() { return new UrlBasedCorsConfigurationSource(); }
  }
}
