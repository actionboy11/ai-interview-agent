package interview.guide.modules.knowledgebase.messaging.rabbit;

import java.time.Duration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
    name = "app.knowledge-vectorization.messaging.provider",
    havingValue = "rabbitmq",
    matchIfMissing = true
)
public class KnowledgeVectorizationRetryPolicy {

  private final KnowledgeVectorizationRabbitProperties properties;

  public KnowledgeVectorizationRetryPolicy(KnowledgeVectorizationRabbitProperties properties) {
    this.properties = properties;
  }

  public RetryDestination destinationFor(int retryCount) {
    if (retryCount < 0) {
      throw new IllegalArgumentException("retryCount must not be negative");
    }
    if (retryCount >= properties.retryDelays().size()) {
      return new RetryDestination(properties.deadRoutingKey(), Duration.ZERO, true);
    }
    Duration delay = properties.retryDelays().get(retryCount);
    return new RetryDestination(retryRoutingKey(properties.routingKey(), delay), delay, false);
  }

  static String retryRoutingKey(String routingKey, Duration delay) {
    return routingKey + ".retry." + delay.toSeconds() + "s";
  }

  public record RetryDestination(String routingKey, Duration delay, boolean deadLetter) {
  }
}
