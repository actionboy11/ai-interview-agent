package interview.guide.modules.voiceinterview.messaging.rabbit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

class VoiceEvaluationRabbitProducerContextTest {
    @Test
    void shouldSelectProductionConstructorDuringSpringBeanCreation() {
        try (var context = new AnnotationConfigApplicationContext()) {
            TestPropertyValues.of(
                "app.voice-evaluation.messaging.provider=rabbitmq"
            ).applyTo(context);
            context.registerBean(RabbitTemplate.class, () -> mock(RabbitTemplate.class));
            context.registerBean(
                VoiceEvaluationRabbitProperties.class,
                () -> new VoiceEvaluationRabbitProperties(
                    "rabbitmq",
                    "voice.evaluation.exchange",
                    "voice.evaluation.queue",
                    "voice.evaluation",
                    "voice.evaluation.dead.exchange",
                    "voice.evaluation.dead.queue",
                    "voice.evaluation.dead",
                    List.of(
                        Duration.ofSeconds(10),
                        Duration.ofSeconds(30),
                        Duration.ofSeconds(60)
                    )
                )
            );
            context.register(VoiceEvaluationRabbitProducer.class);

            context.refresh();

            assertThat(context.getBean(VoiceEvaluationRabbitProducer.class)).isNotNull();
        }
    }
}
