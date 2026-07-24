package interview.guide.modules.voiceinterview.messaging.rabbit;

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

class VoiceEvaluationRabbitProducerTest {
    private RabbitTemplate rabbitTemplate;
    private VoiceEvaluationRabbitProducer producer;

    @BeforeEach
    void setUp() {
        rabbitTemplate = mock(RabbitTemplate.class);
        var properties = new VoiceEvaluationRabbitProperties(
            "rabbitmq",
            "voice.evaluation.exchange",
            "voice.evaluation.queue",
            "voice.evaluation",
            "voice.evaluation.dead.exchange",
            "voice.evaluation.dead.queue",
            "voice.evaluation.dead",
            List.of(Duration.ofSeconds(10), Duration.ofSeconds(30), Duration.ofSeconds(60))
        );
        producer = new VoiceEvaluationRabbitProducer(
            rabbitTemplate,
            properties,
            Duration.ofSeconds(1)
        );
    }

    @Test
    void shouldPublishAfterBrokerAck() {
        doAnswer(invocation -> {
            CorrelationData correlation = invocation.getArgument(4);
            correlation.getFuture().complete(new CorrelationData.Confirm(true, null));
            return null;
        }).when(rabbitTemplate).convertAndSend(
            eq("voice.evaluation.exchange"),
            eq("voice.evaluation"),
            any(),
            any(),
            any(CorrelationData.class)
        );

        var receipt = producer.publish(42L);

        ArgumentCaptor<VoiceEvaluationMessage> messageCaptor =
            ArgumentCaptor.forClass(VoiceEvaluationMessage.class);
        verify(rabbitTemplate).convertAndSend(
            eq("voice.evaluation.exchange"),
            eq("voice.evaluation"),
            messageCaptor.capture(),
            any(),
            any(CorrelationData.class)
        );
        assertThat(receipt.messageId()).isEqualTo(messageCaptor.getValue().messageId());
    }

    @Test
    void shouldFailAfterBrokerNack() {
        doAnswer(invocation -> {
            CorrelationData correlation = invocation.getArgument(4);
            correlation.getFuture().complete(new CorrelationData.Confirm(false, "rejected"));
            return null;
        }).when(rabbitTemplate).convertAndSend(any(), any(), any(), any(), any(CorrelationData.class));

        assertThatThrownBy(() -> producer.publish(42L))
            .isInstanceOf(BusinessException.class);
    }
}
