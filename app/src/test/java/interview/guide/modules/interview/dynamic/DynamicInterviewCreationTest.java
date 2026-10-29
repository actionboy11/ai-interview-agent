package interview.guide.modules.interview.dynamic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

import interview.guide.common.exception.BusinessException;
import interview.guide.modules.auth.model.UserEntity;
import interview.guide.modules.auth.model.UserRole;
import interview.guide.modules.auth.model.UserStatus;
import interview.guide.modules.auth.repository.UserRepository;
import interview.guide.modules.auth.security.CurrentUserProvider;
import interview.guide.modules.interview.dynamic.model.DynamicPhase;
import interview.guide.modules.interview.dynamic.model.InterviewExecutionMode;
import interview.guide.modules.interview.dynamic.model.AssessmentTarget;
import interview.guide.modules.interview.dynamic.model.DynamicMainQuestion;
import interview.guide.modules.interview.dynamic.repository.DynamicInterviewRuntimeRepository;
import interview.guide.modules.interview.dynamic.service.DynamicInterviewCreationService;
import interview.guide.modules.interview.dynamic.service.DynamicInterviewProperties;
import interview.guide.modules.interview.dynamic.service.DynamicMainQuestionGenerator;
import interview.guide.modules.interview.model.CreateInterviewRequest;
import interview.guide.modules.interview.model.HistoricalQuestion;
import interview.guide.modules.interview.planning.QuestionPlanningService;
import interview.guide.modules.interview.planning.model.QuestionSourceMode;
import interview.guide.modules.interview.planning.model.QuestionPlanningCommand;
import interview.guide.modules.interview.planning.repository.InterviewQuestionPlanRepository;
import interview.guide.modules.interview.planning.service.InterviewQuestionPlanPersistenceService;
import interview.guide.modules.interview.repository.InterviewSessionRepository;
import interview.guide.modules.interview.service.InterviewPersistenceService;
import interview.guide.modules.interview.service.InterviewQuestionService;
import interview.guide.modules.resume.repository.ResumeRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Collections;
import java.util.UUID;
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
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(classes = DynamicInterviewCreationTest.JpaApplication.class, properties = {
    "spring.datasource.url=jdbc:h2:mem:dynamic-creation;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa",
    "spring.datasource.password=", "spring.jpa.hibernate.ddl-auto=create-drop",
    "spring.jpa.show-sql=false"
})
@DisplayName("动态面试创建与短事务发布")
class DynamicInterviewCreationTest {
  @Configuration(proxyBeanMethods = false)
  @EntityScan("interview.guide.modules")
  @EnableJpaRepositories(basePackageClasses = {DynamicInterviewRuntimeRepository.class,
      InterviewSessionRepository.class, InterviewQuestionPlanRepository.class,
      UserRepository.class, ResumeRepository.class})
  @ImportAutoConfiguration({DataSourceAutoConfiguration.class, HibernateJpaAutoConfiguration.class,
      TransactionAutoConfiguration.class})
  @Import({InterviewQuestionPlanPersistenceService.class, DynamicInterviewCreationService.class,
      DynamicInterviewProperties.class})
  static class JpaApplication {
    @Bean ObjectMapper mapper() { return JsonMapper.builder().build(); }
    @Bean CurrentUserProvider currentUser() { return mock(CurrentUserProvider.class); }
    @Bean InterviewPersistenceService persistence() { return mock(InterviewPersistenceService.class); }
    @Bean InterviewQuestionService questions() { return mock(InterviewQuestionService.class); }
    @Bean QuestionPlanningService planning() { return mock(QuestionPlanningService.class); }
    @Bean DynamicMainQuestionGenerator generator() { return mock(DynamicMainQuestionGenerator.class); }
  }

  @Autowired DynamicInterviewCreationService service;
  @Autowired CurrentUserProvider currentUser;
  @Autowired InterviewPersistenceService persistence;
  @Autowired InterviewQuestionService questions;
  @Autowired QuestionPlanningService planning;
  @Autowired DynamicMainQuestionGenerator generator;
  @Autowired DynamicInterviewProperties properties;
  @Autowired UserRepository users;
  @Autowired InterviewSessionRepository sessions;
  @Autowired InterviewQuestionPlanRepository plans;
  @Autowired DynamicInterviewRuntimeRepository runtimes;
  @Autowired ObjectMapper mapper;
  private UUID ownerId;

  @BeforeEach
  void setUp() {
    reset(currentUser, persistence, questions, planning, generator);
    String key = UUID.randomUUID().toString();
    ownerId = users.saveAndFlush(UserEntity.builder().username(key).normalizedUsername(key)
        .displayName(key).passwordHash("test-only").role(UserRole.USER).status(UserStatus.ACTIVE)
        .createdAt(Instant.now()).updatedAt(Instant.now()).build()).getId();
    when(currentUser.requireUserId()).thenReturn(ownerId);
    when(persistence.getHistoricalQuestions("java", null)).thenReturn(List.of());
    when(questions.resolveSkillForPlanning(eq("java"), any(), any()))
        .thenReturn(DynamicMainQuestionGeneratorTest.SKILL);
    when(planning.plan(any())).thenReturn(
        DynamicMainQuestionGeneratorTest.plan(8, QuestionSourceMode.DIRECTION));
    properties.setDecisionTimeout(Duration.ofSeconds(45));
    properties.setMaxFollowUpsPerMain(2);
  }

  @Test
  @DisplayName("完整池与计划冻结，只发布首题实际索引零且客户端看不到目标")
  void creationOnlyPublishesFirstMainAtIndexZero() throws Exception {
    var plan = DynamicMainQuestionGeneratorTest.plan(8, QuestionSourceMode.DIRECTION);
    var realGenerator = DynamicMainQuestionGeneratorTest.fixture(
        DynamicMainQuestionGeneratorTest.json(DynamicMainQuestionGeneratorTest.rows(plan, 2)));
    when(generator.generate(any(), any(), any(), any())).thenAnswer(invocation -> {
      assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
      assertThat(sessions.findByOwnerIdOrderByCreatedAtDesc(ownerId)).hasSize(1);
      return realGenerator.generator().generate("test", DynamicMainQuestionGeneratorTest.SKILL,
          null, plan);
    });

    var result = service.create(request());

    assertThat(result).isNotNull();
    assertThat(result.version()).isZero();
    assertThat(result.phase()).isEqualTo(DynamicPhase.WAITING_ANSWER);
    assertThat(result.plannedMainQuestionCount()).isEqualTo(8);
    assertThat(result.currentMainOrdinal()).isEqualTo(1);
    assertThat(result.totalQuestions()).isEqualTo(1);
    assertThat(result.mainQuestionCount()).isEqualTo(1);
    assertThat(result.followUpQuestionCount()).isZero();
    assertThat(result.currentQuestion().questionIndex()).isZero();
    assertThat(result.currentQuestion().slotId()).isEqualTo("slot-1");
    assertThat(mapper.writeValueAsString(result)).doesNotContain("assessmentTargets", "target-1",
        "slot-2");
    var session = sessions.findByOwnerIdAndSessionId(ownerId, result.sessionId()).orElseThrow();
    assertThat(session.getExecutionMode()).isEqualTo(InterviewExecutionMode.DYNAMIC_FOLLOW_UP);
    assertThat(session.getQuestionsJson()).doesNotContain("slot-2", "assessmentTargets");
    assertThat(session.getTotalQuestions()).isEqualTo(1);
    var runtime = runtimes.findByOwner_IdAndSession_SessionId(ownerId, result.sessionId())
        .orElseThrow();
    assertThat(mapper.readTree(runtime.getMainQuestionsJson()).size()).isEqualTo(8);
    assertThat(runtime.getCurrentMainOrdinal()).isZero();
    assertThat(runtime.getExecutionVersion()).isEqualTo(1);
    assertThat(runtime.getTotalFollowUpBudget()).isEqualTo(8);
    assertThat(runtime.getDecisionTimeoutSeconds()).isEqualTo(45);
    assertThat(mapper.readTree(runtime.getPolicyJson()).path("decisionTimeoutSeconds").asInt())
        .isEqualTo(45);
    assertThat(plans.findByOwnerIdAndSessionSessionId(ownerId, result.sessionId()))
        .singleElement().satisfies(saved -> {
          assertThat(saved.getPlanVersion()).isEqualTo(2);
          assertThat(mapper.readTree(saved.getPlanJson()).path("slots").get(7)
              .path("slotId").asString()).isEqualTo("slot-8");
        });
    properties.setDecisionTimeout(Duration.ofSeconds(60));
    assertThat(runtimes.findById(runtime.getId()).orElseThrow().getDecisionTimeoutSeconds())
        .isEqualTo(45);
  }

  @Test
  @DisplayName("无合格目标耗尽重试后清除壳会话和计划，不保存运行半成品")
  void invalidOutputDiscardsShell() throws Exception {
    var plan = DynamicMainQuestionGeneratorTest.plan(8, QuestionSourceMode.DIRECTION);
    var fixture = DynamicMainQuestionGeneratorTest.fixture(
        DynamicMainQuestionGeneratorTest.json(DynamicMainQuestionGeneratorTest.rows(plan, 1)));
    when(generator.generate(any(), any(), any(), any())).thenAnswer(invocation ->
        fixture.generator().generate("test", DynamicMainQuestionGeneratorTest.SKILL, null, plan));
    assertThatThrownBy(() -> service.create(request())).isInstanceOf(BusinessException.class);
    assertThat(sessions.findByOwnerIdOrderByCreatedAtDesc(ownerId)).isEmpty();
    assertThat(plans.findAll().stream().filter(planEntity ->
        planEntity.getOwner().getId().equals(ownerId))).isEmpty();
    assertThat(runtimes.findAll().stream().filter(runtime ->
        runtime.getOwner().getId().equals(ownerId))).isEmpty();
    assertThat(fixture.model().calls.get()).isEqualTo(2);
  }

  @Test
  @DisplayName("运行快照写入失败会回滚已发布题并清理壳记录")
  void publicationFailureRollsBackAndDiscardsShell() {
    var oversizedSlot = new DynamicMainQuestion("x".repeat(101), "解释事务", "JAVA", "Java",
        "事务", List.of(new AssessmentTarget("a", "原子性"), new AssessmentTarget("b", "边界")),
        "mid", List.of());
    when(generator.generate(any(), any(), any(), any()))
        .thenReturn(Collections.nCopies(8, oversizedSlot));

    assertThatThrownBy(() -> service.create(request()))
        .isInstanceOf(DataIntegrityViolationException.class);

    assertThat(sessions.findByOwnerIdOrderByCreatedAtDesc(ownerId)).isEmpty();
    assertThat(plans.findAll().stream().filter(plan -> plan.getOwner().getId().equals(ownerId)))
        .isEmpty();
    assertThat(runtimes.findAll().stream().filter(runtime ->
        runtime.getOwner().getId().equals(ownerId))).isEmpty();
  }

  @Test
  @DisplayName("动态创建复用历史和简历规划上下文，不另起分配算法")
  void reusesExistingHistoryAndPlanningContext() throws Exception {
    var history = List.of(new HistoricalQuestion("旧事务题", "JAVA", "事务"));
    when(persistence.getHistoricalQuestions("java", null)).thenReturn(history);
    var plan = DynamicMainQuestionGeneratorTest.plan(8, QuestionSourceMode.DIRECTION);
    when(planning.plan(any())).thenAnswer(invocation -> {
      QuestionPlanningCommand command = invocation.getArgument(0);
      assertThat(command.historicalQuestions()).containsExactlyElementsOf(history);
      assertThat(command.recentCoverage()).containsEntry("JAVA", 1);
      assertThat(command.totalQuestions()).isEqualTo(8);
      assertThat(command.baseDifficulty()).isEqualTo("mid");
      assertThat(command.hasResume()).isTrue();
      assertThat(command.groundedResumeCategoryKeys()).contains("JAVA");
      return plan;
    });
    var fixture = DynamicMainQuestionGeneratorTest.fixture(
        DynamicMainQuestionGeneratorTest.json(DynamicMainQuestionGeneratorTest.rows(plan, 2)));
    when(generator.generate(any(), any(), any(), any())).thenAnswer(invocation ->
        fixture.generator().generate("test", DynamicMainQuestionGeneratorTest.SKILL, null, plan));

    var result = service.create(new CreateInterviewRequest("熟悉 Java", 8, null, true,
        "test", "java", "mid", null, null));

    assertThat(result.plannedMainQuestionCount()).isEqualTo(8);
  }

  private CreateInterviewRequest request() {
    return new CreateInterviewRequest("", 8, null, true, "test", "java", "mid", null, null);
  }
}
