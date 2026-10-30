package interview.guide.modules.interview.dynamic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import interview.guide.modules.interview.dynamic.model.DynamicPhase;
import interview.guide.modules.interview.dynamic.model.FollowUpDecisionEntity.Action;
import interview.guide.modules.interview.dynamic.model.FollowUpDecisionEntity.DeliveryStatus;
import interview.guide.modules.interview.dynamic.model.FollowUpDecisionEntity.Reason;
import interview.guide.modules.interview.dynamic.model.FollowUpDecisionEntity.Status;
import interview.guide.modules.interview.dynamic.service.DynamicInterviewTransitionService.ApplicationResult;
import interview.guide.modules.interview.dynamic.service.DynamicInterviewTransitionService.DecisionApplication;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("动态决策数据库期限与租约恢复")
class DynamicDecisionRecoveryTest extends DynamicDecisionTestSupport {
  @Test
  @DisplayName("已发布甚至已ACK丢失的任务仍按数据库期限推进且不扣预算")
  void timeoutMovesNextWithoutBudgetCharge() {
    var submitted = submitAnswer();
    tx.executeWithoutResult(status -> decisions.findByIdForUpdate(submitted.decisionId())
        .orElseThrow().setDeliveryStatus(DeliveryStatus.PUBLISHED));
    clock.now = NOW.plusSeconds(30);
    recovery.recover(clock.instant());
    recovery.recover(clock.instant());
    assertThat(runtime().getPhase()).isEqualTo(DynamicPhase.WAITING_ANSWER);
    assertThat(runtime().getCurrentMainOrdinal()).isEqualTo(1);
    assertThat(runtime().getUsedFollowUps()).isZero();
    assertThat(runtime().getNoticeCode()).isEqualTo("DECISION_TIMEOUT");
    assertThat(queries.get(owner.getId(), session.getSessionId()).totalQuestions()).isEqualTo(2);
    assertThat(decisions.findById(submitted.decisionId()).orElseThrow().getStatus())
        .isEqualTo(Status.FAILED);
  }

  @Test
  @DisplayName("租约到期重新认领更换令牌，旧尝试结果不可应用且期限不延长")
  void expiredLeaseRejectsOldAttempt() {
    properties.setWorkLease(Duration.ofSeconds(5));
    var submitted = submitAnswer();
    var first = store.claimWork(submitted.decisionId(), NOW);
    assertThat(first).isPresent();
    var old = first.orElseThrow();
    clock.now = NOW.plusSeconds(5);
    recovery.recover(clock.instant());
    var second = store.claimWork(submitted.decisionId(), clock.instant());
    assertThat(second).isPresent();
    var fresh = second.orElseThrow();
    assertThat(fresh.attemptToken()).isNotEqualTo(old.attemptToken());
    assertThat(fresh.deadline()).isEqualTo(NOW.plusSeconds(30));
    assertThat(runtime().getVersion()).isEqualTo(submitted.runtime().version());
    var application = new DecisionApplication(Action.FOLLOW_UP, Reason.IMPORTANT_GAP,
        "缺少边界", "{}", "追问", null, null);
    assertThat(transitions.applyDecision(session.getSessionId(), submitted.decisionId(),
        old.attemptToken(), application)).isEqualTo(ApplicationResult.IGNORED);
    assertThat(transitions.applyDecision(session.getSessionId(), submitted.decisionId(),
        fresh.attemptToken(), application)).isEqualTo(ApplicationResult.APPLIED);
    assertThat(runtime().getUsedFollowUps()).isEqualTo(1);
  }

  @Test
  @DisplayName("有效工作租约拒绝重复认领，崩溃后过期租约恢复待补投")
  void activeLeaseCannotBeStolen() {
    var submitted = submitAnswer();
    var first = store.claimWork(submitted.decisionId(), NOW);
    assertThat(first).isPresent();
    assertThat(store.claimWork(submitted.decisionId(), NOW.plusSeconds(1))).isEmpty();
    tx.executeWithoutResult(status -> {
      var row = decisions.findByIdForUpdate(submitted.decisionId()).orElseThrow();
      row.setDeliveryStatus(DeliveryStatus.PUBLISHED);
      row.setLeaseUntil(NOW.plusSeconds(5));
    });
    clock.now = NOW.plusSeconds(5);
    recovery.recover(clock.instant());
    var recovered = decisions.findById(submitted.decisionId()).orElseThrow();
    assertThat(recovered.getStatus()).isEqualTo(Status.PENDING);
    assertThat(recovered.getDeliveryStatus()).isEqualTo(DeliveryStatus.PENDING);
    assertThat(recovered.getAttemptToken()).isNull();
    when(model.decide(any(), any(), any())).thenReturn(followUp());
    worker.process(submitted.decisionId());
    assertThat(runtime().getUsedFollowUps()).isEqualTo(1);
  }

  @Test
  @DisplayName("配送认领崩溃后可重领且旧配送完成不能覆盖新世代")
  void deliveryLeaseRecoversCrashAndFencesOldSender() {
    var submitted = submitAnswer();
    var reference = store.deliveryCandidates(NOW).getFirst();
    var first = store.claimDelivery(reference, NOW).orElseThrow();
    assertThat(store.claimDelivery(reference, NOW)).isEmpty();
    clock.now = NOW.plusSeconds(10);
    var second = store.claimDelivery(reference, clock.instant()).orElseThrow();
    store.finishDelivery(first, true, clock.instant());
    assertThat(decisions.findById(submitted.decisionId()).orElseThrow().getDeliveryStatus())
        .isEqualTo(DeliveryStatus.PENDING);
    var work = store.claimWork(submitted.decisionId(), clock.instant()).orElseThrow();
    store.finishDelivery(second, true, clock.instant());
    var row = decisions.findById(submitted.decisionId()).orElseThrow();
    assertThat(row.getDeliveryStatus()).isEqualTo(DeliveryStatus.PUBLISHED);
    assertThat(row.getAttemptToken()).isEqualTo(work.attemptToken());
    assertThat(row.getLeaseUntil()).isEqualTo(NOW.plusSeconds(30));
  }

  @Test
  @DisplayName("工作租约恢复会废弃旧配送世代，迟到发送确认不能吞掉补投")
  void recoveredWorkFencesOldDeliveryCompletion() {
    properties.setWorkLease(Duration.ofSeconds(5));
    var submitted = submitAnswer();
    var delivery = store.claimDelivery(store.deliveryCandidates(NOW).getFirst(), NOW).orElseThrow();
    store.claimWork(submitted.decisionId(), NOW);
    clock.now = NOW.plusSeconds(5);
    recovery.recover(clock.instant());
    store.finishDelivery(delivery, true, clock.instant());
    assertThat(decisions.findById(submitted.decisionId()).orElseThrow().getDeliveryStatus())
        .isEqualTo(DeliveryStatus.PENDING);
    assertThat(store.deliveryCandidates(clock.instant())).hasSize(1);
  }

  @Test
  @DisplayName("每轮投递与恢复扫描均有界且后续批次持续推进")
  void deliveryAndRecoveryAreBoundedBatches() {
    properties.setDeliveryBatchSize(2);
    when(redis.streamAdd(anyString(), anyMap(), anyInt())).thenReturn("1-0");
    for (int index = 0; index < 5; index++) {
      if (index > 0) { session = createSession(3); }
      submitAnswer();
    }
    dispatch.dispatch(NOW);
    assertThat(decisions.findAll().stream().filter(row -> row.getDeliveryStatus() == DeliveryStatus.PUBLISHED))
        .hasSize(2);
    clock.now = NOW.plusSeconds(30);
    recovery.recover(clock.instant());
    assertThat(decisions.findAll().stream().filter(row -> row.getStatus() == Status.FAILED)).hasSize(2);
    recovery.recover(clock.instant());
    recovery.recover(clock.instant());
    assertThat(decisions.findAll()).hasSize(5).allSatisfy(row -> assertThat(row.getStatus()).isEqualTo(Status.FAILED));
  }

  @Test
  @DisplayName("评价投递租约崩溃重领保留消息ID且旧令牌不能确认新尝试")
  void evaluationLeaseRecoversWithStableId() {
    commands.complete(owner.getId(), session.getSessionId(), 0);
    var reference = evaluationStore.candidates(NOW).getFirst();
    var first = evaluationStore.claim(reference, NOW).orElseThrow();
    assertThat(evaluationStore.claim(reference, NOW)).isEmpty();
    clock.now = NOW.plusSeconds(10);
    var second = evaluationStore.claim(reference, clock.instant()).orElseThrow();
    assertThat(second.messageId()).isEqualTo(first.messageId());
    assertThat(second.token()).isNotEqualTo(first.token());
    evaluationStore.finish(first, true, clock.instant());
    assertThat(outboxes.findById(first.outboxId()).orElseThrow().getDeliveryStatus())
        .isEqualTo(DeliveryStatus.PENDING);
    evaluationStore.finish(second, true, clock.instant());
    assertThat(outboxes.findById(first.outboxId()).orElseThrow().getDeliveryStatus())
        .isEqualTo(DeliveryStatus.PUBLISHED);
  }
}
