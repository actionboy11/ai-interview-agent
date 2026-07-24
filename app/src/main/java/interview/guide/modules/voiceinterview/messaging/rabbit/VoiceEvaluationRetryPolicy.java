package interview.guide.modules.voiceinterview.messaging.rabbit;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
    name = "app.voice-evaluation.messaging.provider",
    havingValue = "rabbitmq",
    matchIfMissing = true
)
public class VoiceEvaluationRetryPolicy {
    private final VoiceEvaluationRabbitProperties properties;

    public VoiceEvaluationRetryPolicy(VoiceEvaluationRabbitProperties properties) {
        this.properties = properties;
    }

    public RetryDestination destinationFor(int retryCount) {
        if (retryCount < 0) {
            throw new IllegalArgumentException("retryCount must not be negative");
        }
        List<Duration> delays = properties.retryDelays();
        if (retryCount >= delays.size()) {
            return new RetryDestination(properties.deadRoutingKey(), Duration.ZERO, true);
        }
        Duration delay = delays.get(retryCount);
        return new RetryDestination(retryRoutingKey(properties.routingKey(), delay), delay, false);
    }

    static String retryRoutingKey(String routingKey, Duration delay) {
        return routingKey + ".retry." + delay.toSeconds() + "s";
    }

    public record RetryDestination(String routingKey, Duration delay, boolean deadLetter) {
    }
}
