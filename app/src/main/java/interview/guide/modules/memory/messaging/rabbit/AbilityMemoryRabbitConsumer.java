package interview.guide.modules.memory.messaging.rabbit;

import com.rabbitmq.client.Channel;
import interview.guide.modules.memory.service.AbilityEvidenceDerivationService;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.ability-memory.messaging.provider", havingValue = "rabbitmq",
    matchIfMissing = true)
public class AbilityMemoryRabbitConsumer {
  private final AbilityEvidenceDerivationService derivation;
  private final AbilityMemoryRabbitProducer producer;
  private final AbilityMemoryRetryPolicy retryPolicy;

  @RabbitListener(queues = "${app.ability-memory.messaging.queue:ability.memory.derivation.queue}",
      containerFactory = "abilityMemoryRabbitListenerContainerFactory")
  public void consume(AbilityMemoryDerivationMessage task, Message envelope, Channel channel)
      throws IOException {
    long tag = envelope.getMessageProperties().getDeliveryTag();
    try {
      derivation.derive(task.sessionId(), task.messageId());
      channel.basicAck(tag, false);
    } catch (Exception failure) {
      String reason = failure.getMessage() == null ? "Ability derivation failed"
          : failure.getMessage();
      try {
        derivation.recordFailure(task.sessionId(), task.messageId(), reason);
        var destination = retryPolicy.destinationFor(task.retryCount());
        if (destination.deadLetter()) {
          producer.publishDead(task, reason);
        } else {
          producer.publishRetry(task, destination);
        }
        channel.basicAck(tag, false);
      } catch (Exception rerouteFailure) {
        log.error("Unable to reroute ability derivation: sessionId={}",
            task.sessionId(), rerouteFailure);
        channel.basicNack(tag, false, true);
      }
    }
  }
}
