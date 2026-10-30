package interview.guide.modules.interview.dynamic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.auth.model.UserEntity;
import interview.guide.modules.auth.model.UserStatus;
import interview.guide.modules.interview.dynamic.model.DynamicPhase;
import interview.guide.modules.interview.dynamic.model.FollowUpDecisionEntity.DeliveryStatus;
import interview.guide.modules.interview.dynamic.model.FollowUpDecisionEntity.Status;
import interview.guide.modules.interview.messaging.InterviewEvaluationTaskPublisher.PublishReceipt;
import interview.guide.modules.interview.model.InterviewSessionEntity;
import java.util.Map;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@DisplayName("动态决策持久化投递与工作器")
class DynamicDecisionDeliveryTest extends DynamicDecisionTestSupport {
  @Test
  @DisplayName("提交后队列发送失败不会假标已发送，补投可恢复原任务")
  void commitThenPublishFailureIsRecovered() {
    var submitted = submitAnswer();
    when(redis.streamAdd(anyString(), anyMap(), anyInt()))
        .thenThrow(new IllegalStateException("queue offline")).thenAnswer(invocation -> {
          assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
          return "1-0";
        });
    dispatch.dispatch(NOW);
    var failed = decisions.findById(submitted.decisionId()).orElseThrow();
    assertThat(failed.getDeliveryAttempts()).isEqualTo(1);
    assertThat(failed.getDeliveryStatus()).isEqualTo(DeliveryStatus.PENDING);
    assertThat(runtime().getPhase()).isEqualTo(DynamicPhase.DECISION_PENDING);
    assertThat(answers.findBySession_SessionIdOrderByQuestionIndex(session.getSessionId()))
        .singleElement().satisfies(a -> assertThat(a.getUserAnswer()).isEqualTo("正式答案不可丢失"));
    clock.now = NOW.plusSeconds(2);
    dispatch.dispatch(clock.instant());
    var sent = decisions.findById(submitted.decisionId()).orElseThrow();
    assertThat(sent.getDeliveryStatus()).isEqualTo(DeliveryStatus.PUBLISHED);
    assertThat(sent.getDeliveryAttempts()).isEqualTo(2);
    assertThat(sent.getDeadline()).isEqualTo(NOW.plusSeconds(30));
    assertThat(runtime().getVersion()).isEqualTo(submitted.runtime().version());
  }

  @Test
  @DisplayName("重投只追加一个追问，模型调用使用可信provider且在事务外")
  void redeliveryDoesNotAppendTwice() {
    var submitted = submitAnswer();
    AtomicInteger calls = new AtomicInteger();
    when(model.decide(any(), any(), any())).thenAnswer(invocation -> {
      calls.incrementAndGet();
      assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
      assertThat(invocation.<String>getArgument(0)).isEqualTo("trusted-provider");
      assertThat(invocation.<Object>getArgument(2)).isEqualTo(NOW.plusSeconds(30));
      assertThat(runtime().getVersion()).isEqualTo(submitted.runtime().version());
      return followUp();
    });
    worker.process(submitted.decisionId());
    worker.process(submitted.decisionId());
    assertThat(runtime().getPhase()).isEqualTo(DynamicPhase.WAITING_ANSWER);
    assertThat(runtime().getUsedFollowUps()).isEqualTo(1);
    assertThat(queries.get(owner.getId(), session.getSessionId()).totalQuestions()).isEqualTo(2);
    assertThat(calls.get()).isEqualTo(1);
    assertThat(decisions.findById(submitted.decisionId()).orElseThrow().getStatus())
        .isEqualTo(Status.APPLIED);
  }

  @Test
  @DisplayName("排队时间计入原30秒期限，到期消费直接关闭且不调用模型")
  void queueWaitCountsTowardThirtySeconds() {
    var submitted = submitAnswer();
    clock.now = NOW.plusSeconds(30);
    worker.process(submitted.decisionId());
    assertThat(runtime().getPhase()).isEqualTo(DynamicPhase.WAITING_ANSWER);
    assertThat(runtime().getCurrentMainOrdinal()).isEqualTo(1);
    assertThat(runtime().getUsedFollowUps()).isZero();
    assertThat(runtime().getNoticeCode()).isEqualTo("DECISION_TIMEOUT");
    verifyNoInteractions(model);
  }

  @Test
  @DisplayName("模型异常关闭当前组保留答案，审计不泄漏模型异常正文")
  void modelFailureIsSafeAndDoesNotChargeBudget() {
    var submitted = submitAnswer();
    when(model.decide(any(), any(), any())).thenThrow(new BusinessException(
        ErrorCode.INTERVIEW_QUESTION_GENERATION_FAILED, "secret provider payload"));
    worker.process(submitted.decisionId());
    assertThat(runtime().getCurrentMainOrdinal()).isEqualTo(1);
    assertThat(runtime().getUsedFollowUps()).isZero();
    assertThat(runtime().getNoticeCode()).isEqualTo("MODEL_UNAVAILABLE");
    var decision = decisions.findById(submitted.decisionId()).orElseThrow();
    assertThat(decision.getStatus()).isEqualTo(Status.FAILED);
    assertThat(decision.getErrorCode()).isEqualTo("MODEL_UNAVAILABLE");
    assertThat(decision.getReasonSummary()).isNull();
    assertThat(answers.findBySession_SessionIdOrderByQuestionIndex(session.getSessionId()))
        .singleElement().satisfies(a -> assertThat(a.getUserAnswer()).isEqualTo("正式答案不可丢失"));
  }

  @Test
  @DisplayName("调用前账号失效丢弃任务并停止认领")
  void inactiveOwnerCannotInvokeModel() {
    var submitted = submitAnswer();
    tx.executeWithoutResult(status -> em.find(UserEntity.class, owner.getId())
        .setStatus(UserStatus.DISABLED));
    worker.process(submitted.decisionId());
    assertThat(decisions.findById(submitted.decisionId()).orElseThrow().getStatus())
        .isEqualTo(Status.CANCELLED);
    assertThat(runtime().getUsedFollowUps()).isZero();
    verifyNoInteractions(model);
  }

  @Test
  @DisplayName("调用后账号失效导致结果不可应用")
  void inactiveOwnerAfterModelCannotAppend() {
    var submitted = submitAnswer();
    var calls = new AtomicInteger();
    when(model.decide(any(), any(), any())).thenAnswer(invocation -> {
      calls.incrementAndGet();
      tx.executeWithoutResult(status -> em.find(UserEntity.class, owner.getId())
          .setStatus(UserStatus.DISABLED));
      return followUp();
    });
    worker.process(submitted.decisionId());
    assertThat(calls.get()).isEqualTo(1);
    assertThat(runtime().getUsedFollowUps()).isZero();
    assertThat(runtime().getCurrentQuestionIndex()).isZero();
  }

  @Test
  @DisplayName("模型返回时已过原期限只按超时推进下一主问题")
  void lateModelResultCannotConsumeBudget() {
    var submitted = submitAnswer();
    when(model.decide(any(), any(), any())).thenAnswer(invocation -> {
      clock.now = NOW.plusSeconds(30);
      return followUp();
    });
    worker.process(submitted.decisionId());
    assertThat(runtime().getCurrentMainOrdinal()).isEqualTo(1);
    assertThat(runtime().getUsedFollowUps()).isZero();
    assertThat(runtime().getNoticeCode()).isEqualTo("DECISION_TIMEOUT");
  }

  @Test
  @DisplayName("已删除会话的任务丢弃且不调用模型")
  void deletedSessionIsAcked() {
    var submitted = submitAnswer();
    tx.executeWithoutResult(status -> em.remove(em.find(InterviewSessionEntity.class, session.getId())));
    worker.process(submitted.decisionId());
    assertThat(decisions.findById(submitted.decisionId())).isEmpty();
    verifyNoInteractions(model);
  }

  @Test
  @DisplayName("决策Stream正文仅有decisionId与模板重试元数据")
  void streamDoesNotCarryAnswersOrOwner() {
    var submitted = submitAnswer();
    when(redis.streamAdd(anyString(), anyMap(), anyInt())).thenReturn("1-0");
    dispatch.dispatch(NOW);
    @SuppressWarnings("unchecked")
    ArgumentCaptor<Map<String, String>> payload = ArgumentCaptor.forClass(Map.class);
    verify(redis).streamAdd(anyString(), payload.capture(), anyInt());
    assertThat(payload.getValue()).containsExactlyInAnyOrderEntriesOf(
        Map.of("decisionId", submitted.decisionId().toString(), "retryCount", "0"));
  }

  @Test
  @DisplayName("评价outbox重试始终使用冻结事务创建的消息ID并在确认后标记已发送")
  void evaluationRetryUsesStableMessageId() {
    var completed = commands.complete(owner.getId(), session.getSessionId(), 0);
    var outbox = outboxes.findBySession_IdAndFrozenVersion(session.getId(), completed.version())
        .orElseThrow();
    var seen = new ArrayList<UUID>();
    doAnswer(invocation -> {
      assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
      UUID id = invocation.getArgument(1);
      seen.add(id);
      if (seen.size() == 1) { throw new IllegalStateException("confirm lost"); }
      return new PublishReceipt(id);
    }).when(publisher).publish(eq(session.getSessionId()), any(UUID.class));
    evaluationDispatch.dispatch(NOW);
    assertThat(outboxes.findById(outbox.getId()).orElseThrow().getDeliveryStatus())
        .isEqualTo(DeliveryStatus.PENDING);
    clock.now = NOW.plusSeconds(2);
    evaluationDispatch.dispatch(clock.instant());
    assertThat(seen).containsExactly(outbox.getMessageId(), outbox.getMessageId());
    assertThat(outboxes.findById(outbox.getId()).orElseThrow().getDeliveryStatus())
        .isEqualTo(DeliveryStatus.PUBLISHED);
  }

  @Test
  @DisplayName("模型调用期间提前交卷时旧决策结果被丢弃且只冻结一次")
  void completedDuringModelCannotAppend() {
    var submitted = submitAnswer();
    when(model.decide(any(), any(), any())).thenAnswer(invocation -> {
      commands.complete(owner.getId(), session.getSessionId(), submitted.runtime().version());
      return followUp();
    });
    worker.process(submitted.decisionId());
    assertThat(runtime().getPhase()).isEqualTo(DynamicPhase.COMPLETED);
    assertThat(runtime().getUsedFollowUps()).isZero();
    assertThat(decisions.findById(submitted.decisionId()).orElseThrow().getStatus())
        .isEqualTo(Status.CANCELLED);
    assertThat(outboxes.count()).isEqualTo(1);
  }

  @Test
  @DisplayName("错误评价回执不能把outbox标成已发布")
  void mismatchedEvaluationReceiptRemainsRetryable() {
    commands.complete(owner.getId(), session.getSessionId(), 0);
    when(publisher.publish(anyString(), any(UUID.class))).thenReturn(new PublishReceipt(UUID.randomUUID()));
    evaluationDispatch.dispatch(NOW);
    assertThat(outboxes.findAll()).singleElement().satisfies(row -> {
      assertThat(row.getDeliveryStatus()).isEqualTo(DeliveryStatus.PENDING);
      assertThat(row.getAttemptToken()).isNull();
      assertThat(row.getNextDeliveryAt()).isEqualTo(NOW.plusSeconds(2));
    });
  }
}
