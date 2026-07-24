package interview.guide.modules.resume.messaging.rabbit;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "app.resume.messaging")
public record ResumeAnalysisRabbitProperties(
    @DefaultValue("redis-stream") String provider,
    @DefaultValue("resume.analysis.exchange") String exchange,
    @DefaultValue("resume.analysis.queue") String queue,
    @DefaultValue("resume.analysis") String routingKey,
    @DefaultValue("resume.analysis.dead.exchange") String deadExchange,
    @DefaultValue("resume.analysis.dead.queue") String deadQueue,
    @DefaultValue("resume.analysis.dead") String deadRoutingKey,
    @DefaultValue({"10s", "30s", "60s"}) List<Duration> retryDelays
) {
}
