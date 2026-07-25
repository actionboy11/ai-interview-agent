package interview.guide.modules.knowledgebase.messaging.rabbit;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "app.knowledge-vectorization.messaging")
public record KnowledgeVectorizationRabbitProperties(
    @DefaultValue("rabbitmq") String provider,
    @DefaultValue("knowledge.vectorization.exchange") String exchange,
    @DefaultValue("knowledge.vectorization.queue") String queue,
    @DefaultValue("knowledge.vectorization") String routingKey,
    @DefaultValue("knowledge.vectorization.dead.exchange") String deadExchange,
    @DefaultValue("knowledge.vectorization.dead.queue") String deadQueue,
    @DefaultValue("knowledge.vectorization.dead") String deadRoutingKey,
    @DefaultValue({"10s", "30s", "60s"}) List<Duration> retryDelays
) {
}
