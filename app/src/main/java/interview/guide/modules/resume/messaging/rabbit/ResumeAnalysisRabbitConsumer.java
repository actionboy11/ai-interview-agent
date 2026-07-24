package interview.guide.modules.resume.messaging.rabbit;

import com.rabbitmq.client.Channel;
import interview.guide.common.model.AsyncTaskStatus;
import interview.guide.modules.interview.model.ResumeAnalysisResponse;
import interview.guide.modules.resume.model.ResumeEntity;
import interview.guide.modules.resume.service.ResumeGradingService;
import interview.guide.modules.resume.service.ResumePersistenceService;
import java.io.IOException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@ConditionalOnProperty(name = "app.resume.messaging.provider", havingValue = "rabbitmq")
public class ResumeAnalysisRabbitConsumer {

    private final ResumeGradingService gradingService;
    private final ResumePersistenceService persistenceService;
    private final ResumeAnalysisRabbitProducer producer;
    private final ResumeAnalysisRetryPolicy retryPolicy;

    public ResumeAnalysisRabbitConsumer(
        ResumeGradingService gradingService,
        ResumePersistenceService persistenceService,
        ResumeAnalysisRabbitProducer producer,
        ResumeAnalysisRetryPolicy retryPolicy
    ) {
        this.gradingService = gradingService;
        this.persistenceService = persistenceService;
        this.producer = producer;
        this.retryPolicy = retryPolicy;
    }

    @RabbitListener(
        queues = "${app.resume.messaging.queue}",
        containerFactory = "resumeAnalysisRabbitListenerContainerFactory"
    )
    public void consume(
        ResumeAnalysisMessage task,
        Message message,
        Channel channel
    ) throws IOException {
        long deliveryTag = message.getMessageProperties().getDeliveryTag();
        if (task == null) {
            channel.basicNack(deliveryTag, false, false);
            return;
        }

        ResumeEntity resume = persistenceService.findById(task.resumeId()).orElse(null);
        if (resume == null
            || resume.getAnalyzeStatus() == AsyncTaskStatus.COMPLETED
            || persistenceService.isAnalysisMessageCompleted(task.messageId().toString())) {
            channel.basicAck(deliveryTag, false);
            return;
        }

        try {
            persistenceService.markAnalysisProcessing(task.resumeId());
            ResumeAnalysisResponse analysis = gradingService.analyzeResume(resume.getResumeText());
            persistenceService.completeAnalysis(
                task.resumeId(),
                task.messageId().toString(),
                analysis
            );
            channel.basicAck(deliveryTag, false);
        } catch (Exception failure) {
            handleFailure(task, deliveryTag, channel, failure);
        }
    }

    private void handleFailure(
        ResumeAnalysisMessage task,
        long deliveryTag,
        Channel channel,
        Exception failure
    ) throws IOException {
        try {
            ResumeAnalysisRetryPolicy.RetryDestination destination =
                retryPolicy.destinationFor(task.retryCount());
            if (destination.deadLetter()) {
                persistenceService.markAnalysisFailed(task.resumeId(), failure.getMessage());
                producer.publishDead(task, failure.getMessage());
            } else {
                producer.publishRetry(task, destination);
            }
            channel.basicAck(deliveryTag, false);
        } catch (Exception publishFailure) {
            log.error(
                "Failed to route resume analysis message: messageId={}",
                task.messageId(),
                publishFailure
            );
            channel.basicNack(deliveryTag, false, true);
        }
    }
}
