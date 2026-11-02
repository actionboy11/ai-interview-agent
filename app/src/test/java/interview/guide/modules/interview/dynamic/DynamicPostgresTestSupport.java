package interview.guide.modules.interview.dynamic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;
import static org.awaitility.Awaitility.await;

import interview.guide.infrastructure.redis.RedisService;
import interview.guide.modules.auth.model.UserEntity;
import interview.guide.modules.auth.model.UserRole;
import interview.guide.modules.auth.model.UserStatus;
import interview.guide.modules.auth.security.CurrentUserProvider;
import interview.guide.modules.interview.dynamic.messaging.FollowUpDecisionProducer;
import interview.guide.modules.interview.dynamic.service.DynamicAbilityEvidenceSourceService;
import interview.guide.modules.interview.dynamic.service.DynamicAdvancePolicy;
import interview.guide.modules.interview.dynamic.service.DynamicDecisionDispatchService;
import interview.guide.modules.interview.dynamic.service.DynamicDecisionRecoveryService;
import interview.guide.modules.interview.dynamic.service.DynamicDecisionStoreService;
import interview.guide.modules.interview.dynamic.service.DynamicDecisionWorker;
import interview.guide.modules.interview.dynamic.service.DynamicEvaluationDispatchService;
import interview.guide.modules.interview.dynamic.service.DynamicEvaluationOutboxStoreService;
import interview.guide.modules.interview.dynamic.service.DynamicInterviewCommandService;
import interview.guide.modules.interview.dynamic.service.DynamicInterviewLifecycle;
import interview.guide.modules.interview.dynamic.service.DynamicInterviewProperties;
import interview.guide.modules.interview.dynamic.service.DynamicInterviewQueryService;
import interview.guide.modules.interview.dynamic.service.DynamicInterviewTransitionService;
import interview.guide.modules.interview.dynamic.service.FollowUpDecisionService;
import interview.guide.modules.interview.messaging.InterviewEvaluationTaskPublisher;
import interview.guide.modules.interview.repository.InterviewSessionRepository;
import interview.guide.modules.interview.service.InterviewPersistenceService;
import interview.guide.modules.memory.repository.AbilityProfileProposalRepository;
import interview.guide.modules.memory.repository.UserAbilityEvidenceRepository;
import interview.guide.modules.memory.repository.UserAbilityProfileRepository;
import interview.guide.modules.memory.service.AbilityEvidenceDerivationService;
import interview.guide.modules.memory.service.AbilityMemoryDeletionService;
import interview.guide.modules.memory.service.AbilityProfileCommandService;
import interview.guide.modules.memory.service.AbilityScorePolicy;
import interview.guide.modules.memory.service.AbilityProfileQueryService;
import interview.guide.modules.memory.mapper.AbilityMemoryMapper;
import org.mapstruct.factory.Mappers;
import interview.guide.infrastructure.file.FileHashService;
import interview.guide.infrastructure.mapper.ResumeMapper;
import interview.guide.modules.resume.service.ResumePersistenceService;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ConcurrentLinkedQueue;
import interview.guide.common.exception.BusinessException;
import interview.guide.modules.interview.dynamic.model.DynamicInterviewEvaluationSnapshot;
import interview.guide.modules.interview.dynamic.model.DynamicGroupEvaluation;
import interview.guide.modules.interview.dynamic.model.SubmissionIntent;
import interview.guide.modules.interview.dynamic.model.FollowUpDecisionEntity.Action;
import interview.guide.modules.interview.dynamic.model.FollowUpDecisionEntity.Reason;
import interview.guide.modules.interview.dynamic.service.DynamicInterviewTransitionService.DecisionApplication;
import interview.guide.modules.interview.dynamic.service.DynamicInterviewEvaluationService;
import interview.guide.modules.interview.dynamic.service.DynamicInterviewEvaluationService.EvaluationOutput;
import interview.guide.modules.interview.dynamic.service.DynamicInterviewEvaluationService.QuestionFeedback;
import interview.guide.modules.interview.model.InterviewReportDTO;
import interview.guide.modules.interview.planning.service.InterviewQuestionPlanPersistenceService;
import org.hibernate.resource.jdbc.spi.StatementInspector;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.transaction.autoconfigure.TransactionAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/** One disposable infrastructure pair per test JVM; no application scan or background listeners. */
@SpringBootTest(classes = DynamicPostgresTestSupport.Application.class, properties = {
    "spring.datasource.driver-class-name=org.postgresql.Driver",
    "spring.jpa.hibernate.ddl-auto=validate", "spring.jpa.show-sql=false",
    "app.interview.dynamic.enabled=true"
})
abstract class DynamicPostgresTestSupport extends DynamicDecisionTestSupport {
  static final String SCHEMA = "acceptance_" + UUID.randomUUID().toString().replace("-", "");
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
      DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"))
      .withDatabaseName("dynamic_acceptance").withUsername("acceptance")
      .withPassword(UUID.randomUUID().toString());
  static final GenericContainer<?> REDIS = new GenericContainer<>(
      DockerImageName.parse("redis:7.4-alpine")).withExposedPorts(6379);
  static {
    POSTGRES.start();
    REDIS.start();
    Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
        .schemas(SCHEMA).defaultSchema(SCHEMA)
        .placeholders(Map.of("legacy-owner-username", "")).load().migrate();
    Runtime.getRuntime().addShutdownHook(new Thread(() -> {
      REDIS.stop();
      POSTGRES.stop();
    }, "dynamic-acceptance-container-cleanup"));
  }

  @DynamicPropertySource
  static void infrastructure(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", () -> POSTGRES.getJdbcUrl()
        + "&currentSchema=" + SCHEMA);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
    registry.add("spring.datasource.hikari.maximum-pool-size", () -> 10);
    registry.add("spring.jpa.properties.hibernate.session_factory.statement_inspector",
        () -> SqlCapture.class.getName());
  }

  @Configuration(proxyBeanMethods = false)
  @EntityScan("interview.guide.modules")
  @EnableJpaRepositories("interview.guide.modules")
  @EnableConfigurationProperties(DynamicInterviewProperties.class)
  @ImportAutoConfiguration({DataSourceAutoConfiguration.class, HibernateJpaAutoConfiguration.class,
      TransactionAutoConfiguration.class})
  @Import({DynamicAdvancePolicy.class, DynamicInterviewCommandService.class,
      DynamicInterviewQueryService.class, DynamicInterviewTransitionService.class,
      DynamicDecisionStoreService.class, DynamicEvaluationOutboxStoreService.class,
      DynamicDecisionWorker.class, DynamicDecisionDispatchService.class,
      DynamicDecisionRecoveryService.class, DynamicEvaluationDispatchService.class,
      FollowUpDecisionProducer.class, InterviewPersistenceService.class,
      DynamicInterviewLifecycle.class, DynamicAbilityEvidenceSourceService.class,
      AbilityEvidenceDerivationService.class, AbilityMemoryDeletionService.class,
      AbilityProfileCommandService.class, AbilityScorePolicy.class,
      InterviewQuestionPlanPersistenceService.class, AbilityProfileQueryService.class,
      ResumePersistenceService.class, FileHashService.class})
  static class Application {
    @Bean ObjectMapper mapper() { return JsonMapper.builder().build(); }
    @Bean MutableClock clock() { return new MutableClock(); }
    @Bean RedisService redis() { return mock(RedisService.class); }
    @Bean FollowUpDecisionService model() { return mock(FollowUpDecisionService.class); }
    @Bean CurrentUserProvider currentUser() { return mock(CurrentUserProvider.class); }
    @Bean AbilityMemoryMapper abilityMapper() { return Mappers.getMapper(AbilityMemoryMapper.class); }
    @Bean ResumeMapper resumeMapper() { return Mappers.getMapper(ResumeMapper.class); }
    @Bean InterviewEvaluationTaskPublisher publisher() {
      return mock(InterviewEvaluationTaskPublisher.class);
    }
  }

  @Autowired DataSource dataSource;
  @Autowired InterviewPersistenceService persistence;
  @Autowired InterviewSessionRepository sessions;
  @Autowired AbilityEvidenceDerivationService derivation;
  @Autowired AbilityMemoryDeletionService deletion;
  @Autowired AbilityProfileCommandService profileCommands;
  @Autowired AbilityProfileProposalRepository proposals;
  @Autowired UserAbilityEvidenceRepository evidence;
  @Autowired UserAbilityProfileRepository profiles;
  @Autowired CurrentUserProvider currentUser;
  @Autowired InterviewQuestionPlanPersistenceService planPersistence;
  @Autowired AbilityProfileQueryService profileQueries;
  @Autowired ResumePersistenceService resumePersistence;

  @Override
  @BeforeEach
  void setup() {
    reset(redis, model, publisher);
    clock.now = NOW;
    properties.setWorkLease(Duration.ofSeconds(30));
    assertThat(properties.isEnabled()).as("explicit isolated true overrides Gradle false").isTrue();
    String key = UUID.randomUUID().toString();
    owner = UserEntity.builder().username(key).normalizedUsername(key).displayName(key)
        .passwordHash("test-only").role(UserRole.USER).status(UserStatus.ACTIVE)
        .createdAt(NOW).updatedAt(NOW).build();
    tx.executeWithoutResult(status -> em.persist(owner));
    when(currentUser.requireUserId()).thenReturn(owner.getId());
    session = createSession(3);
    tx.executeWithoutResult(status -> em.find(session.getClass(), session.getId())
        .setSkillId("java-backend"));
  }

  JdbcTemplate jdbc() { return new JdbcTemplate(dataSource); }

  @AfterEach
  void removeOnlyThisTestSessions() {
    if (owner != null) {
      deletion.deleteOwnedSessions(owner.getId(), sessions.findByOwnerIdOrderByCreatedAtDesc(owner.getId())
          .stream().map(item -> item.getId()).toList());
    }
  }

  DecisionApplication append() {
    return new DecisionApplication(Action.FOLLOW_UP, Reason.IMPORTANT_GAP, "边界待观察",
        "{}", "追加边界问题", null, null);
  }

  /** Both production operations must visibly block on separate PostgreSQL backend PIDs. */
  List<Object> race(String lockSql, Callable<?> first, Callable<?> second) throws Exception {
    var executor = new ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS, new LinkedBlockingQueue<>());
    var barrier = new CyclicBarrier(3);
    try (var blocker = dataSource.getConnection()) {
      blocker.setAutoCommit(false);
      try (var statement = blocker.createStatement()) { statement.executeQuery(lockSql); }
      Callable<Object> a = () -> { barrier.await(10, TimeUnit.SECONDS); return first.call(); };
      Callable<Object> b = () -> { barrier.await(10, TimeUnit.SECONDS); return second.call(); };
      Future<Object> left = executor.submit(a);
      Future<Object> right = executor.submit(b);
      barrier.await(10, TimeUnit.SECONDS);
      try {
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(jdbc().queryForList(
            "select pid from pg_stat_activity where datname = current_database() "
                + "and wait_event_type = 'Lock' and cardinality(pg_blocking_pids(pid)) > 0",
            Integer.class)).as("two independent server transactions reached a real row lock")
            .hasSize(2));
      } finally { blocker.rollback(); }
      return List.of(left.get(15, TimeUnit.SECONDS), right.get(15, TimeUnit.SECONDS));
    } finally {
      executor.shutdownNow();
      assertThat(executor.awaitTermination(20, TimeUnit.SECONDS)).isTrue();
    }
  }

  String runtimeLockSql() {
    return "select id from interview_dynamic_runtime where session_id = "
        + session.getId() + " for update";
  }

  Object outcome(Callable<?> action) throws Exception {
    try { return action.call(); } catch (BusinessException rejected) { return rejected; }
  }

  UUID freeze() {
    var snapshot = queries.get(owner.getId(), session.getSessionId());
    commands.complete(owner.getId(), session.getSessionId(), snapshot.version());
    return UUID.fromString(sessions.findById(session.getId()).orElseThrow().getEvaluationMessageId());
  }

  InterviewReportDTO report(UUID messageId) throws Exception {
    var scope = persistence.loadDynamicEvaluation(session.getSessionId(), messageId).orElseThrow().snapshot();
    var model = new DynamicInterviewEvaluationTest.ScriptedModel(evaluationOutput(scope));
    return DynamicInterviewEvaluationTest.evaluator(model).evaluate("test", scope);
  }

  EvaluationOutput evaluationOutput(DynamicInterviewEvaluationSnapshot scope) {
    var groups = new ArrayList<DynamicGroupEvaluation>();
    var details = new ArrayList<QuestionFeedback>();
    for (int i = 0; i < scope.groups().size(); i++) {
      var group = scope.groups().get(i);
      var observed = group.answers().stream()
          .filter(answer -> answer.submissionIntent() != SubmissionIntent.SKIP).toList();
      Integer score = observed.isEmpty() ? null : i == 0 ? 90 : i == 1 ? 30 : 60;
      groups.add(new DynamicGroupEvaluation(group.mainQuestion().slotId(), score,
          score == null ? "未评估" : "正式可观察目标证据", observed.stream()
              .map(answer -> answer.questionIndex()).toList(), score == null ? List.of()
                  : List.of(group.mainQuestion().assessmentTargets().getFirst().id())));
      group.questions().forEach(question -> details.add(new QuestionFeedback(question.questionIndex(),
          observed.stream().anyMatch(answer -> answer.questionIndex() == question.questionIndex())
              ? 10 : null, "逐题反馈")));
    }
    return new EvaluationOutput(groups, details, "按组等权评价", List.of(), List.of(), List.of());
  }

  public static class SqlCapture implements StatementInspector {
    static final ConcurrentLinkedQueue<String> LOCKS = new ConcurrentLinkedQueue<>();
    @Override public String inspect(String sql) {
      if (sql.contains("for no key update") || sql.contains("for update")) {
        LOCKS.add(sql);
      }
      return sql;
    }
  }
}
