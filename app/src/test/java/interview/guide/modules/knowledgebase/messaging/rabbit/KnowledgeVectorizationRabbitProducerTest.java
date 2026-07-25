package interview.guide.modules.knowledgebase.messaging.rabbit;

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

class KnowledgeVectorizationRabbitProducerTest {

  private RabbitTemplate rabbitTemplate;
  private KnowledgeVectorizationRabbitProducer producer;

  @BeforeEach
  void setUp() {
    rabbitTemplate = mock(RabbitTemplate.class);
    var properties = new KnowledgeVectorizationRabbitProperties(
        "rabbitmq",
        "knowledge.vectorization.exchange",
        "knowledge.vectorization.queue",
        "knowledge.vectorization",
        "knowledge.vectorization.dead.exchange",
        "knowledge.vectorization.dead.queue",
        "knowledge.vectorization.dead",
        List.of(Duration.ofSeconds(10), Duration.ofSeconds(30), Duration.ofSeconds(60))
    );
    producer = new KnowledgeVectorizationRabbitProducer(
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
        eq("knowledge.vectorization.exchange"),
        eq("knowledge.vectorization"),
        any(KnowledgeVectorizationMessage.class),
        any(),
        any(CorrelationData.class)
    );

    var receipt = producer.publish(42L);

    assertThat(receipt.messageId()).isNotNull();
    ArgumentCaptor<KnowledgeVectorizationMessage> message =
        ArgumentCaptor.forClass(KnowledgeVectorizationMessage.class);
    verify(rabbitTemplate).convertAndSend(
        eq("knowledge.vectorization.exchange"),
        eq("knowledge.vectorization"),
        message.capture(),
        any(),
        any(CorrelationData.class)
    );
    assertThat(message.getValue().knowledgeBaseId()).isEqualTo(42L);
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

    assertThatThrownBy(() -> producer.publish(42L))
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
    var original = new KnowledgeVectorizationMessage(
        java.util.UUID.randomUUID(),
        42L,
        0,
        java.time.OffsetDateTime.now(),
        null
    );

    var receipt = producer.publishRetry(
        original,
        new KnowledgeVectorizationRetryPolicy.RetryDestination(
            "knowledge.vectorization.retry.10s",
            Duration.ofSeconds(10),
            false
        )
    );

    assertThat(receipt.messageId()).isNotEqualTo(original.messageId());
  }
}
