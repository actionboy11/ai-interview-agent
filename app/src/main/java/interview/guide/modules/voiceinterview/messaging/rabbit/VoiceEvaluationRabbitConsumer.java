package interview.guide.modules.voiceinterview.messaging.rabbit;

import com.rabbitmq.client.Channel;
import interview.guide.common.model.AsyncTaskStatus;
import interview.guide.modules.voiceinterview.model.VoiceInterviewSessionEntity;
import interview.guide.modules.voiceinterview.repository.VoiceInterviewSessionRepository;
import interview.guide.modules.voiceinterview.service.VoiceInterviewEvaluationService;
import interview.guide.modules.voiceinterview.service.VoiceInterviewService;
import java.io.IOException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@ConditionalOnProperty(
    name = "app.voice-evaluation.messaging.provider",
    havingValue = "rabbitmq",
    matchIfMissing = true
)
public class VoiceEvaluationRabbitConsumer {
    private final VoiceInterviewSessionRepository sessionRepository;
    private final VoiceInterviewService voiceInterviewService;
    private final VoiceInterviewEvaluationService evaluationService;
    private final VoiceEvaluationRabbitProducer producer;
    private final VoiceEvaluationRetryPolicy retryPolicy;

    public VoiceEvaluationRabbitConsumer(
        VoiceInterviewSessionRepository sessionRepository,
        VoiceInterviewService voiceInterviewService,
        VoiceInterviewEvaluationService evaluationService,
        VoiceEvaluationRabbitProducer producer,
        VoiceEvaluationRetryPolicy retryPolicy
    ) {
        this.sessionRepository = sessionRepository;
        this.voiceInterviewService = voiceInterviewService;
        this.evaluationService = evaluationService;
        this.producer = producer;
        this.retryPolicy = retryPolicy;
    }

    @RabbitListener(
        queues = "${app.voice-evaluation.messaging.queue}",
        containerFactory = "voiceEvaluationRabbitListenerContainerFactory"
    )
    public void consume(
        VoiceEvaluationMessage task,
        Message message,
        Channel channel
    ) throws IOException {
        long deliveryTag = message.getMessageProperties().getDeliveryTag();
        if (task == null) {
            channel.basicNack(deliveryTag, false, false);
            return;
        }

        VoiceInterviewSessionEntity session =
            sessionRepository.findById(task.sessionId()).orElse(null);
        if (session == null || session.getEvaluateStatus() == AsyncTaskStatus.COMPLETED) {
            channel.basicAck(deliveryTag, false);
            return;
        }

        try {
            voiceInterviewService.updateEvaluateStatus(
                task.sessionId(),
                AsyncTaskStatus.PROCESSING,
                null
            );
            evaluationService.generateEvaluation(
                task.sessionId(),
                task.messageId().toString()
            );
            channel.basicAck(deliveryTag, false);
        } catch (Exception failure) {
            handleFailure(task, deliveryTag, channel, failure);
        }
    }

    private void handleFailure(
        VoiceEvaluationMessage task,
        long deliveryTag,
        Channel channel,
        Exception failure
    ) throws IOException {
        try {
            VoiceEvaluationRetryPolicy.RetryDestination destination =
                retryPolicy.destinationFor(task.retryCount());
            if (destination.deadLetter()) {
                voiceInterviewService.updateEvaluateStatus(
                    task.sessionId(),
                    AsyncTaskStatus.FAILED,
                    truncate(failure.getMessage())
                );
                producer.publishDead(task, failure.getMessage());
            } else {
                producer.publishRetry(task, destination);
            }
            channel.basicAck(deliveryTag, false);
        } catch (Exception publishFailure) {
            log.error(
                "Failed to route voice evaluation message: messageId={}",
                task.messageId(),
                publishFailure
            );
            channel.basicNack(deliveryTag, false, true);
        }
    }

    private String truncate(String value) {
        if (value == null || value.length() <= 500) {
            return value;
        }
        return value.substring(0, 500);
    }
}
