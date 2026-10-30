package interview.guide.modules.interview.dynamic;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;

import interview.guide.infrastructure.redis.RedisService;
import interview.guide.modules.auth.model.UserEntity;
import interview.guide.modules.auth.model.UserRole;
import interview.guide.modules.auth.model.UserStatus;
import interview.guide.modules.interview.dynamic.dto.DynamicSubmitRequest;
import interview.guide.modules.interview.dynamic.dto.DynamicSubmissionReceipt;
import interview.guide.modules.interview.dynamic.model.AssessmentTarget;
import interview.guide.modules.interview.dynamic.model.DynamicInterviewRuntimeEntity;
import interview.guide.modules.interview.dynamic.model.DynamicMainQuestion;
import interview.guide.modules.interview.dynamic.model.FollowUpModelDecision;
import interview.guide.modules.interview.dynamic.model.InterviewExecutionMode;
import interview.guide.modules.interview.dynamic.model.SubmissionIntent;
import interview.guide.modules.interview.dynamic.repository.DynamicEvaluationOutboxRepository;
import interview.guide.modules.interview.dynamic.repository.DynamicInterviewRuntimeRepository;
import interview.guide.modules.interview.dynamic.repository.FollowUpDecisionRepository;
import interview.guide.modules.interview.dynamic.messaging.FollowUpDecisionProducer;
import interview.guide.modules.interview.dynamic.service.DynamicAdvancePolicy;
import interview.guide.modules.interview.dynamic.service.DynamicDecisionDispatchService;
import interview.guide.modules.interview.dynamic.service.DynamicDecisionRecoveryService;
import interview.guide.modules.interview.dynamic.service.DynamicDecisionStoreService;
import interview.guide.modules.interview.dynamic.service.DynamicDecisionWorker;
import interview.guide.modules.interview.dynamic.service.DynamicEvaluationDispatchService;
import interview.guide.modules.interview.dynamic.service.DynamicEvaluationOutboxStoreService;
import interview.guide.modules.interview.dynamic.service.DynamicInterviewCommandService;
import interview.guide.modules.interview.dynamic.service.DynamicInterviewProperties;
import interview.guide.modules.interview.dynamic.service.DynamicInterviewQueryService;
import interview.guide.modules.interview.dynamic.service.DynamicInterviewTransitionService;
import interview.guide.modules.interview.dynamic.service.FollowUpDecisionService;
import interview.guide.modules.interview.messaging.InterviewEvaluationTaskPublisher;
import interview.guide.modules.interview.model.InterviewQuestionDTO;
import interview.guide.modules.interview.model.InterviewSessionEntity;
import interview.guide.modules.interview.repository.InterviewAnswerRepository;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
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
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(classes = DynamicDecisionTestSupport.JpaApplication.class, properties = {
    "spring.datasource.url=jdbc:h2:mem:dynamic-delivery;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    "spring.datasource.driver-class-name=org.h2.Driver",
    "spring.datasource.username=sa", "spring.datasource.password=",
    "spring.jpa.hibernate.ddl-auto=create-drop", "spring.jpa.show-sql=false",
    "app.interview.dynamic.enabled=false"
})
abstract class DynamicDecisionTestSupport {
  static final Instant NOW = Instant.parse("2026-10-30T08:00:00Z");

  @Configuration(proxyBeanMethods = false)
  @EntityScan("interview.guide.modules")
  @EnableJpaRepositories(basePackageClasses = {
      DynamicInterviewRuntimeRepository.class, InterviewAnswerRepository.class})
  @ImportAutoConfiguration({DataSourceAutoConfiguration.class, HibernateJpaAutoConfiguration.class,
      TransactionAutoConfiguration.class})
  @Import({DynamicAdvancePolicy.class, DynamicInterviewCommandService.class,
      DynamicInterviewQueryService.class, DynamicInterviewTransitionService.class,
      DynamicDecisionStoreService.class, DynamicEvaluationOutboxStoreService.class,
      DynamicDecisionWorker.class, DynamicDecisionDispatchService.class,
      DynamicDecisionRecoveryService.class, DynamicEvaluationDispatchService.class,
      FollowUpDecisionProducer.class})
  static class JpaApplication {
    @Bean ObjectMapper mapper() { return JsonMapper.builder().build(); }
    @Bean MutableClock clock() { return new MutableClock(); }
    @Bean DynamicInterviewProperties properties() { return new DynamicInterviewProperties(); }
    @Bean RedisService redis() { return mock(RedisService.class); }
    @Bean FollowUpDecisionService model() { return mock(FollowUpDecisionService.class); }
    @Bean InterviewEvaluationTaskPublisher publisher() {
      return mock(InterviewEvaluationTaskPublisher.class);
    }
  }

  @Autowired EntityManager em;
  @Autowired TransactionTemplate tx;
  @Autowired ObjectMapper mapper;
  @Autowired MutableClock clock;
  @Autowired DynamicInterviewProperties properties;
  @Autowired DynamicInterviewCommandService commands;
  @Autowired DynamicInterviewQueryService queries;
  @Autowired DynamicInterviewTransitionService transitions;
  @Autowired DynamicInterviewRuntimeRepository runtimes;
  @Autowired FollowUpDecisionRepository decisions;
  @Autowired DynamicEvaluationOutboxRepository outboxes;
  @Autowired InterviewAnswerRepository answers;
  @Autowired DynamicDecisionStoreService store;
  @Autowired DynamicDecisionWorker worker;
  @Autowired DynamicDecisionDispatchService dispatch;
  @Autowired DynamicDecisionRecoveryService recovery;
  @Autowired DynamicEvaluationDispatchService evaluationDispatch;
  @Autowired DynamicEvaluationOutboxStoreService evaluationStore;
  @Autowired RedisService redis;
  @Autowired FollowUpDecisionService model;
  @Autowired InterviewEvaluationTaskPublisher publisher;
  UserEntity owner;
  InterviewSessionEntity session;

  @BeforeEach
  void setup() {
    reset(redis, model, publisher);
    clock.now = NOW;
    properties.setDeliveryBatchSize(100);
    properties.setWorkLease(Duration.ofSeconds(30));
    tx.executeWithoutResult(status -> {
      em.createQuery("delete from DynamicEvaluationOutboxEntity").executeUpdate();
      em.createQuery("delete from FollowUpDecisionEntity").executeUpdate();
      em.createQuery("delete from DynamicInterviewRuntimeEntity").executeUpdate();
      em.createQuery("delete from InterviewAnswerEntity").executeUpdate();
      em.createQuery("delete from InterviewSessionEntity").executeUpdate();
      em.createQuery("delete from ResumeEntity").executeUpdate();
      em.createQuery("delete from UserEntity").executeUpdate();
    });
    String key = UUID.randomUUID().toString();
    owner = UserEntity.builder().username(key).normalizedUsername(key).displayName(key)
        .passwordHash("test-only").role(UserRole.USER).status(UserStatus.ACTIVE)
        .createdAt(NOW).updatedAt(NOW).build();
    tx.executeWithoutResult(status -> em.persist(owner));
    session = createSession(3);
  }

  DynamicSubmissionReceipt submitAnswer() {
    var current = queries.get(owner.getId(), session.getSessionId());
    return commands.submit(owner.getId(), session.getSessionId(), new DynamicSubmitRequest(
        UUID.randomUUID(), current.currentQuestion().questionIndex(), current.version(),
        SubmissionIntent.ANSWER, "正式答案不可丢失"));
  }

  DynamicInterviewRuntimeEntity runtime() {
    return runtimes.findBySession_SessionId(session.getSessionId()).orElseThrow();
  }

  FollowUpModelDecision followUp() {
    return new FollowUpModelDecision(FollowUpModelDecision.Action.FOLLOW_UP,
        FollowUpModelDecision.Reason.IMPORTANT_GAP, "缺少边界", List.of("target-a"),
        List.of("target-b"), "事务代理失效时会怎样？");
  }

  InterviewSessionEntity createSession(int count) {
    var pool = IntStream.range(0, count).mapToObj(i -> new DynamicMainQuestion("slot-" + i,
        "主问题" + i, "JAVA", "Java", "事务", List.of(new AssessmentTarget("target-a", "代理"),
        new AssessmentTarget("target-b", "边界")), "mid", List.of())).toList();
    var created = new InterviewSessionEntity();
    created.setOwner(owner);
    created.setSessionId(UUID.randomUUID().toString());
    created.setExecutionMode(InterviewExecutionMode.DYNAMIC_FOLLOW_UP);
    created.setLlmProvider("trusted-provider");
    created.setQuestionsJson(mapper.writeValueAsString(List.of(InterviewQuestionDTO.planned(0,
        "主问题0", "JAVA", "Java", "事务", false, null, "slot-0"))));
    created.setTotalQuestions(1);
    tx.executeWithoutResult(status -> em.persist(created));
    runtimes.saveAndFlush(new DynamicInterviewRuntimeEntity(created, mapper.writeValueAsString(pool),
        mapper.writeValueAsString(properties.snapshot(count)), count, "slot-0"));
    return created;
  }

  static class MutableClock extends Clock {
    volatile Instant now = NOW;
    @Override public ZoneId getZone() { return ZoneOffset.UTC; }
    @Override public Clock withZone(ZoneId zone) { return this; }
    @Override public Instant instant() { return now; }
  }
}
