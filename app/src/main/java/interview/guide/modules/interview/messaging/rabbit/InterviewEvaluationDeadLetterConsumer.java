package interview.guide.modules.interview.messaging.rabbit;

import com.rabbitmq.client.Channel;
import interview.guide.modules.interview.deadletter.InterviewEvaluationDeadLetterService;
import java.io.IOException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
    name = "app.interview-evaluation.messaging.provider",
    havingValue = "rabbitmq",
    matchIfMissing = true
)
public class InterviewEvaluationDeadLetterConsumer {
    private final InterviewEvaluationDeadLetterService service;

    public InterviewEvaluationDeadLetterConsumer(InterviewEvaluationDeadLetterService service) {
        this.service = service;
    }

    @RabbitListener(
        queues = "${app.interview-evaluation.messaging.dead-queue}",
        containerFactory = "interviewEvaluationRabbitListenerContainerFactory"
    )
    public void consume(
        InterviewEvaluationMessage task,
        Message message,
        Channel channel
    ) throws IOException {
        long deliveryTag = message.getMessageProperties().getDeliveryTag();
        Object header = message.getMessageProperties()
            .getHeaders()
            .get("x-interview-evaluation-failure");
        try {
            service.record(task, header == null ? null : header.toString());
            channel.basicAck(deliveryTag, false);
        } catch (Exception exception) {
            channel.basicNack(deliveryTag, false, true);
        }
    }
}

