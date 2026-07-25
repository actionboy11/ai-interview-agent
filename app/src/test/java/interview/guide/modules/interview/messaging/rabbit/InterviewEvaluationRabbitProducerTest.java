package interview.guide.modules.interview.messaging.rabbit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import interview.guide.common.exception.BusinessException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

class InterviewEvaluationRabbitProducerTest {

  private RabbitTemplate rabbitTemplate;
  private InterviewEvaluationRabbitProducer producer;

  @BeforeEach
  void setUp() {
    rabbitTemplate = mock(RabbitTemplate.class);
    var properties = new InterviewEvaluationRabbitProperties(
        "rabbitmq",
        "interview.evaluation.exchange",
        "interview.evaluation.queue",
        "interview.evaluation",
        "interview.evaluation.dead.exchange",
        "interview.evaluation.dead.queue",
        "interview.evaluation.dead",
        List.of(Duration.ofSeconds(10), Duration.ofSeconds(30), Duration.ofSeconds(60))
    );
    producer = new InterviewEvaluationRabbitProducer(
        rabbitTemplate,
        properties,
        Duration.ofMillis(100)
    );
  }

  @Test
  @DisplayName("发布确认 ACK 后返回消息标识")
  void shouldReturnReceiptAfterAck() {
    doAnswer(invocation -> {
      CorrelationData correlation = invocation.getArgument(4);
      correlation.getFuture().complete(new CorrelationData.Confirm(true, null));
      return null;
    }).when(rabbitTemplate).convertAndSend(
        eq("interview.evaluation.exchange"),
        eq("interview.evaluation"),
        any(InterviewEvaluationMessage.class),
        any(),
        any(CorrelationData.class)
    );

    var receipt = producer.publish("session-42");

    assertThat(receipt.messageId()).isNotNull();
    ArgumentCaptor<InterviewEvaluationMessage> message =
        ArgumentCaptor.forClass(InterviewEvaluationMessage.class);
    verify(rabbitTemplate).convertAndSend(
        eq("interview.evaluation.exchange"),
        eq("interview.evaluation"),
        message.capture(),
        any(),
        any(CorrelationData.class)
    );
    assertThat(message.getValue().sessionId()).isEqualTo("session-42");
    assertThat(message.getValue().originalMessageId()).isEqualTo(receipt.messageId());
  }

  @Test
  @DisplayName("发布确认 NACK 时抛出业务异常")
  void shouldFailAfterNack() {
    doAnswer(invocation -> {
      CorrelationData correlation = invocation.getArgument(4);
      correlation.getFuture().complete(new CorrelationData.Confirm(false, "rejected"));
      return null;
    }).when(rabbitTemplate).convertAndSend(any(), any(), any(), any(), any(CorrelationData.class));

    assertThatThrownBy(() -> producer.publish("session-42"))
        .isInstanceOf(BusinessException.class);
  }

  @Test
  @DisplayName("重试生成新消息标识并保留原始消息标识")
  void shouldPreserveOriginalMessageIdForRetry() {
    doAnswer(invocation -> {
      CorrelationData correlation = invocation.getArgument(4);
      correlation.getFuture().complete(new CorrelationData.Confirm(true, null));
      return null;
    }).when(rabbitTemplate).convertAndSend(any(), any(), any(), any(), any(CorrelationData.class));
    var original = new InterviewEvaluationMessage(
        java.util.UUID.randomUUID(),
        "session-42",
        0,
        java.time.OffsetDateTime.now(),
        null
    );

    var receipt = producer.publishRetry(
        original,
        new InterviewEvaluationRetryPolicy.RetryDestination(
            "interview.evaluation.retry.10s",
            Duration.ofSeconds(10),
            false
        )
    );

    assertThat(receipt.messageId()).isNotEqualTo(original.messageId());
  }
}
