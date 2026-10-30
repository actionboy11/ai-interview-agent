package interview.guide.modules.interview.dynamic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.model.AsyncTaskStatus;
import interview.guide.common.transaction.TransactionalExecutor;
import interview.guide.infrastructure.redis.RedisService;
import interview.guide.modules.interview.listener.EvaluateStreamProducer;
import interview.guide.modules.interview.messaging.InterviewEvaluationTaskPublisher;
import interview.guide.modules.interview.messaging.rabbit.InterviewEvaluationMessage;
import interview.guide.modules.interview.messaging.rabbit.InterviewEvaluationRabbitProducer;
import interview.guide.modules.interview.messaging.rabbit.InterviewEvaluationRabbitProperties;
import interview.guide.modules.interview.service.InterviewPersistenceService;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

@DisplayName("两条评价发布通道的稳定消息ID兼容契约")
class DynamicStableEvaluationPublisherTest {
  @Test
  @DisplayName("Rabbit稳定重投保留payload与确认correlation的消息ID")
  void rabbitStablePublishKeepsIdentity() {
    var rabbit = mock(RabbitTemplate.class);
    var sent = new ArrayList<InterviewEvaluationMessage>();
    var properties = new InterviewEvaluationRabbitProperties("rabbitmq", "exchange", "queue",
        "routing", "dead-exchange", "dead-queue", "dead-routing", List.of(Duration.ofSeconds(10)));
    doAnswer(invocation -> {
      InterviewEvaluationMessage message = invocation.getArgument(2);
      CorrelationData correlation = invocation.getArgument(4);
      assertThat(correlation.getId()).isEqualTo(message.messageId().toString());
      sent.add(message);
      correlation.getFuture().complete(new CorrelationData.Confirm(true, null));
      return null;
    }).when(rabbit).convertAndSend(anyString(), anyString(), any(), any(), any(CorrelationData.class));
    var producer = new InterviewEvaluationRabbitProducer(rabbit, properties);
    UUID id = UUID.randomUUID();
    assertThatCode(() -> {
      assertThat(producer.publish("session-1", id).messageId()).isEqualTo(id);
      assertThat(producer.publish("session-1", id).messageId()).isEqualTo(id);
    }).doesNotThrowAnyException();
    assertThat(sent).hasSize(2).allSatisfy(message -> {
      assertThat(message.messageId()).isEqualTo(id);
      assertThat(message.originalMessageId()).isEqualTo(id);
    });
  }

  @Test
  @DisplayName("Redis稳定重投在实际Stream字段中携带指定ID而不只是回执")
  void redisStablePublishKeepsIdentityInPayload() {
    var redis = mock(RedisService.class);
    var sent = new ArrayList<Map<String, String>>();
    when(redis.streamAdd(anyString(), anyMap(), anyInt())).thenAnswer(invocation -> {
      sent.add(invocation.getArgument(1));
      return "1-0";
    });
    var producer = new EvaluateStreamProducer(redis, mock(InterviewPersistenceService.class),
        mock(TransactionalExecutor.class));
    UUID id = UUID.randomUUID();
    assertThatCode(() -> {
      assertThat(producer.publish("session-1", id).messageId()).isEqualTo(id);
      assertThat(producer.publish("session-1", id).messageId()).isEqualTo(id);
    }).doesNotThrowAnyException();
    assertThat(sent).hasSize(2).allSatisfy(payload -> assertThat(payload)
        .containsExactlyInAnyOrderEntriesOf(Map.of("sessionId", "session-1",
            "messageId", id.toString(), "retryCount", "0")));
  }

  @Test
  @DisplayName("稳定Redis发送失败必须可观察且不能提前把会话评估标成失败")
  void redisStableFailurePropagatesButLegacyFailureSemanticsRemain() {
    var redis = mock(RedisService.class);
    var persistence = mock(InterviewPersistenceService.class);
    var transactions = mock(TransactionalExecutor.class);
    doAnswer(invocation -> { invocation.<Runnable>getArgument(0).run(); return null; })
        .when(transactions).runRequiresNew(any());
    when(redis.streamAdd(anyString(), anyMap(), anyInt()))
        .thenThrow(new IllegalStateException("queue unavailable"));
    var producer = new EvaluateStreamProducer(redis, persistence, transactions);
    assertThatThrownBy(() -> producer.publish("stable", UUID.randomUUID()))
        .isInstanceOf(BusinessException.class);
    verifyNoInteractions(persistence, transactions);
    assertThatCode(() -> producer.publish("legacy")).doesNotThrowAnyException();
    verify(persistence).updateEvaluateStatusInternal(eq("legacy"), eq(AsyncTaskStatus.FAILED), any());
  }

  @Test
  @DisplayName("旧lambda仍可编译调用，未适配稳定ID的publisher明确拒绝")
  void legacyLambdaMustNotSilentlyDiscardStableId() {
    UUID old = UUID.randomUUID();
    InterviewEvaluationTaskPublisher legacy = sessionId ->
        new InterviewEvaluationTaskPublisher.PublishReceipt(old);
    assertThat(legacy.publish("legacy").messageId()).isEqualTo(old);
    assertThatThrownBy(() -> legacy.publish("dynamic", UUID.randomUUID()))
        .isInstanceOf(UnsupportedOperationException.class);
  }
}
