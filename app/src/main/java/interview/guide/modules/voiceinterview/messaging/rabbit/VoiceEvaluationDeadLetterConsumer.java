package interview.guide.modules.voiceinterview.messaging.rabbit;

import com.rabbitmq.client.Channel;
import interview.guide.modules.voiceinterview.deadletter.VoiceEvaluationDeadLetterService;
import java.io.IOException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
    name = "app.voice-evaluation.messaging.provider",
    havingValue = "rabbitmq",
    matchIfMissing = true
)
public class VoiceEvaluationDeadLetterConsumer {
    private final VoiceEvaluationDeadLetterService service;

    public VoiceEvaluationDeadLetterConsumer(VoiceEvaluationDeadLetterService service) {
        this.service = service;
    }

    @RabbitListener(
        queues = "${app.voice-evaluation.messaging.dead-queue}",
        containerFactory = "voiceEvaluationRabbitListenerContainerFactory"
    )
    public void consume(
        VoiceEvaluationMessage task,
        Message message,
        Channel channel
    ) throws IOException {
        long deliveryTag = message.getMessageProperties().getDeliveryTag();
        Object header = message.getMessageProperties()
            .getHeaders()
            .get("x-voice-evaluation-failure");
        try {
            service.record(task, header == null ? null : header.toString());
            channel.basicAck(deliveryTag, false);
        } catch (Exception exception) {
            channel.basicNack(deliveryTag, false, true);
        }
    }
}
