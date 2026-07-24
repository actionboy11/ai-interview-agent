package interview.guide.modules.voiceinterview.messaging.rabbit;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.rabbitmq.client.Channel;
import interview.guide.modules.voiceinterview.deadletter.VoiceEvaluationDeadLetterService;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;

class VoiceEvaluationDeadLetterConsumerTest {
    @Test
    void shouldAckAfterAuditPersistence() throws Exception {
        VoiceEvaluationDeadLetterService service = mock(VoiceEvaluationDeadLetterService.class);
        VoiceEvaluationDeadLetterConsumer consumer =
            new VoiceEvaluationDeadLetterConsumer(service);
        UUID id = UUID.randomUUID();
        VoiceEvaluationMessage task =
            new VoiceEvaluationMessage(id, 42L, 3, OffsetDateTime.now(), id);
        MessageProperties properties = new MessageProperties();
        properties.setDeliveryTag(9L);
        properties.setHeader("x-voice-evaluation-failure", "AI unavailable");
        Message delivery = new Message(new byte[0], properties);
        Channel channel = mock(Channel.class);

        consumer.consume(task, delivery, channel);

        verify(service).record(task, "AI unavailable");
        verify(channel).basicAck(9L, false);
    }
}
