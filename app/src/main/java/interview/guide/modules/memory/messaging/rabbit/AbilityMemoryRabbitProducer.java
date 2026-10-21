package interview.guide.modules.memory.messaging.rabbit;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.memory.messaging.AbilityMemoryDerivationTaskPublisher;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.ability-memory.messaging.provider", havingValue = "rabbitmq",
    matchIfMissing = true)
public class AbilityMemoryRabbitProducer implements AbilityMemoryDerivationTaskPublisher {
  private static final Duration CONFIRM_TIMEOUT = Duration.ofSeconds(5);

  private final RabbitTemplate template;
  private final AbilityMemoryRabbitProperties properties;

  public AbilityMemoryRabbitProducer(
      @Qualifier("abilityMemoryRabbitTemplate") RabbitTemplate template,
      AbilityMemoryRabbitProperties properties) {
    this.template = template;
    this.properties = properties;
  }

  @Override
  public void publish(String sessionId) {
    UUID id = UUID.randomUUID();
    send(properties.exchange(), properties.routingKey(),
        new AbilityMemoryDerivationMessage(id, sessionId, 0, OffsetDateTime.now(), id), null);
  }

  public void publishRetry(AbilityMemoryDerivationMessage previous,
      AbilityMemoryRetryPolicy.RetryDestination destination) {
    UUID id = UUID.randomUUID();
    send(properties.exchange(), destination.routingKey(),
        new AbilityMemoryDerivationMessage(id, previous.sessionId(), previous.retryCount() + 1,
            OffsetDateTime.now(), previous.originalMessageId()), null);
  }

  public void publishDead(AbilityMemoryDerivationMessage previous, String reason) {
    UUID id = UUID.randomUUID();
    send(properties.deadExchange(), properties.deadRoutingKey(),
        new AbilityMemoryDerivationMessage(id, previous.sessionId(), previous.retryCount(),
            OffsetDateTime.now(), previous.originalMessageId()), reason);
  }

  private void send(String exchange, String key, AbilityMemoryDerivationMessage task,
      String reason) {
    CorrelationData correlation = new CorrelationData(task.messageId().toString());
    template.convertAndSend(exchange, key, task, message -> {
      message.getMessageProperties().setDeliveryMode(MessageDeliveryMode.PERSISTENT);
      message.getMessageProperties().setMessageId(task.messageId().toString());
      if (reason != null) {
        message.getMessageProperties().setHeader("x-ability-memory-failure",
            reason.substring(0, Math.min(500, reason.length())));
      }
      return message;
    }, correlation);
    try {
      var confirm = correlation.getFuture().get(CONFIRM_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
      if (!confirm.ack() || correlation.getReturned() != null) {
        throw new BusinessException(ErrorCode.INTERNAL_ERROR, "RabbitMQ rejected ability derivation");
      }
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new BusinessException(ErrorCode.INTERNAL_ERROR,
          "Ability derivation confirmation interrupted", exception);
    } catch (ExecutionException | TimeoutException exception) {
      throw new BusinessException(ErrorCode.INTERNAL_ERROR,
          "Ability derivation confirmation failed", exception);
    }
  }
}
