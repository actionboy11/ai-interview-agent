package interview.guide.modules.voiceinterview.messaging.rabbit;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.voiceinterview.messaging.VoiceEvaluationTaskPublisher;
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
    name = "app.voice-evaluation.messaging.provider",
    havingValue = "rabbitmq",
    matchIfMissing = true
)
public class VoiceEvaluationRabbitProducer implements VoiceEvaluationTaskPublisher {
    private static final Duration DEFAULT_CONFIRM_TIMEOUT = Duration.ofSeconds(5);

    private final RabbitTemplate rabbitTemplate;
    private final VoiceEvaluationRabbitProperties properties;
    private final Duration confirmTimeout;

    @Autowired
    public VoiceEvaluationRabbitProducer(
        RabbitTemplate voiceEvaluationRabbitTemplate,
        VoiceEvaluationRabbitProperties properties
    ) {
        this(voiceEvaluationRabbitTemplate, properties, DEFAULT_CONFIRM_TIMEOUT);
    }

    VoiceEvaluationRabbitProducer(
        RabbitTemplate rabbitTemplate,
        VoiceEvaluationRabbitProperties properties,
        Duration confirmTimeout
    ) {
        this.rabbitTemplate = rabbitTemplate;
        this.properties = properties;
        this.confirmTimeout = confirmTimeout;
    }

    @Override
    public PublishReceipt publish(Long sessionId) {
        UUID messageId = UUID.randomUUID();
        VoiceEvaluationMessage message = new VoiceEvaluationMessage(
            messageId,
            sessionId,
            0,
            OffsetDateTime.now(),
            messageId
        );
        publishConfirmed(properties.exchange(), properties.routingKey(), message, null);
        return new PublishReceipt(messageId);
    }

    private void publishConfirmed(
        String exchange,
        String routingKey,
        VoiceEvaluationMessage message,
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
                        "x-voice-evaluation-failure",
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
                throw publishFailure("RabbitMQ rejected voice evaluation: " + confirm.reason());
            }
            if (correlation.getReturned() != null) {
                throw publishFailure("RabbitMQ returned unroutable voice evaluation");
            }
        } catch (BusinessException exception) {
            throw exception;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw confirmFailure(exception);
        } catch (ExecutionException | TimeoutException exception) {
            throw confirmFailure(exception);
        }
    }

    private BusinessException publishFailure(String message) {
        return new BusinessException(ErrorCode.VOICE_EVALUATION_FAILED, message);
    }

    private BusinessException confirmFailure(Exception exception) {
        return new BusinessException(
            ErrorCode.VOICE_EVALUATION_FAILED,
            "RabbitMQ voice evaluation confirmation failed",
            exception
        );
    }
}
