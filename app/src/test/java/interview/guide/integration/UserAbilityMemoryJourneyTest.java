package interview.guide.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.jayway.jsonpath.JsonPath;
import interview.guide.common.ai.LlmProviderRegistry;
import interview.guide.common.ai.PromptSanitizer;
import interview.guide.common.ai.StructuredOutputInvoker;
import interview.guide.common.config.LlmProviderProperties;
import interview.guide.common.exception.GlobalExceptionHandler;
import interview.guide.common.model.AsyncTaskStatus;
import interview.guide.modules.auth.mapper.AuthResponseMapperImpl;
import interview.guide.modules.auth.model.UserEntity;
import interview.guide.modules.auth.model.UserRole;
import interview.guide.modules.auth.security.CurrentUser;
import interview.guide.modules.auth.security.CurrentUserProvider;
import interview.guide.modules.auth.service.AdminAccountService;
import interview.guide.modules.auth.service.AuthService;
import interview.guide.modules.auth.service.AuthUserQueryService;
import interview.guide.modules.auth.service.CreateAdminUserCommand;
import interview.guide.modules.auth.service.InviteCodeService;
import interview.guide.modules.auth.service.UsernameNormalizer;
import interview.guide.modules.interview.InterviewController;
import interview.guide.modules.interview.model.CreateInterviewRequest;
import interview.guide.modules.interview.model.InterviewQuestionDTO;
import interview.guide.modules.interview.model.InterviewReportDTO;
import interview.guide.modules.interview.model.InterviewSessionEntity;
import interview.guide.modules.interview.dynamic.service.DynamicInterviewLifecycle;
import interview.guide.modules.interview.dynamic.service.DynamicInterviewSessionSupport;
import interview.guide.modules.interview.dynamic.service.DynamicInterviewProperties;
import interview.guide.modules.interview.dynamic.service.DynamicInterviewCreationService;
import interview.guide.modules.interview.dynamic.service.DynamicInterviewCommandService;
import interview.guide.modules.interview.dynamic.service.DynamicInterviewQueryService;
import interview.guide.modules.interview.planning.QuestionPlanningService;
import interview.guide.modules.interview.planning.model.PlanningMode;
import interview.guide.modules.interview.planning.model.QuestionPlanningCommand;
import interview.guide.modules.interview.planning.repository.InterviewQuestionPlanRepository;
import interview.guide.modules.interview.repository.InterviewSessionRepository;
import interview.guide.modules.interview.messaging.InterviewEvaluationTaskPublisher;
import interview.guide.infrastructure.redis.InterviewSessionCache;
import interview.guide.infrastructure.redis.RedisService;
import interview.guide.modules.interview.service.AnswerEvaluationService;
import interview.guide.modules.interview.service.InterviewHistoryService;
import interview.guide.modules.interview.service.InterviewPersistenceService;
import interview.guide.modules.interview.service.InterviewQuestionProperties;
import interview.guide.modules.interview.service.InterviewQuestionService;
import interview.guide.modules.interview.service.InterviewQuestionCountService;
import interview.guide.modules.interview.service.InterviewSessionService;
import interview.guide.modules.interview.service.MemoryDerivationPublicationService;
import interview.guide.modules.interview.skill.InterviewSkillService;
import interview.guide.modules.memory.mapper.AbilityMemoryMapperImpl;
import interview.guide.modules.memory.model.AbilityLevel;
import interview.guide.modules.memory.repository.AbilityProfileProposalRepository;
import interview.guide.modules.memory.repository.UserAbilityProfileRepository;
import interview.guide.modules.memory.service.AbilityEvidenceDerivationService;
import interview.guide.modules.memory.service.AbilityMemoryDeletionService;
import interview.guide.modules.memory.service.AbilityProfileCommandService;
import interview.guide.modules.memory.service.AbilityProfileQueryService;
import interview.guide.modules.memory.service.AbilityScorePolicy;
import interview.guide.modules.memory.service.DerivationResult;
import interview.guide.modules.resume.model.ResumeEntity;
import interview.guide.modules.resume.repository.ResumeRepository;
import java.time.Duration;
import java.util.ArrayList;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.regex.Pattern;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.transaction.autoconfigure.TransactionAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(classes = UserAbilityMemoryJourneyTest.TestApplication.class, properties = {
    "spring.datasource.url=jdbc:h2:mem:ability-journey;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa",
    "spring.datasource.password=", "spring.jpa.hibernate.ddl-auto=create-drop"
})
@DisplayName("用户能力记忆完整旅程")
class UserAbilityMemoryJourneyTest {
  @Configuration(proxyBeanMethods = false)
  @EnableWebMvc
  @EntityScan("interview.guide.modules")
  @EnableJpaRepositories("interview.guide.modules")
  @ComponentScan(basePackageClasses = QuestionPlanningService.class)
  @ImportAutoConfiguration({DataSourceAutoConfiguration.class, HibernateJpaAutoConfiguration.class,
      TransactionAutoConfiguration.class})
  @Import({AdminAccountService.class, AuthService.class, InviteCodeService.class,
      UsernameNormalizer.class, AuthUserQueryService.class, AuthResponseMapperImpl.class,
      CurrentUserProvider.class, AbilityEvidenceDerivationService.class,
      AbilityMemoryDeletionService.class, InterviewPersistenceService.class,
      DynamicInterviewLifecycle.class, DynamicInterviewSessionSupport.class,
      DynamicInterviewProperties.class,
      InterviewSessionService.class, InterviewQuestionService.class, InterviewQuestionCountService.class,
      InterviewSessionCache.class, InterviewSkillService.class,
      AbilityProfileCommandService.class, AbilityProfileQueryService.class,
      AbilityScorePolicy.class, AbilityMemoryMapperImpl.class, InterviewController.class,
      GlobalExceptionHandler.class})
  static class TestApplication {
    @Bean ObjectMapper objectMapper() { return new ObjectMapper(); }
    @Bean InterviewQuestionProperties interviewQuestionProperties() {
      return new InterviewQuestionProperties();
    }
    @Bean PromptSanitizer promptSanitizer() {
      return new PromptSanitizer(new LlmProviderProperties());
    }
  }

  @Autowired AdminAccountService accounts;
  @Autowired AuthService auth;
  @Autowired InviteCodeService invites;
  @Autowired CurrentUserProvider currentUser;
  @Autowired AbilityEvidenceDerivationService derivation;
  @Autowired AbilityProfileCommandService decisions;
  @MockitoSpyBean AbilityProfileQueryService memory;
  @Autowired QuestionPlanningService planning;
  @Autowired InterviewQuestionPlanRepository plans;
  @Autowired InterviewSessionRepository sessions;
  @Autowired AbilityProfileProposalRepository proposals;
  @Autowired UserAbilityProfileRepository profiles;
  @Autowired ResumeRepository resumes;
  @Autowired WebApplicationContext web;
  @Autowired ObjectMapper mapper;
  @Autowired InterviewSkillService skills;
  @MockitoBean InterviewHistoryService history;
  @Autowired InterviewPersistenceService interviewPersistence;
  @MockitoBean RedisService redis;
  @MockitoBean InterviewEvaluationTaskPublisher evaluationTasks;
  @MockitoBean MemoryDerivationPublicationService memoryPublication;
  @MockitoBean AnswerEvaluationService evaluation;
  @MockitoBean LlmProviderRegistry llm;
  @MockitoBean StructuredOutputInvoker invoker;
  @MockitoBean DynamicInterviewCreationService dynamicCreation;
  @MockitoBean DynamicInterviewCommandService dynamicCommands;
  @MockitoBean DynamicInterviewQueryService dynamicQueries;
  private final ConcurrentLinkedQueue<String> modelPrompts = new ConcurrentLinkedQueue<>();

  @BeforeEach
  void fakeExternalQuestionModel() {
    modelPrompts.clear();
    when(llm.getChatClientOrDefault(any())).thenReturn(mock(ChatClient.class));
    when(invoker.invoke(any(), anyString(), anyString(), any(), any(),
        anyString(), anyString(), any())).thenAnswer(call -> {
      String prompt = call.getArgument(2);
      modelPrompts.add(prompt);
      var matcher = Pattern.compile("slotId=(slot-\\d+); categoryKey=([A-Z_]+)").matcher(prompt);
      var output = new ArrayList<InterviewQuestionService.QuestionDTO>();
      while (matcher.find()) {
        output.add(new InterviewQuestionService.QuestionDTO(
            "Question for " + matcher.group(2), matcher.group(2), matcher.group(2),
            matcher.group(1), null, List.of()));
      }
      assertThat(output).isNotEmpty();
      return new InterviewQuestionService.QuestionListDTO(output);
    });
  }

  @AfterEach
  void clearIdentity() {
    SecurityContextHolder.clearContext();
  }

  @Test
  @DisplayName("管理员邀请双用户，确认后的弱项影响下一场且解释快照隔离")
  void invitedOwnersSeeOnlyConfirmedSnapshot() throws Exception {
    UserEntity admin = accounts.createUser(new CreateAdminUserCommand(
        "journey-admin", "journey-password", "Admin", UserRole.ADMIN));
    UserEntity a = invited(admin, "journey-a");
    UserEntity b = invited(admin, "journey-b");
    assertThat(auth.authenticate("journey-a", "journey-password").getId()).isEqualTo(a.getId());
    assertThat(auth.authenticate("journey-b", "journey-password").getId()).isEqualTo(b.getId());

    ResumeEntity aResume = resume(a, "same-content-hash");
    ResumeEntity bResume = resume(b, "same-content-hash");
    assertThat(resumes.findByOwnerIdAndId(a.getId(), bResume.getId())).isEmpty();
    assertThat(resumes.findByOwnerIdAndId(b.getId(), aResume.getId())).isEmpty();

    authenticate(a);
    MockMvc mvc = MockMvcBuilders.webAppContextSetup(web).build();
    String evaluated = createInterview(mvc, aResume.getId());
    InterviewSessionEntity first = sessions.findByOwnerIdAndSessionId(a.getId(), evaluated)
        .orElseThrow();
    List<InterviewQuestionDTO> firstQuestions = mapper.readValue(first.getQuestionsJson(),
        new TypeReference<>() {});
    InterviewQuestionDTO firstMysql = firstQuestions.stream()
        .filter(question -> "MYSQL".equals(question.type())).findFirst().orElseThrow();
    interviewPersistence.saveAnswer(evaluated, firstMysql.questionIndex(), firstMysql.question(),
        "MYSQL", "MySQL",
        "uncertain answer", 30, "Needs practice");
    assertThat(interviewPersistence.saveReport(evaluated, new InterviewReportDTO(evaluated,
        firstQuestions.size(), 30,
        List.of(new InterviewReportDTO.CategoryScore("MYSQL", "MySQL", 30, 1)),
        List.of(new InterviewReportDTO.QuestionEvaluation(firstMysql.questionIndex(),
            firstMysql.question(), "MYSQL",
            "MySQL", "uncertain answer", 30, "Needs practice")), "Practice indexes",
        List.of(), List.of("Index usage"), List.of()), UUID.randomUUID().toString()))
        .isEqualTo(InterviewPersistenceService.CompletionResult.CREATED);
    assertThat(derivation.derive(evaluated, UUID.randomUUID()))
        .isEqualTo(DerivationResult.CREATED);
    assertThat(derivation.derive(evaluated, UUID.randomUUID()))
        .isEqualTo(DerivationResult.ALREADY_COMPLETED);
    var proposed = proposals.findByOwnerIdAndInterviewSessionId(a.getId(), first.getId());
    assertThat(proposed).hasSize(1);
    assertThat(memory.profiles(a.getId(), "java-backend")).isEmpty();

    authenticate(a);
    assertThat(planning.plan(command()).mode()).isEqualTo(PlanningMode.FIXED);
    decisions.acceptWithEdit(a.getId(), proposed.getFirst().getId(),
        proposed.getFirst().getVersion(), AbilityLevel.WEAK, "MYSQL_INTERNAL_MEMORY_SECRET");
    assertThat(memory.profiles(b.getId(), "java-backend")).isEmpty();
    var selected = planning.plan(command());
    assertThat(selected.mode()).isEqualTo(PlanningMode.ADAPTIVE);
    assertThat(selected.slots()).anySatisfy(slot -> {
      assertThat(slot.categoryKey()).isEqualTo("MYSQL");
      assertThat(slot.purpose().name()).isEqualTo("WEAKNESS_REINFORCEMENT");
    });

    String next = createInterview(mvc, aResume.getId());
    String body = mvc.perform(get("/api/interview/sessions/{id}/plan", next))
        .andReturn().getResponse().getContentAsString();
    assertThat((String) JsonPath.read(body, "$.data.mode")).isEqualTo("ADAPTIVE");
    Map<String, Integer> counts = JsonPath.read(body, "$.data.purposeCounts");
    // Six covered categories leave four slots: weights 5:3:1 yield 2 baseline, 1 weak, 1 explore.
    assertThat(counts).isEqualTo(Map.of("MANDATORY", 1, "BASELINE", 7,
        "WEAKNESS_REINFORCEMENT", 1, "EXPLORATION", 1, "STRENGTH_CALIBRATION", 0));
    List<Map<String, Object>> reasons = JsonPath.read(body, "$.data.questions");
    var mysqlWeak = reasons.stream().filter(reason ->
        "MYSQL".equals(reason.get("categoryKey"))
            && "WEAKNESS_REINFORCEMENT".equals(reason.get("purpose"))).toList();
    assertThat(mysqlWeak).hasSize(1).allSatisfy(reason ->
        assertThat(reason.get("reason")).isEqualTo("巩固已确认的薄弱项"));
    List<InterviewQuestionDTO> savedQuestions = mapper.readValue(sessions
        .findByOwnerIdAndSessionId(a.getId(), next).orElseThrow().getQuestionsJson(),
        new TypeReference<>() {});
    assertThat(savedQuestions).hasSize(10);
    for (var reason : mysqlWeak) {
      assertThat(savedQuestions).anySatisfy(question -> {
        assertThat(question.questionIndex()).isEqualTo(reason.get("questionIndex"));
        assertThat(question.slotId()).isEqualTo(reason.get("slotId"));
        assertThat(question.type()).isEqualTo("MYSQL");
      });
    }
    assertThat(modelPrompts).anySatisfy(prompt ->
        assertThat(prompt).contains("purpose=WEAKNESS_REINFORCEMENT"));
    assertThat(body).doesNotContain("MYSQL_INTERNAL_MEMORY_SECRET", "focusTopics", "avoidedTopics");
    assertThat(plans.findFirstByOwnerIdAndSessionSessionIdOrderByIdDesc(a.getId(), next)).isPresent();

    var profile = profiles.findByOwnerIdAndSkillIdAndCategoryKey(a.getId(),
        "java-backend", "MYSQL").orElseThrow();
    profile.setSummary("UPDATED_PRIVATE_MEMORY");
    profiles.saveAndFlush(profile);
    assertThat(profiles.findByOwnerIdAndSkillIdAndCategoryKey(a.getId(),
        "java-backend", "MYSQL").orElseThrow().getSummary())
        .isEqualTo("UPDATED_PRIVATE_MEMORY");
    assertThat(mvc.perform(get("/api/interview/sessions/{id}/plan", next))
        .andReturn().getResponse().getContentAsString()).isEqualTo(body)
        .doesNotContain("UPDATED_PRIVATE_MEMORY");
    authenticate(b);
    assertThat(mvc.perform(get("/api/interview/sessions/{id}/plan", next))
        .andReturn().getResponse().getContentAsString()).contains("\"code\":3001")
        .doesNotContain("MYSQL_INTERNAL_MEMORY_SECRET");
    assertThat(planning.plan(command()).mode()).isEqualTo(PlanningMode.FIXED);
    String bInterview = createInterview(mvc, bResume.getId());
    String bPlan = mvc.perform(get("/api/interview/sessions/{id}/plan", bInterview))
        .andReturn().getResponse().getContentAsString();
    assertThat((String) JsonPath.read(bPlan, "$.data.mode")).isEqualTo("FIXED");
    assertThat((Integer) JsonPath.read(bPlan,
        "$.data.purposeCounts.WEAKNESS_REINFORCEMENT")).isZero();

    authenticate(a);
    doThrow(new IllegalStateException("temporary memory read failure"))
        .when(memory).profiles(a.getId(), "java-backend");
    String fallback = createInterview(mvc, aResume.getId());
    String fixedPlan = mvc.perform(get("/api/interview/sessions/{id}/plan", fallback))
        .andReturn().getResponse().getContentAsString();
    assertThat((String) JsonPath.read(fixedPlan, "$.data.mode")).isEqualTo("FIXED");
    assertThat((Integer) JsonPath.read(fixedPlan,
        "$.data.purposeCounts.WEAKNESS_REINFORCEMENT")).isZero();
  }

  @Test
  @DisplayName("旧会话没有计划快照时返回空摘要且不泄露其他用户会话")
  void legacyAndForeignSessionAreSafe() throws Exception {
    UserEntity admin = accounts.createUser(new CreateAdminUserCommand(
        "legacy-admin", "journey-password", "Admin", UserRole.ADMIN));
    UserEntity owner = invited(admin, "legacy-owner");
    UserEntity other = invited(admin, "legacy-other");
    String id = UUID.randomUUID().toString();
    evaluatedSession(owner, id);
    MockMvc mvc = MockMvcBuilders.webAppContextSetup(web).build();
    authenticate(owner);
    String legacy = mvc.perform(get("/api/interview/sessions/{id}/plan", id))
        .andReturn().getResponse().getContentAsString();
    assertThat(legacy).contains("\"available\":false", "\"questions\":[]");
    authenticate(other);
    String denied = mvc.perform(get("/api/interview/sessions/{id}/plan", id))
        .andReturn().getResponse().getContentAsString();
    assertThat(denied).contains("\"code\":3001").doesNotContain("\"available\":false");
  }

  private UserEntity invited(UserEntity admin, String username) {
    String code = invites.issue(Duration.ofHours(1), admin.getId()).rawCode();
    return invites.register(code, username, "journey-password", username);
  }

  private String createInterview(MockMvc mvc, Long resumeId) throws Exception {
    String response = mvc.perform(post("/api/interview/sessions")
        .contentType(MediaType.APPLICATION_JSON)
        .content(mapper.writeValueAsString(new CreateInterviewRequest(null, 10,
            resumeId, true, null, "java-backend", "mid", null, null))))
        .andReturn().getResponse().getContentAsString();
    assertThat((Integer) JsonPath.read(response, "$.code"))
        .as("createSession response=%s", response)
        .isEqualTo(200);
    return JsonPath.read(response, "$.data.sessionId");
  }

  private void authenticate(UserEntity user) {
    var principal = auth.authenticate(user.getUsername(), "journey-password");
    var current = new CurrentUser(principal.getId(),
        principal.getUsername(), principal.getDisplayName(), principal.getRole(), false);
    SecurityContextHolder.getContext().setAuthentication(
        new UsernamePasswordAuthenticationToken(current, null, List.of()));
    assertThat(currentUser.requireUserId()).isEqualTo(user.getId());
  }

  private ResumeEntity resume(UserEntity owner, String hash) {
    var value = new ResumeEntity();
    value.setOwner(owner);
    value.setFileHash(hash);
    value.setOriginalFilename("resume.txt");
    value.setStorageKey("resumes/" + owner.getId());
    value.setResumeText("MYSQL experience");
    return resumes.saveAndFlush(value);
  }

  private InterviewSessionEntity evaluatedSession(UserEntity owner, String id) {
    var value = new InterviewSessionEntity();
    value.setOwner(owner);
    value.setSessionId(id);
    value.setSkillId("java-backend");
    value.setDifficulty("mid");
    value.setQuestionsJson("[]");
    value.setStatus(InterviewSessionEntity.SessionStatus.EVALUATED);
    value.setEvaluateStatus(AsyncTaskStatus.COMPLETED);
    return sessions.saveAndFlush(value);
  }

  private QuestionPlanningCommand command() {
    return new QuestionPlanningCommand("java-backend", skills.getSkill("java-backend").categories(),
        10, "mid", false, Map.of(), List.of(), Set.of());
  }
}
