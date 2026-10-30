package interview.guide.modules.memory.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import interview.guide.common.model.AsyncTaskStatus;
import interview.guide.modules.interview.dynamic.service.DynamicAbilityEvidenceSourceService;
import interview.guide.modules.auth.model.UserEntity;
import interview.guide.modules.auth.model.UserRole;
import interview.guide.modules.auth.model.UserStatus;
import interview.guide.modules.interview.model.InterviewAnswerEntity;
import interview.guide.modules.interview.model.InterviewReportDTO;
import interview.guide.modules.interview.model.InterviewSessionEntity;
import interview.guide.modules.interview.model.InterviewQuestionDTO;
import interview.guide.modules.interview.planning.model.InterviewQuestionPlanEntity;
import interview.guide.modules.interview.planning.model.PlanningMode;
import interview.guide.modules.interview.planning.model.QuestionPlan;
import interview.guide.modules.interview.planning.model.QuestionPurpose;
import interview.guide.modules.interview.planning.model.QuestionSlot;
import interview.guide.modules.interview.planning.model.QuestionSourceMode;
import interview.guide.modules.interview.planning.repository.InterviewQuestionPlanRepository;
import interview.guide.modules.interview.repository.InterviewAnswerRepository;
import interview.guide.modules.interview.repository.InterviewSessionRepository;
import interview.guide.modules.interview.dynamic.repository.DynamicInterviewRuntimeRepository;
import interview.guide.modules.interview.dynamic.repository.DynamicEvaluationOutboxRepository;
import interview.guide.modules.interview.service.InterviewPersistenceService;
import interview.guide.modules.auth.repository.UserRepository;
import interview.guide.modules.auth.security.CurrentUserProvider;
import interview.guide.modules.memory.service.AbilityMemoryDeletionService;
import interview.guide.modules.resume.repository.ResumeRepository;
import interview.guide.modules.memory.model.AbilityProfileProposalEntity;
import interview.guide.modules.memory.repository.AbilityProfileProposalRepository;
import interview.guide.modules.memory.repository.UserAbilityEvidenceRepository;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.transaction.autoconfigure.TransactionAutoConfiguration;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Bean;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.transaction.support.TransactionTemplate;
import static org.mockito.Mockito.mock;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(classes = AbilityEvidenceDerivationServiceTest.TestApplication.class, properties = {
    "spring.datasource.url=jdbc:h2:mem:ability-derivation;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    "spring.datasource.driver-class-name=org.h2.Driver",
    "spring.datasource.username=sa",
    "spring.datasource.password=",
    "spring.jpa.hibernate.ddl-auto=create-drop"
})
@DisplayName("文字面试能力证据派生")
class AbilityEvidenceDerivationServiceTest {
  @Configuration(proxyBeanMethods = false)
  @EntityScan("interview.guide.modules")
  @EnableJpaRepositories("interview.guide.modules")
  @Import({AbilityEvidenceDerivationService.class, AbilityScorePolicy.class,
      DynamicAbilityEvidenceSourceService.class})
  @ImportAutoConfiguration({DataSourceAutoConfiguration.class, HibernateJpaAutoConfiguration.class,
      TransactionAutoConfiguration.class})
  static class TestApplication {
    @Bean
    ObjectMapper objectMapper() {
      return new ObjectMapper();
    }

    @Bean
    InterviewPersistenceService interviewPersistence(InterviewSessionRepository sessions,
        InterviewAnswerRepository answers, ResumeRepository resumes, ObjectMapper mapper,
        UserRepository users,
        DynamicInterviewRuntimeRepository runtimes, DynamicEvaluationOutboxRepository outboxes) {
      return new InterviewPersistenceService(sessions, answers, resumes, mapper,
          mock(CurrentUserProvider.class), users, mock(AbilityMemoryDeletionService.class), runtimes, outboxes);
    }
  }

  @Autowired EntityManager entityManager;
  @Autowired TransactionTemplate transactions;
  @Autowired AbilityEvidenceDerivationService service;
  @Autowired AbilityProfileProposalRepository proposals;
  @Autowired UserAbilityEvidenceRepository evidence;
  @Autowired InterviewPersistenceService reports;
  @Autowired InterviewSessionRepository sessionRepository;
  @Autowired InterviewQuestionPlanRepository planRepository;
  @Autowired ObjectMapper mapper;

  @Test
  @DisplayName("只从已评估且已回答的稳定分类生成同场候选与关联证据，重投递不重复")
  void derivesEvaluatedAnswersIdempotently() {
    String sessionId = UUID.randomUUID().toString();
    UUID ownerId = transactions.execute(status -> {
      UserEntity owner = owner(UserStatus.ACTIVE);
      InterviewSessionEntity session = session(owner, sessionId, true);
      answer(session, 0, "JAVA", "集合", "答复", 80);
      answer(session, 1, "JAVA", "并发", "答复", 60);
      answer(session, 2, "OTHER", "其它", "答复", 90);
      answer(session, 3, "MYSQL", "数据库", " ", 70);
      return owner.getId();
    });

    assertThat(service.derive(sessionId, UUID.randomUUID())).isEqualTo(DerivationResult.CREATED);
    assertThat(service.derive(sessionId, UUID.randomUUID())).isEqualTo(DerivationResult.ALREADY_COMPLETED);

    List<AbilityProfileProposalEntity> items = proposals.findByOwnerIdAndInterviewSessionId(
        ownerId, sessionIdToId(sessionId));
    assertThat(items).hasSize(1);
    assertThat(items.getFirst().getCategoryKey()).isEqualTo("JAVA");
    assertThat(items.getFirst().getProposedScore()).isEqualTo(70);
    assertThat(evidence.findByOwnerIdAndInterviewSessionId(ownerId, sessionIdToId(sessionId)))
        .hasSize(2).allSatisfy(item -> {
          assertThat(item.getProposal().getId()).isEqualTo(items.getFirst().getId());
          assertThat(item.getCategoryKey()).isEqualTo("JAVA");
          assertThat(item.getDifficulty()).isEqualTo("mid");
        });
  }

  @Test
  @DisplayName("证据难度取回答所属的有效计划槽位，缺槽位时退回会话难度")
  void usesEffectivePlanSlotDifficulty() {
    String sessionId = UUID.randomUUID().toString();
    UUID ownerId = transactions.execute(status -> {
      var owner = owner(UserStatus.ACTIVE);
      var session = session(owner, sessionId, true);
      session.setQuestionsJson(mapper.writeValueAsString(List.of(
          InterviewQuestionDTO.planned(0, "弱项", "JAVA", "Java", null, false, null, "weak"),
          InterviewQuestionDTO.planned(1, "强项", "JAVA", "Java", null, false, null, "strong"),
          InterviewQuestionDTO.create(2, "旧题", "JAVA", "Java"))));
      var plan = new QuestionPlan(1, PlanningMode.ADAPTIVE, List.of(
          new QuestionSlot("weak", "JAVA", QuestionPurpose.WEAKNESS_REINFORCEMENT,
              "junior", QuestionSourceMode.DIRECTION, List.of(), List.of(), null),
          new QuestionSlot("strong", "JAVA", QuestionPurpose.STRENGTH_CALIBRATION,
              "senior", QuestionSourceMode.DIRECTION, List.of(), List.of(), null)), null);
      var saved = new InterviewQuestionPlanEntity();
      saved.setOwner(owner);
      saved.setSession(session);
      saved.setPlanVersion(1);
      saved.setPlanningMode(PlanningMode.ADAPTIVE);
      saved.setPlanJson(mapper.writeValueAsString(plan));
      planRepository.saveAndFlush(saved);
      answer(session, 0, "JAVA", "Java", "回答", 60);
      answer(session, 1, "JAVA", "Java", "回答", 90);
      answer(session, 2, "JAVA", "Java", "回答", 70);
      return owner.getId();
    });

    assertThat(service.derive(sessionId, UUID.randomUUID())).isEqualTo(DerivationResult.CREATED);
    var difficulties = transactions.execute(status -> evidence
        .findByOwnerIdAndInterviewSessionId(ownerId, sessionIdToId(sessionId)).stream()
        .map(item -> item.getInterviewAnswer().getQuestionIndex() + ":" + item.getDifficulty())
        .toList());
    assertThat(difficulties).containsExactlyInAnyOrder(
            "0:junior", "1:senior", "2:mid");
  }

  @Test
  @DisplayName("未完成评估与停用用户均不生成证据")
  void skipsIncompleteAndDisabled() {
    long before = proposals.count();
    String incomplete = UUID.randomUUID().toString();
    String disabled = UUID.randomUUID().toString();
    transactions.executeWithoutResult(status -> {
      InterviewSessionEntity first = session(owner(UserStatus.ACTIVE), incomplete, false);
      answer(first, 0, "JAVA", "集合", "答复", 70);
      InterviewSessionEntity second = session(owner(UserStatus.DISABLED), disabled, true);
      answer(second, 0, "JAVA", "集合", "答复", 70);
    });

    assertThat(service.derive(incomplete, UUID.randomUUID())).isEqualTo(DerivationResult.DISCARDED);
    assertThat(service.derive(disabled, UUID.randomUUID())).isEqualTo(DerivationResult.DISCARDED);
    assertThat(service.derive(UUID.randomUUID().toString(), UUID.randomUUID()))
        .isEqualTo(DerivationResult.DISCARDED);
    assertThat(proposals.count()).isEqualTo(before);
  }

  @Test
  @DisplayName("并发派生同一会话只创建一个候选和一条证据")
  void concurrentDeliveryIsIdempotent() throws Exception {
    String sessionId = UUID.randomUUID().toString();
    UUID ownerId = transactions.execute(status -> {
      UserEntity owner = owner(UserStatus.ACTIVE);
      answer(session(owner, sessionId, true), 0, "JAVA", "集合", "答复", 70);
      return owner.getId();
    });
    CountDownLatch start = new CountDownLatch(1);
    AtomicReference<Throwable> failure = new AtomicReference<>();
    Runnable work = () -> {
      try {
        start.await();
        service.derive(sessionId, UUID.randomUUID());
      } catch (Throwable problem) {
        failure.compareAndSet(null, problem);
      }
    };
    Thread first = Thread.ofVirtual().start(work);
    Thread second = Thread.ofVirtual().start(work);
    start.countDown();
    first.join();
    second.join();

    assertThat(failure.get()).isNull();
    assertThat(proposals.findByOwnerIdAndInterviewSessionId(ownerId, sessionIdToId(sessionId)))
        .hasSize(1);
    assertThat(evidence.findByOwnerIdAndInterviewSessionId(ownerId, sessionIdToId(sessionId)))
        .hasSize(1);
  }

  @Test
  @DisplayName("并发不同报告只有一个完成，派生证据与最终报告一致")
  void distinctConcurrentReportsHaveOneWinner() throws Exception {
    String sessionId = UUID.randomUUID().toString();
    UUID ownerId = transactions.execute(status -> {
      var owner = owner(UserStatus.ACTIVE);
      answer(session(owner, sessionId, false), 0, "JAVA", "Java", "回答", 0);
      return owner.getId();
    });
    CountDownLatch start = new CountDownLatch(1);
    AtomicReference<Throwable> failure = new AtomicReference<>();
    AtomicInteger created = new AtomicInteger();
    Runnable first = () -> completeConcurrently(sessionId, 40, "first", start, created, failure);
    Runnable second = () -> completeConcurrently(sessionId, 90, "second", start, created, failure);
    Thread a = Thread.ofVirtual().start(first);
    Thread b = Thread.ofVirtual().start(second);
    start.countDown();
    a.join();
    b.join();
    assertThat(failure.get()).isNull();
    assertThat(created.get()).isOne();
    var winner = sessionRepository.findBySessionId(sessionId).orElseThrow();
    assertThat(winner.getOverallScore()).isIn(40, 90);
    assertThat(service.derive(sessionId, UUID.randomUUID())).isEqualTo(DerivationResult.CREATED);
    assertThat(evidence.findByOwnerIdAndInterviewSessionId(ownerId, winner.getId()))
        .singleElement().satisfies(item ->
            assertThat(item.getQuestionScore()).isEqualTo(winner.getOverallScore()));
  }

  @Test
  @DisplayName("派生完成后迟到的不同报告不能改写报告、证据或派生状态")
  void lateReportCannotOverwriteDerivedEvidence() {
    String sessionId = UUID.randomUUID().toString();
    UUID ownerId = transactions.execute(status -> {
      var owner = owner(UserStatus.ACTIVE);
      answer(session(owner, sessionId, false), 0, "JAVA", "Java", "回答", 0);
      return owner.getId();
    });
    assertThat(reports.saveReport(sessionId, report(sessionId, 40), "first"))
        .isEqualTo(InterviewPersistenceService.CompletionResult.CREATED);
    assertThat(service.derive(sessionId, UUID.randomUUID())).isEqualTo(DerivationResult.CREATED);
    assertThat(reports.saveReport(sessionId, report(sessionId, 90), "second"))
        .isEqualTo(InterviewPersistenceService.CompletionResult.ALREADY_COMPLETED);
    var winner = sessionRepository.findBySessionId(sessionId).orElseThrow();
    assertThat(winner.getOverallScore()).isEqualTo(40);
    assertThat(winner.getMemoryDerivationStatus()).isEqualTo(AsyncTaskStatus.COMPLETED);
    assertThat(evidence.findByOwnerIdAndInterviewSessionId(ownerId, winner.getId()))
        .singleElement().satisfies(item -> assertThat(item.getQuestionScore()).isEqualTo(40));
  }

  private void completeConcurrently(String sessionId, int score, String messageId,
      CountDownLatch start, AtomicInteger created, AtomicReference<Throwable> failure) {
    try {
      start.await();
      if (reports.saveReport(sessionId, report(sessionId, score), messageId)
          == InterviewPersistenceService.CompletionResult.CREATED) {
        created.incrementAndGet();
      }
    } catch (Throwable problem) {
      failure.compareAndSet(null, problem);
    }
  }

  private InterviewReportDTO report(String sessionId, int score) {
    return new InterviewReportDTO(sessionId, 1, score, List.of(), List.of(
        new InterviewReportDTO.QuestionEvaluation(0, "问题 0", "JAVA", "Java", "回答",
            score, "反馈")), "反馈", List.of(), List.of(), List.of());
  }

  @Test
  @DisplayName("派生失败只记录派生错误，已完成评估保持完成")
  void failureDoesNotChangeEvaluation() {
    String sessionId = UUID.randomUUID().toString();
    transactions.executeWithoutResult(status -> {
      InterviewSessionEntity value = session(owner(UserStatus.ACTIVE), sessionId, true);
      value.setQuestionsJson("broken-json");
      answer(value, 0, "JAVA", "集合", "答复", 70);
    });
    UUID messageId = UUID.randomUUID();

    assertThatThrownBy(() -> service.derive(sessionId, messageId))
        .isInstanceOf(RuntimeException.class);
    service.recordFailure(sessionId, messageId, "invalid question data");

    transactions.executeWithoutResult(status -> {
      InterviewSessionEntity persisted = entityManager.createQuery(
          "select s from InterviewSessionEntity s where s.sessionId = :id",
          InterviewSessionEntity.class).setParameter("id", sessionId).getSingleResult();
      assertThat(persisted.getEvaluateStatus()).isEqualTo(AsyncTaskStatus.COMPLETED);
      assertThat(persisted.getStatus()).isEqualTo(InterviewSessionEntity.SessionStatus.EVALUATED);
      assertThat(persisted.getMemoryDerivationStatus()).isEqualTo(AsyncTaskStatus.FAILED);
      assertThat(persisted.getMemoryDerivationMessageId()).isEqualTo(messageId.toString());
      assertThat(persisted.getMemoryDerivationError()).isEqualTo("invalid question data");
    });
  }

  private Long sessionIdToId(String sessionId) {
    return transactions.execute(status -> entityManager.createQuery(
        "select s.id from InterviewSessionEntity s where s.sessionId = :id", Long.class)
        .setParameter("id", sessionId).getSingleResult());
  }

  private UserEntity owner(UserStatus state) {
    UserEntity value = UserEntity.builder().username(UUID.randomUUID().toString())
        .normalizedUsername(UUID.randomUUID().toString()).passwordHash("hash")
        .displayName("Tester").role(UserRole.USER).status(state)
        .createdAt(Instant.now()).updatedAt(Instant.now()).build();
    entityManager.persist(value);
    return value;
  }

  private InterviewSessionEntity session(UserEntity owner, String id, boolean evaluated) {
    InterviewSessionEntity value = new InterviewSessionEntity();
    value.setOwner(owner);
    value.setSessionId(id);
    value.setSkillId("java-backend");
    value.setDifficulty("mid");
    value.setQuestionsJson("[]");
    value.setStatus(evaluated ? InterviewSessionEntity.SessionStatus.EVALUATED
        : InterviewSessionEntity.SessionStatus.COMPLETED);
    value.setEvaluateStatus(evaluated ? AsyncTaskStatus.COMPLETED : AsyncTaskStatus.PROCESSING);
    entityManager.persist(value);
    return value;
  }

  private void answer(InterviewSessionEntity session, int index, String key, String label,
      String response, int score) {
    InterviewAnswerEntity value = new InterviewAnswerEntity();
    value.setSession(session);
    value.setQuestionIndex(index);
    value.setQuestion("问题 " + index);
    value.setCategoryKey(key);
    value.setCategoryLabel(label);
    value.setUserAnswer(response);
    value.setScore(score);
    value.setFeedback("反馈 " + index);
    entityManager.persist(value);
  }
}
