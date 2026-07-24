package interview.guide.modules.resume.messaging.rabbit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

class ResumeAnalysisRabbitProducerContextTest {

    @Test
    void shouldSelectProductionConstructorDuringSpringBeanCreation() {
        try (var context = new AnnotationConfigApplicationContext()) {
            TestPropertyValues.of("app.resume.messaging.provider=rabbitmq").applyTo(context);
            context.registerBean(RabbitTemplate.class, () -> mock(RabbitTemplate.class));
            context.registerBean(
                ResumeAnalysisRabbitProperties.class,
                () -> new ResumeAnalysisRabbitProperties(
                    "rabbitmq",
                    "resume.analysis.exchange",
                    "resume.analysis.queue",
                    "resume.analysis",
                    "resume.analysis.dead.exchange",
                    "resume.analysis.dead.queue",
                    "resume.analysis.dead",
                    List.of(
                        Duration.ofSeconds(10),
                        Duration.ofSeconds(30),
                        Duration.ofSeconds(60)
                    )
                )
            );
            context.register(ResumeAnalysisRabbitProducer.class);

            context.refresh();

            assertThat(context.getBean(ResumeAnalysisRabbitProducer.class)).isNotNull();
        }
    }
}
