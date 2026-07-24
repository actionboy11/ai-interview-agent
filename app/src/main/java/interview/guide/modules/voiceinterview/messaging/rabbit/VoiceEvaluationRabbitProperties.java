package interview.guide.modules.voiceinterview.messaging.rabbit;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "app.voice-evaluation.messaging")
public record VoiceEvaluationRabbitProperties(
    @DefaultValue("rabbitmq") String provider,
    @DefaultValue("voice.evaluation.exchange") String exchange,
    @DefaultValue("voice.evaluation.queue") String queue,
    @DefaultValue("voice.evaluation") String routingKey,
    @DefaultValue("voice.evaluation.dead.exchange") String deadExchange,
    @DefaultValue("voice.evaluation.dead.queue") String deadQueue,
    @DefaultValue("voice.evaluation.dead") String deadRoutingKey,
    @DefaultValue({"10s", "30s", "60s"}) List<Duration> retryDelays
) {
}
