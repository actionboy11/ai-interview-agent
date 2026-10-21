package interview.guide.modules.memory.messaging.rabbit;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "app.ability-memory.messaging")
public record AbilityMemoryRabbitProperties(
    @DefaultValue("rabbitmq") String provider,
    @DefaultValue("ability.memory.derivation.exchange") String exchange,
    @DefaultValue("ability.memory.derivation.queue") String queue,
    @DefaultValue("ability.memory.derivation") String routingKey,
    @DefaultValue("ability.memory.derivation.dead.exchange") String deadExchange,
    @DefaultValue("ability.memory.derivation.dead.queue") String deadQueue,
    @DefaultValue("ability.memory.derivation.dead") String deadRoutingKey,
    @DefaultValue({"10s", "30s", "60s"}) List<Duration> retryDelays
) {
}
