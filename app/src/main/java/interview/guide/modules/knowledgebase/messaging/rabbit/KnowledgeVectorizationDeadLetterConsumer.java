package interview.guide.modules.knowledgebase.messaging.rabbit;

import com.rabbitmq.client.Channel;
import interview.guide.modules.knowledgebase.deadletter.KnowledgeVectorizationDeadLetterService;
import java.io.IOException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
    name = "app.knowledge-vectorization.messaging.provider",
    havingValue = "rabbitmq",
    matchIfMissing = true
)
public class KnowledgeVectorizationDeadLetterConsumer {
    private final KnowledgeVectorizationDeadLetterService service;

    public KnowledgeVectorizationDeadLetterConsumer(KnowledgeVectorizationDeadLetterService service) {
        this.service = service;
    }

    @RabbitListener(
        queues = "${app.knowledge-vectorization.messaging.dead-queue:"
            + "knowledge.vectorization.dead.queue}",
        containerFactory = "knowledgeVectorizationRabbitListenerContainerFactory"
    )
    public void consume(
        KnowledgeVectorizationMessage task,
        Message message,
        Channel channel
    ) throws IOException {
        long tag = message.getMessageProperties().getDeliveryTag();
        Object header = message.getMessageProperties()
            .getHeaders()
            .get("x-knowledge-vectorization-failure");
        try {
            service.record(task, header == null ? null : header.toString());
            channel.basicAck(tag, false);
        } catch (Exception exception) {
            channel.basicNack(tag, false, true);
        }
    }
}
