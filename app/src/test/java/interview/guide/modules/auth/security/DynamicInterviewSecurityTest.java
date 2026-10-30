package interview.guide.modules.auth.security;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import interview.guide.common.exception.GlobalExceptionHandler;
import interview.guide.modules.auth.model.UserEntity;
import interview.guide.modules.auth.model.UserStatus;
import interview.guide.modules.auth.security.CurrentUserProvider;
import interview.guide.modules.auth.security.ResultAccessDeniedHandler;
import interview.guide.modules.auth.security.ResultAuthenticationEntryPoint;
import interview.guide.modules.auth.security.SecurityConfiguration;
import interview.guide.modules.interview.dynamic.model.DynamicInterviewRuntimeEntity;
import interview.guide.modules.interview.dynamic.DynamicInterviewController;
import interview.guide.modules.interview.dynamic.repository.DynamicInterviewRuntimeRepository;
import interview.guide.modules.interview.dynamic.service.DynamicInterviewCommandService;
import interview.guide.modules.interview.dynamic.service.DynamicInterviewQueryService;
import interview.guide.modules.interview.dynamic.service.DynamicInterviewSessionSupport;
import interview.guide.modules.interview.model.InterviewQuestionDTO;
import interview.guide.modules.interview.model.InterviewSessionEntity;
import interview.guide.modules.interview.repository.InterviewAnswerRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.test.context.junit.jupiter.web.SpringJUnitWebConfig;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import tools.jackson.databind.ObjectMapper;

@SpringJUnitWebConfig(DynamicInterviewSecurityTest.WebConfiguration.class)
@DisplayName("动态API复用应用安全过滤链")
class DynamicInterviewSecurityTest {
  @Configuration(proxyBeanMethods = false)
  @EnableWebMvc
  @EnableWebSecurity
  @Import({DynamicInterviewController.class, GlobalExceptionHandler.class})
  static class WebConfiguration {
    @Bean CurrentUserProvider currentUser() { return mock(CurrentUserProvider.class); }
    @Bean ObjectMapper mapper() { return new ObjectMapper(); }
    @Bean DynamicInterviewRuntimeRepository runtimes() { return mock(DynamicInterviewRuntimeRepository.class); }
    @Bean DynamicInterviewQueryService queries(DynamicInterviewRuntimeRepository runtimes, ObjectMapper mapper) {
      return new DynamicInterviewQueryService(runtimes, mock(InterviewAnswerRepository.class), mapper);
    }
    @Bean DynamicInterviewCommandService commands() { return mock(DynamicInterviewCommandService.class); }
    @Bean DynamicInterviewSessionSupport support() { return mock(DynamicInterviewSessionSupport.class); }
    @Bean SecurityFilterChain chain(HttpSecurity http, CurrentUserProvider users) throws Exception {
      var responses = new ResultAuthenticationEntryPoint();
      // Invoke the real application's chain without enabling Redis-backed session infrastructure.
      return new SecurityConfiguration().securityFilterChain(http,
          CookieCsrfTokenRepository.withHttpOnlyFalse(), new HttpSessionSecurityContextRepository(),
          users, responses, new ResultAccessDeniedHandler(responses),
          new UrlBasedCorsConfigurationSource());
    }
  }

  @Autowired WebApplicationContext context;
  @Autowired CurrentUserProvider users;
  @Autowired DynamicInterviewRuntimeRepository runtimes;
  @Autowired ObjectMapper mapper;
  MockMvc mvc;
  UUID owner;

  @BeforeEach
  void setup() {
    mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    owner = UUID.randomUUID();
    var account = new UserEntity();
    account.setId(owner);
    account.setStatus(UserStatus.ACTIVE);
    var session = new InterviewSessionEntity();
    session.setOwner(account);
    session.setSessionId("s");
    session.setQuestionsJson(mapper.writeValueAsString(List.of(
        InterviewQuestionDTO.create(0, "问题", "JAVA", "Java"))));
    var runtime = new DynamicInterviewRuntimeEntity();
    runtime.setSession(session);
    runtime.setOwner(account);
    runtime.setVersion(0L);
    when(users.requireUserId()).thenReturn(owner);
    when(runtimes.findByOwner_IdAndSession_SessionId(owner, "s")).thenReturn(Optional.of(runtime));
  }

  @Test
  @DisplayName("匿名拒绝，三种写入口要求CSRF，合法CSRF仍不能绕过归属")
  void csrfAndOwnershipEnforced() throws Exception {
    String base = "/api/interview/sessions/s/dynamic";
    mvc.perform(get(base)).andExpect(status().isOk()).andExpect(jsonPath("$.code").value(401));
    var submissions = List.of(
        post(base + "/answers").content("{\"requestId\":\"" + UUID.randomUUID()
            + "\",\"questionIndex\":0,\"expectedVersion\":0,\"submissionIntent\":\"SKIP\"}"),
        put(base + "/draft").content("{\"questionIndex\":0,\"expectedVersion\":0}"),
        post(base + "/complete").content("{\"expectedVersion\":0}"));
    for (var request : submissions) {
      mvc.perform(request.with(user("authenticated")).contentType("application/json"))
          .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(403));
      mvc.perform(request.with(user("authenticated")).with(csrf().asHeader())
          .contentType("application/json"))
          .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(200));
    }
    mvc.perform(get(base).with(user("authenticated")))
        .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(200));
    when(users.requireUserId()).thenReturn(UUID.randomUUID());
    mvc.perform(get(base).with(user("authenticated")))
        .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(3001));
  }
}
