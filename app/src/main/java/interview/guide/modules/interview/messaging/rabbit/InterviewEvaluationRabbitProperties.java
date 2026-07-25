package interview.guide.modules.interview.messaging.rabbit;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "app.interview-evaluation.messaging")
public record InterviewEvaluationRabbitProperties(
    @DefaultValue("rabbitmq") String provider,
    @DefaultValue("interview.evaluation.exchange") String exchange,
    @DefaultValue("interview.evaluation.queue") String queue,
    @DefaultValue("interview.evaluation") String routingKey,
    @DefaultValue("interview.evaluation.dead.exchange") String deadExchange,
    @DefaultValue("interview.evaluation.dead.queue") String deadQueue,
    @DefaultValue("interview.evaluation.dead") String deadRoutingKey,
    @DefaultValue({"10s", "30s", "60s"}) List<Duration> retryDelays
) {
}
