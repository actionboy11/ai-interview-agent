package interview.guide.modules.interview.messaging.rabbit;

import java.time.Duration;
import org.springframework.stereotype.Component;

@Component
public class InterviewEvaluationRetryPolicy {

  private final InterviewEvaluationRabbitProperties properties;

  public InterviewEvaluationRetryPolicy(InterviewEvaluationRabbitProperties properties) {
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
