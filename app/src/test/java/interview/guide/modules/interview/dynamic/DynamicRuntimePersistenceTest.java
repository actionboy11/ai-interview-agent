package interview.guide.modules.interview.dynamic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import interview.guide.modules.auth.model.UserEntity;
import interview.guide.modules.auth.model.UserRole;
import interview.guide.modules.auth.model.UserStatus;
import interview.guide.modules.interview.dynamic.model.DynamicEvaluationOutboxEntity;
import interview.guide.modules.interview.dynamic.model.DynamicInterviewRuntimeEntity;
import interview.guide.modules.interview.dynamic.model.DynamicPhase;
import interview.guide.modules.interview.dynamic.model.FollowUpDecisionEntity;
import interview.guide.modules.interview.dynamic.model.SubmissionIntent;
import interview.guide.modules.interview.dynamic.repository.DynamicEvaluationOutboxRepository;
import interview.guide.modules.interview.dynamic.repository.DynamicInterviewRuntimeRepository;
import interview.guide.modules.interview.dynamic.repository.FollowUpDecisionRepository;
import interview.guide.modules.interview.model.InterviewAnswerEntity;
import interview.guide.modules.interview.model.InterviewSessionEntity;
import interview.guide.modules.interview.planning.model.InterviewQuestionPlanEntity;
import interview.guide.modules.interview.planning.model.PlanningMode;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.JdbcTemplateAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.transaction.autoconfigure.TransactionAutoConfiguration;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(classes = DynamicRuntimePersistenceTest.JpaApplication.class, properties = {
    "spring.datasource.url=jdbc:h2:mem:dynamic-runtime;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    "spring.datasource.driver-class-name=org.h2.Driver",
    "spring.datasource.username=sa", "spring.datasource.password=",
    "spring.jpa.hibernate.ddl-auto=create-drop", "spring.jpa.show-sql=false"
})
@DisplayName("动态面试持久化兼容性与数据库不变量")
class DynamicRuntimePersistenceTest {
  static final Instant NOW = Instant.parse("2026-10-29T08:00:00Z");
  static final String MAIN_POOL = "[{\"slotId\":\"slot-1\",\"question\":\"解释事务\","
      + "\"assessmentTargets\":[{\"id\":\"a\",\"description\":\"边界\"},"
      + "{\"id\":\"b\",\"description\":\"失效场景\"}]}]";
  static final String POLICY = "{\"schemaVersion\":1,\"maxFollowUpsPerGroup\":2,"
      + "\"totalFollowUpBudget\":1,\"decisionTimeoutSeconds\":30}";

  @Configuration(proxyBeanMethods = false)
  @EntityScan("interview.guide.modules")
  @EnableJpaRepositories(basePackageClasses = DynamicInterviewRuntimeRepository.class)
  @ImportAutoConfiguration({DataSourceAutoConfiguration.class, HibernateJpaAutoConfiguration.class,
      JdbcTemplateAutoConfiguration.class, TransactionAutoConfiguration.class})
  static class JpaApplication {}

  @Autowired EntityManager entityManager;
  @Autowired TransactionTemplate transactions;
  @Autowired JdbcTemplate jdbc;
  @Autowired DynamicInterviewRuntimeRepository runtimes;
  @Autowired FollowUpDecisionRepository decisions;
  @Autowired DynamicEvaluationOutboxRepository outboxes;
  private UserEntity owner;
  private InterviewSessionEntity session;

  @BeforeEach
  void setUp() {
    owner = user();
    session = session(owner);
  }

  @Test
  @DisplayName("旧 V2 规划会话缺省仍向消费者声明固定执行模式")
  void legacyV2HasFixedExecutionMode() {
    var plan = new InterviewQuestionPlanEntity();
    plan.setSession(session);
    plan.setOwner(owner);
    plan.setPlanVersion(2);
    plan.setPlanningMode(PlanningMode.ADAPTIVE);
    plan.setPlanJson("{\"planVersion\":2}");
    transactions.executeWithoutResult(tx -> entityManager.persist(plan));
    var mode = JsonMapper.builder().build().valueToTree(session).path("executionMode").asString();
    assertThat(mode).isEqualTo("LEGACY_FIXED");
    assertThat(jdbc.queryForObject("select execution_mode from interview_sessions where id = ?",
        String.class, session.getId())).isEqualTo("LEGACY_FIXED");
    assertThat(jdbc.queryForObject("select count(*) from interview_dynamic_runtime where session_id = ?",
        Integer.class, session.getId())).isZero();
  }

  @Test
  @DisplayName("运行快照、结束原因与政策重新读取，锁定更新递增版本")
  void runtimeRoundTripAndVersion() {
    var saved = runtimes.saveAndFlush(runtime(session));
    assertThat(saved.getVersion()).isZero();
    transactions.executeWithoutResult(tx -> {
      var locked = runtimes.findBySessionIdForUpdate(session.getSessionId()).orElseThrow();
      locked.setPhase(DynamicPhase.COMPLETED);
      locked.setCompletedMainCount(1);
      locked.setGroupEndReasonsJson("{\"schemaVersion\":1,\"groups\":{\"slot-1\":\"SKIP\"}}");
    });
    var loaded = runtimes.findByOwner_IdAndSession_SessionId(owner.getId(), session.getSessionId())
        .orElseThrow();
    assertThat(loaded.getVersion()).isEqualTo(1);
    assertThat(loaded.getMainQuestionsJson()).isEqualTo(MAIN_POOL);
    assertThat(loaded.getPolicyJson()).isEqualTo(POLICY);
    assertThat(loaded.getGroupEndReasonsJson()).contains("\"slot-1\":\"SKIP\"");
    assertThat(loaded.getPhase()).isEqualTo(DynamicPhase.COMPLETED);
    assertThat(loaded.getMaxFollowUpsPerGroup()).isEqualTo(2);
    assertThat(loaded.getTotalFollowUpBudget()).isEqualTo(1);
    assertThat(loaded.getDecisionTimeoutSeconds()).isEqualTo(30);
  }

  @Test
  @DisplayName("相同会话只有一份运行状态且拒绝陈旧版本覆盖")
  void uniqueRuntimeAndOptimisticLock() {
    var saved = runtimes.saveAndFlush(runtime(session));
    var stale = runtimes.findById(saved.getId()).orElseThrow();
    saved.setNoticeCode("SYSTEM_UNAVAILABLE");
    runtimes.saveAndFlush(saved);
    stale.setNoticeCode("STALE");
    assertThatThrownBy(() -> runtimes.saveAndFlush(stale))
        .isInstanceOf(ObjectOptimisticLockingFailureException.class);
    assertThatThrownBy(() -> runtimes.saveAndFlush(runtime(session)))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  @DisplayName("正式源答案只能创建一次决策且同用户请求不能复用于另一会话")
  void duplicateSourceAnswerAndRequestAreRejected() {
    var runtime = runtimes.saveAndFlush(runtime(session));
    var requestId = UUID.randomUUID();
    var answer = answer(session, requestId);
    var decision = decisions.saveAndFlush(decision(runtime, answer));
    assertThatThrownBy(() -> decisions.saveAndFlush(decision(runtime, answer)))
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThat(decisions.findByOwner_IdAndRequestId(owner.getId(), requestId))
        .hasValueSatisfying(found -> {
          assertThat(found.getId()).isEqualTo(decision.getId());
          assertThat(found.getRequestFingerprint()).isEqualTo("a".repeat(64));
          assertThat(found.getDeadline()).isEqualTo(NOW.plusSeconds(30));
        });
    assertThatThrownBy(() -> answer(session(owner), requestId))
        .isInstanceOf(ConstraintViolationException.class)
        .hasMessageContaining("UK_DYNAMIC_ANSWER_REQUEST");
    assertThat(answer(session(user()), requestId).getId()).isNotNull();
  }

  @Test
  @DisplayName("直接不会提交保留请求指纹且旧答案不强制填写意图")
  void explicitIntentAndLegacyAnswerMetadata() {
    var answer = answer(session, UUID.randomUUID());
    transactions.executeWithoutResult(tx -> {
      var managed = entityManager.find(InterviewAnswerEntity.class, answer.getId());
      managed.setSubmissionIntent(SubmissionIntent.DONT_KNOW);
      managed.setUserAnswer("");
    });
    var loaded = transactions.execute(tx -> entityManager.find(InterviewAnswerEntity.class,
        answer.getId()));
    assertThat(loaded.getSubmissionIntent()).isEqualTo(SubmissionIntent.DONT_KNOW);
    assertThat(loaded.getRequestFingerprint()).isEqualTo("a".repeat(64));
    var legacy = new InterviewAnswerEntity();
    legacy.setSession(session);
    legacy.setQuestionIndex(1);
    transactions.executeWithoutResult(tx -> entityManager.persist(legacy));
    assertThat(legacy.getSubmissionIntent()).isNull();
    assertThat(legacy.getRequestId()).isNull();
  }

  @Test
  @DisplayName("跨用户运行状态与跨会话源答案被拒绝，删除会话清理动态记录")
  void crossOwnerRuntimeAndDeleteCascade() {
    var wrongOwner = runtime(session);
    wrongOwner.setOwner(user());
    assertThatThrownBy(() -> runtimes.saveAndFlush(wrongOwner))
        .isInstanceOf(DataIntegrityViolationException.class);
    var runtime = runtimes.saveAndFlush(runtime(session));
    var answer = answer(session, UUID.randomUUID());
    decisions.saveAndFlush(decision(runtime, answer));
    outboxes.saveAndFlush(outbox(runtime, 1));
    var wrongSource = decision(runtime, answer(session(owner), UUID.randomUUID()));
    assertThatThrownBy(() -> decisions.saveAndFlush(wrongSource))
        .isInstanceOf(DataIntegrityViolationException.class);
    transactions.executeWithoutResult(tx ->
        entityManager.remove(entityManager.find(InterviewSessionEntity.class, session.getId())));
    assertThat(runtimes.findBySession_SessionId(session.getSessionId())).isEmpty();
    assertThat(jdbc.queryForObject(
        "select count(*) from interview_follow_up_decisions where session_id = ?",
        Integer.class, session.getId())).isZero();
    assertThat(jdbc.queryForObject(
        "select count(*) from interview_dynamic_evaluation_outbox where session_id = ?",
        Integer.class, session.getId())).isZero();
  }

  @Test
  @DisplayName("每个冻结版本只持久化一份可恢复评价任务并保留稳定消息ID")
  void evaluationTaskUniquePerFrozenVersion() {
    var runtime = runtimes.saveAndFlush(runtime(session));
    var outbox = outboxes.saveAndFlush(outbox(runtime, 3));
    assertThat(outboxes.findBySession_IdAndFrozenVersion(session.getId(), 3))
        .hasValueSatisfying(found -> {
          assertThat(found.getMessageId()).isEqualTo(outbox.getMessageId());
          assertThat(found.getFrozenSnapshotJson()).isEqualTo("{\"schemaVersion\":1}");
        });
    assertThatThrownBy(() -> outboxes.saveAndFlush(outbox(runtime, 3)))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  private UserEntity user() {
    String key = UUID.randomUUID().toString();
    var user = UserEntity.builder().username(key).normalizedUsername(key).displayName(key)
        .passwordHash("test-only-hash").role(UserRole.USER).status(UserStatus.ACTIVE)
        .createdAt(NOW).updatedAt(NOW).build();
    transactions.executeWithoutResult(tx -> entityManager.persist(user));
    return user;
  }

  private InterviewSessionEntity session(UserEntity user) {
    var created = new InterviewSessionEntity();
    created.setOwner(user);
    created.setSessionId(UUID.randomUUID().toString());
    transactions.executeWithoutResult(tx -> entityManager.persist(created));
    return created;
  }

  private DynamicInterviewRuntimeEntity runtime(InterviewSessionEntity target) {
    return new DynamicInterviewRuntimeEntity(target, MAIN_POOL, POLICY, 1, "slot-1");
  }

  private InterviewAnswerEntity answer(InterviewSessionEntity target, UUID requestId) {
    var answer = new InterviewAnswerEntity();
    answer.setSession(target);
    answer.setQuestionIndex(0);
    answer.setSubmissionIntent(SubmissionIntent.ANSWER);
    answer.setSubmissionOwnerId(target.getOwner().getId());
    answer.setRequestId(requestId);
    answer.setRequestFingerprint("a".repeat(64));
    answer.setUserAnswer("事务由代理管理");
    transactions.executeWithoutResult(tx -> {
      entityManager.persist(answer);
      entityManager.flush();
    });
    return answer;
  }

  private FollowUpDecisionEntity decision(DynamicInterviewRuntimeEntity runtime,
      InterviewAnswerEntity answer) {
    return new FollowUpDecisionEntity(runtime, answer, "{\"schemaVersion\":1}", NOW,
        NOW.plusSeconds(30));
  }

  private DynamicEvaluationOutboxEntity outbox(DynamicInterviewRuntimeEntity runtime, long version) {
    return new DynamicEvaluationOutboxEntity(runtime, version, "{\"schemaVersion\":1}", NOW);
  }
}
