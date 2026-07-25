package interview.guide.modules.interview.messaging.rabbit;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.interview.messaging.InterviewEvaluationTaskPublisher;
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
    name = "app.interview-evaluation.messaging.provider",
    havingValue = "rabbitmq",
    matchIfMissing = true
)
public class InterviewEvaluationRabbitProducer
    implements InterviewEvaluationTaskPublisher {

  private static final Duration DEFAULT_CONFIRM_TIMEOUT = Duration.ofSeconds(5);

  private final RabbitTemplate rabbitTemplate;
  private final InterviewEvaluationRabbitProperties properties;
  private final Duration confirmTimeout;

  @Autowired
  public InterviewEvaluationRabbitProducer(
      RabbitTemplate interviewEvaluationRabbitTemplate,
      InterviewEvaluationRabbitProperties properties
  ) {
    this(interviewEvaluationRabbitTemplate, properties, DEFAULT_CONFIRM_TIMEOUT);
  }

  InterviewEvaluationRabbitProducer(
      RabbitTemplate rabbitTemplate,
      InterviewEvaluationRabbitProperties properties,
      Duration confirmTimeout
  ) {
    this.rabbitTemplate = rabbitTemplate;
    this.properties = properties;
    this.confirmTimeout = confirmTimeout;
  }

  @Override
  public PublishReceipt publish(String sessionId) {
    UUID messageId = UUID.randomUUID();
    var message = new InterviewEvaluationMessage(
        messageId,
        sessionId,
        0,
        OffsetDateTime.now(),
        messageId
    );
    publishConfirmed(properties.exchange(), properties.routingKey(), message, null);
    return new PublishReceipt(messageId);
  }

  public PublishReceipt publishRetry(
      InterviewEvaluationMessage failed,
      InterviewEvaluationRetryPolicy.RetryDestination destination
  ) {
    UUID messageId = UUID.randomUUID();
    var retry = new InterviewEvaluationMessage(
        messageId,
        failed.sessionId(),
        failed.retryCount() + 1,
        OffsetDateTime.now(),
        failed.originalMessageId()
    );
    publishConfirmed(properties.exchange(), destination.routingKey(), retry, null);
    return new PublishReceipt(messageId);
  }

  public PublishReceipt publishDead(
      InterviewEvaluationMessage failed,
      String failureReason
  ) {
    UUID messageId = UUID.randomUUID();
    var dead = new InterviewEvaluationMessage(
        messageId,
        failed.sessionId(),
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
      InterviewEvaluationMessage message,
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
                "x-interview-evaluation-failure",
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
        throw failure("RabbitMQ rejected text evaluation: " + confirm.reason());
      }
      if (correlation.getReturned() != null) {
        throw failure("RabbitMQ returned unroutable text evaluation");
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
    return new BusinessException(ErrorCode.INTERVIEW_EVALUATION_FAILED, message);
  }

  private BusinessException confirmationFailure(Exception exception) {
    return new BusinessException(
        ErrorCode.INTERVIEW_EVALUATION_FAILED,
        "RabbitMQ text evaluation confirmation failed",
        exception
    );
  }

  private String truncate(String value) {
    return value == null || value.length() <= 500 ? value : value.substring(0, 500);
  }
}
