package interview.guide.modules.knowledgebase.messaging.rabbit;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.knowledgebase.messaging.KnowledgeVectorizationTaskPublisher;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
    name = "app.knowledge-vectorization.messaging.provider",
    havingValue = "rabbitmq",
    matchIfMissing = true
)
public class KnowledgeVectorizationRabbitProducer
    implements KnowledgeVectorizationTaskPublisher {

  private static final Duration DEFAULT_CONFIRM_TIMEOUT = Duration.ofSeconds(5);

  private final RabbitTemplate rabbitTemplate;
  private final KnowledgeVectorizationRabbitProperties properties;
  private final Duration confirmTimeout;

  @Autowired
  public KnowledgeVectorizationRabbitProducer(
      RabbitTemplate knowledgeVectorizationRabbitTemplate,
      KnowledgeVectorizationRabbitProperties properties
  ) {
    this(knowledgeVectorizationRabbitTemplate, properties, DEFAULT_CONFIRM_TIMEOUT);
  }

  KnowledgeVectorizationRabbitProducer(
      RabbitTemplate rabbitTemplate,
      KnowledgeVectorizationRabbitProperties properties,
      Duration confirmTimeout
  ) {
    this.rabbitTemplate = rabbitTemplate;
    this.properties = properties;
    this.confirmTimeout = confirmTimeout;
  }

  @Override
  public PublishReceipt publish(Long knowledgeBaseId) {
    UUID messageId = UUID.randomUUID();
    var message = new KnowledgeVectorizationMessage(
        messageId,
        knowledgeBaseId,
        0,
        OffsetDateTime.now(),
        messageId
    );
    publishConfirmed(properties.exchange(), properties.routingKey(), message, null);
    return new PublishReceipt(messageId);
  }

  public PublishReceipt publishRetry(
      KnowledgeVectorizationMessage failed,
      KnowledgeVectorizationRetryPolicy.RetryDestination destination
  ) {
    UUID messageId = UUID.randomUUID();
    var retry = new KnowledgeVectorizationMessage(
        messageId,
        failed.knowledgeBaseId(),
        failed.retryCount() + 1,
        OffsetDateTime.now(),
        failed.originalMessageId()
    );
    publishConfirmed(properties.exchange(), destination.routingKey(), retry, null);
    return new PublishReceipt(messageId);
  }

  public PublishReceipt publishDead(
      KnowledgeVectorizationMessage failed,
      String failureReason
  ) {
    UUID messageId = UUID.randomUUID();
    var dead = new KnowledgeVectorizationMessage(
        messageId,
        failed.knowledgeBaseId(),
        failed.retryCount(),
        OffsetDateTime.now(),
        failed.originalMessageId()
    );
    publishConfirmed(
        properties.deadExchange(),
        properties.deadRoutingKey(),
        dead,
        truncate(failureReason)
    );
    return new PublishReceipt(messageId);
  }

  private void publishConfirmed(
      String exchange,
      String routingKey,
      KnowledgeVectorizationMessage message,
      String failureReason
  ) {
    CorrelationData correlation = new CorrelationData(message.messageId().toString());
    rabbitTemplate.convertAndSend(
        exchange,
        routingKey,
        message,
        outbound -> {
          outbound.getMessageProperties().setDeliveryMode(MessageDeliveryMode.PERSISTENT);
          outbound.getMessageProperties().setMessageId(message.messageId().toString());
          if (failureReason != null) {
            outbound.getMessageProperties().setHeader(
                "x-knowledge-vectorization-failure",
                failureReason
            );
          }
          return outbound;
        },
        correlation
    );

    try {
      CorrelationData.Confirm confirm =
          correlation.getFuture().get(confirmTimeout.toMillis(), TimeUnit.MILLISECONDS);
      if (!confirm.ack()) {
        throw failure("RabbitMQ rejected knowledge vectorization: " + confirm.reason());
      }
      if (correlation.getReturned() != null) {
        throw failure("RabbitMQ returned unroutable knowledge vectorization");
      }
    } catch (BusinessException exception) {
      throw exception;
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw confirmationFailure(exception);
    } catch (ExecutionException | TimeoutException exception) {
      throw confirmationFailure(exception);
    }
  }

  private BusinessException failure(String message) {
    return new BusinessException(ErrorCode.KNOWLEDGE_BASE_VECTORIZATION_FAILED, message);
  }

  private BusinessException confirmationFailure(Exception exception) {
    return new BusinessException(
        ErrorCode.KNOWLEDGE_BASE_VECTORIZATION_FAILED,
        "RabbitMQ knowledge vectorization confirmation failed",
        exception
    );
  }

  private String truncate(String value) {
    return value == null || value.length() <= 500 ? value : value.substring(0, 500);
  }
}
