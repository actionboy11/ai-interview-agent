package interview.guide.modules.interview.dynamic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import interview.guide.modules.resume.model.ResumeEntity;
import interview.guide.modules.interview.model.InterviewSessionEntity;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import interview.guide.common.exception.BusinessException;
import interview.guide.modules.interview.dynamic.dto.DynamicSubmitRequest;
import interview.guide.modules.interview.dynamic.dto.DynamicSubmissionReceipt;
import interview.guide.modules.interview.dynamic.model.DynamicPhase;
import interview.guide.modules.interview.dynamic.model.SubmissionIntent;
import interview.guide.modules.interview.dynamic.model.FollowUpDecisionEntity.Status;
import interview.guide.modules.interview.dynamic.service.DynamicInterviewTransitionService.ApplicationResult;
import interview.guide.modules.interview.service.InterviewPersistenceService.CompletionResult;
import interview.guide.modules.memory.service.DerivationResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("隔离PostgreSQL多连接锁与生命周期竞争")
class DynamicInterviewPostgresConcurrencyTest extends DynamicPostgresTestSupport {
  @Test
  @DisplayName("运行态悲观锁只锁运行行，不能提前锁会话行")
  void runtimeLockDoesNotLockSessionRow() {
    SqlCapture.LOCKS.clear();
    tx.executeWithoutResult(status -> {
      runtimes.findBySessionIdForUpdate(session.getSessionId()).orElseThrow();
      // B is a physical connection separate from the transaction-bound JPA connection A.
      assertThatCode(() -> {
        try (var connection = dataSource.getConnection()) {
          connection.setAutoCommit(false);
          try (var statement = connection.createStatement()) {
            statement.executeQuery("select id from interview_sessions where id = "
                + session.getId() + " for update nowait");
          } finally { connection.rollback(); }
        }
      }).doesNotThrowAnyException();
      assertThatThrownBy(() -> {
        try (var connection = dataSource.getConnection()) {
          connection.setAutoCommit(false);
          try (var statement = connection.createStatement()) {
            statement.executeQuery("select session_id from interview_dynamic_runtime where session_id = "
                + session.getId() + " for update nowait");
          } finally { connection.rollback(); }
        }
      }).isInstanceOf(SQLException.class).satisfies(error ->
          assertThat(((SQLException) error).getSQLState()).isEqualTo("55P03"));
    });
    assertThat(SqlCapture.LOCKS).anySatisfy(sql -> {
      assertThat(sql).contains("join interview_sessions", "for no key update of");
      System.out.println("RUNTIME_LOCK_SQL=" + sql);
    });
  }

  @Test
  @DisplayName("批量删除锁也只持有运行行并按稳定ID排序")
  void batchRuntimeLockDoesNotLockSessionRow() {
    SqlCapture.LOCKS.clear();
    tx.executeWithoutResult(status -> {
      runtimes.lockForDeletion(owner.getId(), List.of(session.getId()));
      assertThatCode(() -> {
        try (var connection = dataSource.getConnection()) {
          connection.setAutoCommit(false);
          try (var statement = connection.createStatement()) {
            statement.executeQuery("select id from interview_sessions where id = "
                + session.getId() + " for update nowait");
          } finally { connection.rollback(); }
        }
      }).doesNotThrowAnyException();
    });
    assertThat(SqlCapture.LOCKS).anySatisfy(sql -> {
      assertThat(sql).doesNotContain(" join ").contains("order by", "for no key update");
      System.out.println("BATCH_LOCK_SQL=" + sql);
    });
  }

  @Test
  @DisplayName("同请求同时提交仅持久化一份答案与决策并返回同一收据")
  void duplicateSubmissionIsIdempotentAcrossConnections() throws Exception {
    var request = new DynamicSubmitRequest(UUID.randomUUID(), 0, 0, SubmissionIntent.ANSWER, "答案");
    var results = race(runtimeLockSql(),
        () -> commands.submit(owner.getId(), session.getSessionId(), request),
        () -> commands.submit(owner.getId(), session.getSessionId(), request));
    assertThat(results).allSatisfy(result -> assertThat(result).isInstanceOf(DynamicSubmissionReceipt.class));
    assertThat(((DynamicSubmissionReceipt) results.getFirst()).decisionId())
        .isEqualTo(((DynamicSubmissionReceipt) results.getLast()).decisionId());
    assertThat(answers.findBySessionIdOrderByQuestionIndex(session.getId())).hasSize(1);
    assertThat(runtime().getVersion()).isEqualTo(1);
    assertThat(runtime().getUsedFollowUps()).isZero();
  }

  @Test
  @DisplayName("不同请求同版本争用只有一个提交成功")
  void conflictingSubmissionRejectsStaleVersion() throws Exception {
    var results = race(runtimeLockSql(),
        () -> outcome(() -> submitAnswer()),
        () -> outcome(() -> commands.submit(owner.getId(), session.getSessionId(),
            new DynamicSubmitRequest(UUID.randomUUID(), 0, 0, SubmissionIntent.ANSWER, "另一答案"))));
    assertThat(results.stream().filter(DynamicSubmissionReceipt.class::isInstance)).hasSize(1);
    assertThat(results.stream().filter(BusinessException.class::isInstance)).hasSize(1);
    assertThat(answers.findBySessionIdOrderByQuestionIndex(session.getId())).hasSize(1);
  }

  @Test
  @DisplayName("同决策两个工作认领仅有一个令牌，过期租约可重认领并隔离旧令牌")
  void claimAndReclaimFenceStaleWorkAndDeliveryIndependently() throws Exception {
    var submitted = submitAnswer();
    properties.setWorkLease(Duration.ofSeconds(5));
    var results = race(runtimeLockSql(), () -> store.claimWork(submitted.decisionId(), NOW),
        () -> store.claimWork(submitted.decisionId(), NOW));
    assertThat(results.stream().filter(result -> ((Optional<?>) result).isPresent()))
        .hasSize(1);
    var oldToken = decisions.findById(submitted.decisionId()).orElseThrow().getAttemptToken();
    var deadline = decisions.findById(submitted.decisionId()).orElseThrow().getDeadline();
    clock.now = NOW.plusSeconds(5);
    recovery.recover(clock.instant());
    var reference = store.deliveryCandidates(clock.instant()).stream()
        .filter(item -> item.getId().equals(submitted.decisionId())).findFirst().orElseThrow();
    var oldDelivery = store.claimDelivery(reference, clock.instant()).orElseThrow();
    var fresh = store.claimWork(submitted.decisionId(), clock.instant()).orElseThrow();
    assertThat(transitions.applyDecision(session.getSessionId(), submitted.decisionId(), oldToken,
        append())).isEqualTo(ApplicationResult.IGNORED);
    assertThat(transitions.applyDecision(session.getSessionId(), submitted.decisionId(),
        fresh.attemptToken(), append())).isEqualTo(ApplicationResult.APPLIED);
    store.finishDelivery(oldDelivery, true, clock.instant());
    assertThat(runtime().getUsedFollowUps()).isOne();
    assertThat(decisions.findById(submitted.decisionId()).orElseThrow().getDeadline()).isEqualTo(deadline);
  }

  @Test
  @DisplayName("截止时应用与超时双连接争用只推进一次且不消耗追问预算")
  void applyVersusExpiryAtDeadline() throws Exception {
    var submitted = submitAnswer();
    var claim = store.claimWork(submitted.decisionId(), NOW).orElseThrow();
    clock.now = NOW.plusSeconds(30);
    assertThat(race(runtimeLockSql(),
        () -> transitions.applyDecision(session.getSessionId(), submitted.decisionId(), claim.attemptToken(), append()),
        () -> transitions.expireDecision(session.getSessionId(), submitted.decisionId())))
        .containsExactlyInAnyOrder(ApplicationResult.IGNORED, ApplicationResult.APPLIED);
    assertThat(runtime().getCurrentMainOrdinal()).isOne();
    assertThat(runtime().getUsedFollowUps()).isZero();
    assertThat(answers.findBySessionIdOrderByQuestionIndex(session.getId())).hasSize(1);
  }

  @Test
  @DisplayName("应用与交卷竞争只冻结一个版本，过期交卷可刷新重试")
  void applyVersusComplete() throws Exception {
    var submitted = submitAnswer();
    var claim = store.claimWork(submitted.decisionId(), NOW).orElseThrow();
    race(runtimeLockSql(),
        () -> transitions.applyDecision(session.getSessionId(), submitted.decisionId(), claim.attemptToken(), append()),
        () -> outcome(() -> commands.complete(owner.getId(), session.getSessionId(), submitted.runtime().version())));
    freeze();
    assertThat(runtime().getPhase()).isEqualTo(DynamicPhase.COMPLETED);
    assertThat(runtime().getUsedFollowUps()).isBetween(0, 1);
    assertThat(jdbc().queryForObject("select count(*) from interview_dynamic_evaluation_outbox "
        + "where session_id = ?", Integer.class, session.getId())).isOne();
    assertThat(transitions.applyDecision(session.getSessionId(), submitted.decisionId(),
        claim.attemptToken(), append())).isEqualTo(ApplicationResult.IGNORED);
  }

  @Test
  @DisplayName("应用与统一删除竞争不会留下迟到问题或任务")
  void applyVersusDelete() throws Exception {
    var submitted = submitAnswer();
    var claim = store.claimWork(submitted.decisionId(), NOW).orElseThrow();
    race(runtimeLockSql(),
        () -> transitions.applyDecision(session.getSessionId(), submitted.decisionId(), claim.attemptToken(), append()),
        () -> { deletion.deleteOwnedSessions(owner.getId(), List.of(session.getId())); return "deleted"; });
    assertDeleted();
    assertThat(transitions.applyDecision(session.getSessionId(), submitted.decisionId(),
        claim.attemptToken(), append())).isEqualTo(ApplicationResult.IGNORED);
  }

  @Test
  @DisplayName("交卷与删除竞争后数据库不残留冻结报告任务")
  void completeVersusDelete() throws Exception {
    race(runtimeLockSql(), () -> outcome(() -> commands.complete(owner.getId(), session.getSessionId(), 0)),
        () -> { deletion.deleteOwnedSessions(owner.getId(), List.of(session.getId())); return "deleted"; });
    assertDeleted();
  }

  @Test
  @DisplayName("两个评价同时完成只写入一次且JSON和反馈精确重放")
  void evaluationsCompleteOnce() throws Exception {
    submitAnswer();
    var id = freeze();
    var report = report(id);
    var results = race(runtimeLockSql(),
        () -> persistence.completeDynamicReport(session.getSessionId(), id, report.frozenVersion(), report),
        () -> persistence.completeDynamicReport(session.getSessionId(), id, report.frozenVersion(), report));
    assertThat(results).containsExactlyInAnyOrder(CompletionResult.CREATED, CompletionResult.ALREADY_COMPLETED);
    String json = sessions.findById(session.getId()).orElseThrow().getReportJson();
    assertThat(persistence.completeDynamicReport(session.getSessionId(), id, report.frozenVersion(), report))
        .isEqualTo(CompletionResult.ALREADY_COMPLETED);
    assertThat(sessions.findById(session.getId()).orElseThrow().getReportJson()).isEqualTo(json);
    assertThat(answers.findBySessionIdOrderByQuestionIndex(session.getId()).getFirst().getScore()).isEqualTo(10);
  }

  @Test
  @DisplayName("评价与删除竞争后迟到报告不能复活会话")
  void evaluateVersusDelete() throws Exception {
    submitAnswer();
    var id = freeze();
    var report = report(id);
    race(runtimeLockSql(),
        () -> persistence.completeDynamicReport(session.getSessionId(), id, report.frozenVersion(), report),
        () -> { deletion.deleteOwnedSessions(owner.getId(), List.of(session.getId())); return "deleted"; });
    assertDeleted();
    assertThat(persistence.completeDynamicReport(session.getSessionId(), id, report.frozenVersion(), report))
        .isEqualTo(CompletionResult.SESSION_MISSING);
  }

  @Test
  @DisplayName("两个记忆派生争用会话行只生成每组一份证据")
  void derivationsAreExactlyOnce() throws Exception {
    evaluate();
    var results = race("select id from interview_sessions where id = " + session.getId() + " for update",
        () -> derivation.derive(session.getSessionId(), UUID.randomUUID()),
        () -> derivation.derive(session.getSessionId(), UUID.randomUUID()));
    assertThat(results).containsExactlyInAnyOrder(DerivationResult.CREATED, DerivationResult.ALREADY_COMPLETED);
    assertThat(evidence.findByOwnerIdAndInterviewSessionId(owner.getId(), session.getId())).hasSize(1);
    assertThat(proposals.findByOwnerIdAndInterviewSessionId(owner.getId(), session.getId())).hasSize(1);
  }

  @Test
  @DisplayName("记忆派生与删除交错不留下提案、证据或支持来源")
  void deriveVersusDelete() throws Exception {
    evaluate();
    race("select id from interview_sessions where id = " + session.getId() + " for update",
        () -> derivation.derive(session.getSessionId(), UUID.randomUUID()),
        () -> { deletion.deleteOwnedSessions(owner.getId(), List.of(session.getId())); return "deleted"; });
    assertDeleted();
    assertThat(derivation.derive(session.getSessionId(), UUID.randomUUID())).isEqualTo(DerivationResult.DISCARDED);
  }

  @Test
  @DisplayName("确认与删除通过所有者串行锁保证画像重放不会保留已删证据")
  void confirmVersusDelete() throws Exception {
    evaluate();
    derivation.derive(session.getSessionId(), UUID.randomUUID());
    var proposal = proposals.findByOwnerIdAndInterviewSessionId(owner.getId(), session.getId()).getFirst();
    race("select id from users where id = '" + owner.getId() + "' for update",
        () -> outcome(() -> profileCommands.accept(owner.getId(), proposal.getId(), proposal.getVersion())),
        () -> { deletion.deleteOwnedSessions(owner.getId(), List.of(session.getId())); return "deleted"; });
    assertDeleted();
    profiles.findByOwnerIdAndSkillIdAndCategoryKey(owner.getId(), "java-backend", "JAVA")
        .ifPresent(profile -> assertThat(profile.getConfirmedEvidenceCount()).isZero());
  }

  @Test
  @DisplayName("简历真实删除入口批量清理两场动态会话并阻止并发追问")
  void resumeDeletionVersusApplyCleansAllSessions() throws Exception {
    var second = createSession(3);
    var resume = new ResumeEntity();
    resume.setOwner(owner);
    resume.setFileHash(UUID.randomUUID().toString());
    resume.setOriginalFilename("synthetic.txt");
    tx.executeWithoutResult(status -> {
      em.persist(resume);
      em.find(InterviewSessionEntity.class, session.getId()).setResume(resume);
      em.find(InterviewSessionEntity.class, second.getId()).setResume(resume);
    });
    var submitted = submitAnswer();
    var claim = store.claimWork(submitted.decisionId(), NOW).orElseThrow();
    race(runtimeLockSql(),
        () -> transitions.applyDecision(session.getSessionId(), submitted.decisionId(), claim.attemptToken(), append()),
        () -> { resumePersistence.deleteResume(resume.getId()); return "deleted"; });
    assertDeleted();
    assertThat(sessions.findById(second.getId())).isEmpty();
    assertThat(jdbc().queryForObject("select count(*) from resumes where id = ?", Integer.class,
        resume.getId())).isZero();
  }

  @Test
  @DisplayName("账户禁用提交后释放运行锁，等待中的应用读取最新归属状态并丢弃")
  void disabledOwnerFencesWaitingApplication() throws Exception {
    var submitted = submitAnswer();
    var claim = store.claimWork(submitted.decisionId(), NOW).orElseThrow();
    try (var blocker = dataSource.getConnection()) {
      blocker.setAutoCommit(false);
      try (var statement = blocker.createStatement()) { statement.executeQuery(runtimeLockSql()); }
      var started = new CountDownLatch(1);
      var task = new FutureTask<>(() -> {
        started.countDown();
        return transitions.applyDecision(session.getSessionId(), submitted.decisionId(), claim.attemptToken(), append());
      });
      var thread = new Thread(task, "task10-disabled-owner");
      thread.start();
      try {
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(jdbc().queryForObject(
            "select count(*) from pg_stat_activity where datname = current_database() "
                + "and wait_event_type = 'Lock'", Integer.class)).isOne());
        jdbc().update("update users set status = 'DISABLED' where id = ?", owner.getId());
      } finally { blocker.rollback(); }
      assertThat(task.get(10, TimeUnit.SECONDS)).isEqualTo(ApplicationResult.IGNORED);
      thread.join(1000);
      assertThat(thread.isAlive()).isFalse();
    }
    assertThat(runtime().getUsedFollowUps()).isZero();
    assertThat(runtime().getPhase()).isEqualTo(DynamicPhase.DECISION_PENDING);
    assertThat(store.claimWork(submitted.decisionId(), NOW)).isEmpty();
    assertThat(decisions.findById(submitted.decisionId()).orElseThrow().getStatus()).isEqualTo(Status.CANCELLED);
  }

  @Test
  @DisplayName("未返回的模型不持有事务，交卷先提交后迟到结果不可追加")
  void lateUninterruptibleModelCannotAppendAfterCompletion() throws Exception {
    var submitted = submitAnswer();
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    when(model.decide(any(), any(), any())).thenAnswer(call -> {
      assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
      entered.countDown();
      assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
      return followUp();
    });
    var task = new FutureTask<>(() -> { worker.process(submitted.decisionId()); return "returned"; });
    var thread = new Thread(task, "task10-late-model");
    thread.start();
    try {
      assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
      freeze();
      assertThat(runtime().getPhase()).isEqualTo(DynamicPhase.COMPLETED);
    } finally { release.countDown(); }
    assertThat(task.get(10, TimeUnit.SECONDS)).isEqualTo("returned");
    thread.join(1000);
    assertThat(thread.isAlive()).isFalse();
    assertThat(runtime().getUsedFollowUps()).isZero();
    assertThat(queries.get(owner.getId(), session.getSessionId()).totalQuestions()).isOne();
  }

  private void evaluate() throws Exception {
    submitAnswer();
    var id = freeze();
    var report = report(id);
    assertThat(persistence.completeDynamicReport(session.getSessionId(), id, report.frozenVersion(), report))
        .isEqualTo(CompletionResult.CREATED);
  }

  private void assertDeleted() {
    assertThat(sessions.findById(session.getId())).isEmpty();
    for (String table : List.of("interview_dynamic_runtime", "interview_follow_up_decisions",
        "interview_dynamic_evaluation_outbox", "interview_answers")) {
      assertThat(jdbc().queryForObject("select count(*) from " + table + " where session_id = ?",
          Integer.class, session.getId())).as(table).isZero();
    }
    assertThat(evidence.findByOwnerIdAndInterviewSessionId(owner.getId(), session.getId())).isEmpty();
    assertThat(proposals.findByOwnerIdAndInterviewSessionId(owner.getId(), session.getId())).isEmpty();
    assertThat(jdbc().queryForObject("select count(*) from user_ability_evidence_sources "
        + "where interview_session_id = ?", Integer.class, session.getId())).isZero();
  }
}
