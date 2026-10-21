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
public class AbilityMemoryDeadLetterConsumer {
  private final AbilityEvidenceDerivationService derivation;

  @RabbitListener(queues = "${app.ability-memory.messaging.dead-queue:ability.memory.derivation.dead.queue}",
      containerFactory = "abilityMemoryRabbitListenerContainerFactory")
  public void consume(AbilityMemoryDerivationMessage task, Message envelope, Channel channel)
      throws IOException {
    long tag = envelope.getMessageProperties().getDeliveryTag();
    Object header = envelope.getMessageProperties().getHeaders().get("x-ability-memory-failure");
    try {
      derivation.recordFailure(task.sessionId(), task.messageId(),
          header == null ? null : header.toString());
      channel.basicAck(tag, false);
    } catch (Exception failure) {
      log.error("Unable to record dead ability derivation: sessionId={}",
          task.sessionId(), failure);
      channel.basicNack(tag, false, true);
    }
  }
}
