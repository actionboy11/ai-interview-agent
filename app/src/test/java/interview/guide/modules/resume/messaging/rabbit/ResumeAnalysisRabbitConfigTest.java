package interview.guide.modules.resume.messaging.rabbit;

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

class ResumeAnalysisRabbitConfigTest {

    private ResumeAnalysisRabbitConfig config;
    private ResumeAnalysisRabbitProperties properties;

    @BeforeEach
    void setUp() {
        config = new ResumeAnalysisRabbitConfig();
        properties = new ResumeAnalysisRabbitProperties(
            "rabbitmq",
            "resume.analysis.exchange",
            "resume.analysis.queue",
            "resume.analysis",
            "resume.analysis.dead.exchange",
            "resume.analysis.dead.queue",
            "resume.analysis.dead",
            List.of(Duration.ofSeconds(10), Duration.ofSeconds(30), Duration.ofSeconds(60))
        );
    }

    @Test
    void shouldDeclareDurableMainAndDeadResources() {
        DirectExchange mainExchange = config.resumeAnalysisExchange(properties);
        DirectExchange deadExchange = config.resumeAnalysisDeadExchange(properties);
        Queue mainQueue = config.resumeAnalysisQueue(properties);
        Queue deadQueue = config.resumeAnalysisDeadQueue(properties);

        assertThat(mainExchange.isDurable()).isTrue();
        assertThat(deadExchange.isDurable()).isTrue();
        assertThat(mainQueue.isDurable()).isTrue();
        assertThat(deadQueue.isDurable()).isTrue();
    }

    @Test
    void shouldDeclareTtlRetryQueuesThatReturnToMainQueue() {
        DirectExchange exchange = config.resumeAnalysisExchange(properties);
        Declarables topology = config.resumeAnalysisRetryTopology(exchange, properties);
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
        var converter = config.resumeAnalysisMessageConverter();
        var factory = config.resumeAnalysisRabbitListenerContainerFactory(
            mock(ConnectionFactory.class),
            converter
        );

        assertThat(converter).isInstanceOf(JacksonJsonMessageConverter.class);
        assertThat(factory.createListenerContainer().getAcknowledgeMode())
            .isEqualTo(AcknowledgeMode.MANUAL);
    }
}
