package interview.guide.modules.resume.messaging.rabbit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import interview.guide.common.exception.BusinessException;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

class ResumeAnalysisRabbitProducerTest {

    private RabbitTemplate rabbitTemplate;
    private ResumeAnalysisRabbitProducer producer;

    @BeforeEach
    void setUp() {
        rabbitTemplate = mock(RabbitTemplate.class);
        var properties = new ResumeAnalysisRabbitProperties(
            "rabbitmq",
            "resume.analysis.exchange",
            "resume.analysis.queue",
            "resume.analysis",
            "resume.analysis.dead.exchange",
            "resume.analysis.dead.queue",
            "resume.analysis.dead",
            List.of(Duration.ofSeconds(10), Duration.ofSeconds(30), Duration.ofSeconds(60))
        );
        producer = new ResumeAnalysisRabbitProducer(rabbitTemplate, properties, Duration.ofSeconds(1));
    }

    @Test
    void shouldPublishPersistentMessageAfterBrokerAck() {
        doAnswer(invocation -> {
            CorrelationData correlation = invocation.getArgument(4);
            correlation.getFuture().complete(new CorrelationData.Confirm(true, null));
            return null;
        }).when(rabbitTemplate).convertAndSend(
            eq("resume.analysis.exchange"),
            eq("resume.analysis"),
            any(),
            any(),
            any(CorrelationData.class)
        );

        var receipt = producer.publish(42L);

        ArgumentCaptor<ResumeAnalysisMessage> messageCaptor =
            ArgumentCaptor.forClass(ResumeAnalysisMessage.class);
        verify(rabbitTemplate).convertAndSend(
            eq("resume.analysis.exchange"),
            eq("resume.analysis"),
            messageCaptor.capture(),
            any(),
            any(CorrelationData.class)
        );
        assertThat(receipt.messageId()).isEqualTo(messageCaptor.getValue().messageId());
    }

    @Test
    void shouldFailWhenBrokerNacks() {
        doAnswer(invocation -> {
            CorrelationData correlation = invocation.getArgument(4);
            correlation.getFuture().complete(new CorrelationData.Confirm(false, "rejected"));
            return null;
        }).when(rabbitTemplate).convertAndSend(any(), any(), any(), any(), any(CorrelationData.class));

        assertThatThrownBy(() -> producer.publish(42L))
            .isInstanceOf(BusinessException.class);
    }
}
