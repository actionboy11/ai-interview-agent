package interview.guide.modules.resume.messaging.rabbit;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.resume.messaging.ResumeAnalysisTaskPublisher;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
    name = "app.resume.messaging.provider",
    havingValue = "rabbitmq"
)
public class ResumeAnalysisRabbitProducer implements ResumeAnalysisTaskPublisher {

    private static final Duration DEFAULT_CONFIRM_TIMEOUT = Duration.ofSeconds(5);

    private final RabbitTemplate rabbitTemplate;
    private final ResumeAnalysisRabbitProperties properties;
    private final Duration confirmTimeout;

    @Autowired
    public ResumeAnalysisRabbitProducer(
        RabbitTemplate resumeAnalysisRabbitTemplate,
        ResumeAnalysisRabbitProperties properties
    ) {
        this(resumeAnalysisRabbitTemplate, properties, DEFAULT_CONFIRM_TIMEOUT);
    }

    ResumeAnalysisRabbitProducer(
        RabbitTemplate rabbitTemplate,
        ResumeAnalysisRabbitProperties properties,
        Duration confirmTimeout
    ) {
        this.rabbitTemplate = rabbitTemplate;
        this.properties = properties;
        this.confirmTimeout = confirmTimeout;
    }

    @Override
    public PublishReceipt publish(Long resumeId) {
        UUID messageId = UUID.randomUUID();
        ResumeAnalysisMessage message = new ResumeAnalysisMessage(
            messageId,
            resumeId,
            0,
            OffsetDateTime.now(),
            messageId
        );
        publishConfirmed(properties.exchange(), properties.routingKey(), message, null);
        return new PublishReceipt(messageId);
    }

    public PublishReceipt publishRetry(
        ResumeAnalysisMessage failed,
        ResumeAnalysisRetryPolicy.RetryDestination destination
    ) {
        UUID messageId = UUID.randomUUID();
        ResumeAnalysisMessage retry = new ResumeAnalysisMessage(
            messageId,
            failed.resumeId(),
            failed.retryCount() + 1,
            OffsetDateTime.now(),
            failed.originalMessageId()
        );
        publishConfirmed(properties.exchange(), destination.routingKey(), retry, null);
        return new PublishReceipt(messageId);
    }

    public PublishReceipt publishDead(ResumeAnalysisMessage failed, String failureReason) {
        UUID messageId = UUID.randomUUID();
        ResumeAnalysisMessage dead = new ResumeAnalysisMessage(
            messageId,
            failed.resumeId(),
            failed.retryCount(),
            OffsetDateTime.now(),
            failed.originalMessageId()
        );
        publishConfirmed(
            properties.deadExchange(),
            properties.deadRoutingKey(),
            dead,
            truncateHeader(failureReason)
        );
        return new PublishReceipt(messageId);
    }

    private void publishConfirmed(
        String exchange,
        String routingKey,
        ResumeAnalysisMessage message,
        String failureReason
    ) {
        CorrelationData correlation = new CorrelationData(message.messageId().toString());
        rabbitTemplate.convertAndSend(
            exchange,
            routingKey,
            message,
            outbound -> {
                outbound.getMessageProperties().setDeliveryMode(MessageDeliveryMode.PERSISTENT);
                outbound.getMessageProperties().setMessageId(message.messageId().toString());
                if (failureReason != null) {
                    outbound.getMessageProperties().setHeader(
                        "x-resume-analysis-failure",
                        failureReason
                    );
                }
                return outbound;
            },
            correlation
        );

        try {
            CorrelationData.Confirm confirm =
                correlation.getFuture().get(confirmTimeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!confirm.ack()) {
                throw publishFailure("RabbitMQ rejected message: " + confirm.reason());
            }
            if (correlation.getReturned() != null) {
                throw publishFailure("RabbitMQ returned unroutable message");
            }
        } catch (BusinessException exception) {
            throw exception;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new BusinessException(
                ErrorCode.RESUME_ANALYSIS_FAILED,
                "RabbitMQ publish confirmation failed",
                exception
            );
        } catch (ExecutionException | TimeoutException exception) {
            throw new BusinessException(
                ErrorCode.RESUME_ANALYSIS_FAILED,
                "RabbitMQ publish confirmation failed",
                exception
            );
        }
    }

    private BusinessException publishFailure(String message) {
        return new BusinessException(ErrorCode.RESUME_ANALYSIS_FAILED, message);
    }

    private String truncateHeader(String value) {
        if (value == null || value.length() <= 500) {
            return value;
        }
        return value.substring(0, 500);
    }
}
