package interview.guide.modules.voiceinterview.messaging.rabbit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;

class VoiceEvaluationRabbitConfigTest {
    private VoiceEvaluationRabbitConfig config;
    private VoiceEvaluationRabbitProperties properties;

    @BeforeEach
    void setUp() {
        config = new VoiceEvaluationRabbitConfig();
        properties = new VoiceEvaluationRabbitProperties(
            "rabbitmq",
            "voice.evaluation.exchange",
            "voice.evaluation.queue",
            "voice.evaluation",
            "voice.evaluation.dead.exchange",
            "voice.evaluation.dead.queue",
            "voice.evaluation.dead",
            List.of(Duration.ofSeconds(10), Duration.ofSeconds(30), Duration.ofSeconds(60))
        );
    }

    @Test
    void shouldDeclareDurableMainAndDeadResources() {
        DirectExchange main = config.voiceEvaluationExchange(properties);
        DirectExchange dead = config.voiceEvaluationDeadExchange(properties);
        Queue mainQueue = config.voiceEvaluationQueue(properties);
        Queue deadQueue = config.voiceEvaluationDeadQueue(properties);

        assertThat(main.isDurable()).isTrue();
        assertThat(dead.isDurable()).isTrue();
        assertThat(mainQueue.isDurable()).isTrue();
        assertThat(deadQueue.isDurable()).isTrue();
    }

    @Test
    void shouldDeclareThreeTtlRetryQueuesReturningToMainQueue() {
        Declarables topology = config.voiceEvaluationRetryTopology(
            config.voiceEvaluationExchange(properties),
            properties
        );
        List<Queue> queues = topology.getDeclarablesByType(Queue.class);

        assertThat(queues).hasSize(3);
        assertThat(queues)
            .extracting(queue -> queue.getArguments().get("x-message-ttl"))
            .containsExactly(10_000L, 30_000L, 60_000L);
        assertThat(queues).allSatisfy(queue -> {
            assertThat(queue.isDurable()).isTrue();
            assertThat(queue.getArguments().get("x-dead-letter-exchange"))
                .isEqualTo(properties.exchange());
            assertThat(queue.getArguments().get("x-dead-letter-routing-key"))
                .isEqualTo(properties.routingKey());
        });
    }

    @Test
    void shouldUseJacksonAndManualAcknowledgement() {
        var converter = config.voiceEvaluationMessageConverter();
        var factory = config.voiceEvaluationRabbitListenerContainerFactory(
            mock(ConnectionFactory.class),
            converter
        );

        assertThat(converter).isInstanceOf(JacksonJsonMessageConverter.class);
        assertThat(factory.createListenerContainer().getAcknowledgeMode())
            .isEqualTo(AcknowledgeMode.MANUAL);
    }
}
