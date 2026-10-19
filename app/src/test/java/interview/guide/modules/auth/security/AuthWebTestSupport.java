package interview.guide.modules.auth.security;

import com.jayway.jsonpath.JsonPath;
import interview.guide.common.config.CorsConfig;
import interview.guide.common.config.CorsProperties;
import interview.guide.common.exception.GlobalExceptionHandler;
import interview.guide.modules.auth.model.UserEntity;
import interview.guide.modules.auth.model.UserRole;
import interview.guide.modules.auth.repository.RegistrationInviteRepository;
import interview.guide.modules.auth.repository.UserRepository;
import interview.guide.modules.auth.service.AdminAccountService;
import interview.guide.modules.auth.service.CreateAdminUserCommand;
import interview.guide.modules.auth.service.InitialAdminBootstrap;
import jakarta.servlet.Filter;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.redisson.spring.data.connection.RedissonConnectionFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.security.autoconfigure.SecurityAutoConfiguration;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.transaction.autoconfigure.TransactionAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.session.data.redis.RedisIndexedSessionRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = AuthWebTestSupport.TestApplication.class, properties = {
    "spring.datasource.url=jdbc:h2:mem:auth-web;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    "spring.datasource.driver-class-name=org.h2.Driver",
    "spring.datasource.username=sa", "spring.datasource.password=",
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "spring.session.redis.namespace=interview-guide:test:task4",
    "spring.session.timeout=5m",
    // Do not emit an auto-generated secret while the fallback-account regression is RED.
    "logging.level.org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration=OFF",
    "app.cors.allowed-origins=http://localhost:5173"
})
public abstract class AuthWebTestSupport {
  @Configuration(proxyBeanMethods = false)
  @EnableWebMvc
  @EntityScan(basePackageClasses = UserEntity.class)
  @EnableJpaRepositories(basePackageClasses = UserRepository.class)
  @ComponentScan(basePackages = "interview.guide.modules.auth", excludeFilters = {
      @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = InitialAdminBootstrap.class),
      @ComponentScan.Filter(type = FilterType.REGEX, pattern = ".*Test.*")})
  @ImportAutoConfiguration({DataSourceAutoConfiguration.class, HibernateJpaAutoConfiguration.class,
      TransactionAutoConfiguration.class, SecurityAutoConfiguration.class,
      UserDetailsServiceAutoConfiguration.class})
  @Import({CorsConfig.class, CorsProperties.class, GlobalExceptionHandler.class,
      SecurityBoundaryIntegrationTest.OwnerProbeController.class})
  static class TestApplication {
    @Bean(destroyMethod = "shutdown")
    RedissonClient redissonClient() {
      Config config = new Config();
      config.useSingleServer().setAddress("redis://127.0.0.1:6379");
      return Redisson.create(config);
    }

    @Bean
    RedisConnectionFactory redisConnectionFactory(RedissonClient client) {
      return new RedissonConnectionFactory(client);
    }
  }

  @Autowired protected UserRepository users;
  @Autowired RegistrationInviteRepository invites;
  @Autowired protected AdminAccountService accounts;
  @Autowired protected RedisIndexedSessionRepository sessions;
  @Autowired WebApplicationContext context;
  @Autowired @Qualifier("springSessionRepositoryFilter") Filter sessionFilter;
  protected MockMvc mvc;

  @BeforeEach
  void prepare() {
    mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(sessionFilter)
        .apply(springSecurity()).build();
    invites.deleteAllInBatch();
    users.deleteAllInBatch();
  }

  @AfterEach
  void cleanSessions() {
    users.findAll().forEach(user -> sessions.findByPrincipalName(user.getId().toString())
        .keySet().forEach(sessions::deleteById));
  }

  protected UserEntity createUser(String name, UserRole role) {
    return accounts.createUser(new CreateAdminUserCommand(name, "secret-password", name, role));
  }

  protected Browser login(String name) throws Exception {
    Browser browser = bootstrap();
    MvcResult result = mvc.perform(browser.mutate(post("/api/auth/login"))
        .contentType("application/json")
        .content("{\"username\":\"" + name + "\",\"password\":\"secret-password\"}"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(200)).andReturn();
    Cookie session = result.getResponse().getCookie("SESSION");
    assertThat(session).isNotNull();
    // Login rotates CSRF state; fetch the fresh token before subsequent mutations.
    MvcResult csrf = mvc.perform(get("/api/auth/csrf").cookie(session)).andReturn();
    return new Browser(session, csrf.getResponse().getCookie("XSRF-TOKEN"));
  }

  protected Browser bootstrap() throws Exception {
    MvcResult csrf = mvc.perform(get("/api/auth/csrf"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(200)).andReturn();
    Cookie token = csrf.getResponse().getCookie("XSRF-TOKEN");
    assertThat(token).isNotNull();
    assertThat(token.isHttpOnly()).isFalse();
    return new Browser(null, token);
  }

  protected String data(MvcResult result, String field) throws Exception {
    return JsonPath.read(result.getResponse().getContentAsString(), "$.data." + field);
  }

  protected record Browser(Cookie session, Cookie csrf) {
    public MockHttpServletRequestBuilder read(MockHttpServletRequestBuilder request) {
      return session == null ? request : request.cookie(session);
    }

    public MockHttpServletRequestBuilder mutate(MockHttpServletRequestBuilder request) {
      read(request);
      return request.cookie(csrf).header("X-XSRF-TOKEN", csrf.getValue());
    }

    public String sessionId() {
      return new String(Base64.getDecoder().decode(session.getValue()), StandardCharsets.UTF_8);
    }
  }
}
