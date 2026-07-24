package interview.guide.modules.resume.messaging.rabbit;

import com.rabbitmq.client.Channel;
import interview.guide.modules.resume.deadletter.ResumeAnalysisDeadLetterService;
import java.io.IOException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.resume.messaging.provider", havingValue = "rabbitmq")
public class ResumeAnalysisDeadLetterConsumer {
    private final ResumeAnalysisDeadLetterService service;

    public ResumeAnalysisDeadLetterConsumer(ResumeAnalysisDeadLetterService service) {
        this.service = service;
    }

    @RabbitListener(
        queues = "${app.resume.messaging.dead-queue}",
        containerFactory = "resumeAnalysisRabbitListenerContainerFactory"
    )
    public void consume(
        ResumeAnalysisMessage task,
        Message message,
        Channel channel
    ) throws IOException {
        long tag = message.getMessageProperties().getDeliveryTag();
        Object header = message.getMessageProperties()
            .getHeaders()
            .get("x-resume-analysis-failure");
        try {
            service.record(task, header == null ? null : header.toString());
            channel.basicAck(tag, false);
        } catch (Exception exception) {
            channel.basicNack(tag, false, true);
        }
    }
}
