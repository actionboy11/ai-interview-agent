package interview.guide.modules.memory.messaging.rabbit;

import java.time.Duration;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.ability-memory.messaging.provider", havingValue = "rabbitmq",
    matchIfMissing = true)
public class AbilityMemoryRetryPolicy {
  private final AbilityMemoryRabbitProperties properties;

  public RetryDestination destinationFor(int retryCount) {
    if (retryCount < 0) {
      throw new IllegalArgumentException("retryCount must not be negative");
    }
    if (retryCount >= properties.retryDelays().size()) {
      return new RetryDestination(properties.deadRoutingKey(), true);
    }
    return new RetryDestination(retryRoutingKey(properties.routingKey(),
        properties.retryDelays().get(retryCount)), false);
  }

  public static String retryRoutingKey(String routingKey, Duration delay) {
    return routingKey + ".retry." + delay.toSeconds() + "s";
  }

  public record RetryDestination(String routingKey, boolean deadLetter) {
  }
}
