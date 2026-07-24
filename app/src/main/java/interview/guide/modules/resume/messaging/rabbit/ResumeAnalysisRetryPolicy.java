package interview.guide.modules.resume.messaging.rabbit;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
    name = "app.resume.messaging.provider",
    havingValue = "rabbitmq"
)
public class ResumeAnalysisRetryPolicy {

    private final ResumeAnalysisRabbitProperties properties;

    public ResumeAnalysisRetryPolicy(ResumeAnalysisRabbitProperties properties) {
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

    static String retryRoutingKey(String mainRoutingKey, Duration delay) {
        return mainRoutingKey + ".retry." + delay.toSeconds() + "s";
    }

    public record RetryDestination(String routingKey, Duration delay, boolean deadLetter) {
    }
}
