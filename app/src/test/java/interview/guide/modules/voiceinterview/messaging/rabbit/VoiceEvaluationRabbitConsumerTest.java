package interview.guide.modules.voiceinterview.messaging.rabbit;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.rabbitmq.client.Channel;
import interview.guide.common.model.AsyncTaskStatus;
import interview.guide.modules.voiceinterview.model.VoiceInterviewSessionEntity;
import interview.guide.modules.voiceinterview.repository.VoiceInterviewSessionRepository;
import interview.guide.modules.voiceinterview.service.VoiceInterviewEvaluationService;
import interview.guide.modules.voiceinterview.service.VoiceInterviewService;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;

class VoiceEvaluationRabbitConsumerTest {
    private VoiceInterviewSessionRepository sessionRepository;
    private VoiceInterviewService voiceInterviewService;
    private VoiceInterviewEvaluationService evaluationService;
    private VoiceEvaluationRabbitProducer producer;
    private VoiceEvaluationRetryPolicy retryPolicy;
    private VoiceEvaluationRabbitConsumer consumer;
    private Channel channel;
    private Message delivery;

    @BeforeEach
    void setUp() {
        sessionRepository = mock(VoiceInterviewSessionRepository.class);
        voiceInterviewService = mock(VoiceInterviewService.class);
        evaluationService = mock(VoiceInterviewEvaluationService.class);
        producer = mock(VoiceEvaluationRabbitProducer.class);
        retryPolicy = mock(VoiceEvaluationRetryPolicy.class);
        consumer = new VoiceEvaluationRabbitConsumer(
            sessionRepository,
            voiceInterviewService,
            evaluationService,
            producer,
            retryPolicy
        );
        channel = mock(Channel.class);
        MessageProperties properties = new MessageProperties();
        properties.setDeliveryTag(7L);
        delivery = new Message(new byte[0], properties);
    }

    @Test
    void shouldAckMissingSession() throws Exception {
        VoiceEvaluationMessage task = task(0);
        when(sessionRepository.findById(42L)).thenReturn(Optional.empty());

        consumer.consume(task, delivery, channel);

        verify(channel).basicAck(7L, false);
        verify(evaluationService, never()).generateEvaluation(42L, task.messageId().toString());
    }

    @Test
    void shouldCompleteAndAckSuccessfulEvaluation() throws Exception {
        VoiceEvaluationMessage task = task(0);
        VoiceInterviewSessionEntity session =
            VoiceInterviewSessionEntity.builder().id(42L).build();
        when(sessionRepository.findById(42L)).thenReturn(Optional.of(session));

        consumer.consume(task, delivery, channel);

        verify(voiceInterviewService).updateEvaluateStatus(
            42L,
            AsyncTaskStatus.PROCESSING,
            null
        );
        verify(evaluationService).generateEvaluation(42L, task.messageId().toString());
        verify(channel).basicAck(7L, false);
    }

    @Test
    void shouldPublishRetryThenAckAfterFailure() throws Exception {
        VoiceEvaluationMessage task = task(0);
        VoiceInterviewSessionEntity session =
            VoiceInterviewSessionEntity.builder().id(42L).build();
        var destination = new VoiceEvaluationRetryPolicy.RetryDestination(
            "voice.evaluation.retry.10s",
            Duration.ofSeconds(10),
            false
        );
        when(sessionRepository.findById(42L)).thenReturn(Optional.of(session));
        when(evaluationService.generateEvaluation(42L, task.messageId().toString()))
            .thenThrow(new RuntimeException("AI down"));
        when(retryPolicy.destinationFor(0)).thenReturn(destination);

        consumer.consume(task, delivery, channel);

        verify(producer).publishRetry(task, destination);
        verify(channel).basicAck(7L, false);
    }

    @Test
    void shouldNackWhenRetryPublishFails() throws Exception {
        VoiceEvaluationMessage task = task(0);
        VoiceInterviewSessionEntity session =
            VoiceInterviewSessionEntity.builder().id(42L).build();
        var destination = new VoiceEvaluationRetryPolicy.RetryDestination(
            "voice.evaluation.retry.10s",
            Duration.ofSeconds(10),
            false
        );
        when(sessionRepository.findById(42L)).thenReturn(Optional.of(session));
        when(evaluationService.generateEvaluation(42L, task.messageId().toString()))
            .thenThrow(new RuntimeException("AI down"));
        when(retryPolicy.destinationFor(0)).thenReturn(destination);
        when(producer.publishRetry(task, destination))
            .thenThrow(new RuntimeException("broker down"));

        consumer.consume(task, delivery, channel);

        verify(channel).basicNack(7L, false, true);
        verify(channel, never()).basicAck(7L, false);
    }

    @Test
    void shouldPublishDeadAndAckAfterFinalFailure() throws Exception {
        VoiceEvaluationMessage task = task(3);
        VoiceInterviewSessionEntity session =
            VoiceInterviewSessionEntity.builder().id(42L).build();
        var destination = new VoiceEvaluationRetryPolicy.RetryDestination(
            "voice.evaluation.dead",
            Duration.ZERO,
            true
        );
        when(sessionRepository.findById(42L)).thenReturn(Optional.of(session));
        when(evaluationService.generateEvaluation(42L, task.messageId().toString()))
            .thenThrow(new RuntimeException("AI down"));
        when(retryPolicy.destinationFor(3)).thenReturn(destination);

        consumer.consume(task, delivery, channel);

        verify(voiceInterviewService).updateEvaluateStatus(
            42L,
            AsyncTaskStatus.FAILED,
            "AI down"
        );
        verify(producer).publishDead(task, "AI down");
        verify(channel).basicAck(7L, false);
    }

    private VoiceEvaluationMessage task(int retryCount) {
        UUID id = UUID.randomUUID();
        return new VoiceEvaluationMessage(id, 42L, retryCount, OffsetDateTime.now(), id);
    }
}
