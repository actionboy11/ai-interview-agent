package interview.guide.modules.resume.messaging.rabbit;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.rabbitmq.client.Channel;
import interview.guide.modules.interview.model.ResumeAnalysisResponse;
import interview.guide.modules.resume.model.ResumeEntity;
import interview.guide.modules.resume.service.ResumeGradingService;
import interview.guide.modules.resume.service.ResumePersistenceService;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;

class ResumeAnalysisRabbitConsumerTest {

    private ResumeGradingService gradingService;
    private ResumePersistenceService persistenceService;
    private ResumeAnalysisRabbitProducer producer;
    private ResumeAnalysisRetryPolicy retryPolicy;
    private ResumeAnalysisRabbitConsumer consumer;
    private Channel channel;
    private Message delivery;

    @BeforeEach
    void setUp() {
        gradingService = mock(ResumeGradingService.class);
        persistenceService = mock(ResumePersistenceService.class);
        producer = mock(ResumeAnalysisRabbitProducer.class);
        retryPolicy = mock(ResumeAnalysisRetryPolicy.class);
        consumer = new ResumeAnalysisRabbitConsumer(
            gradingService,
            persistenceService,
            producer,
            retryPolicy
        );
        channel = mock(Channel.class);
        MessageProperties properties = new MessageProperties();
        properties.setDeliveryTag(7L);
        delivery = new Message(new byte[0], properties);
    }

    @Test
    void shouldCompleteAndAckSuccessfulAnalysis() throws Exception {
        ResumeAnalysisMessage task = task(0);
        ResumeEntity resume = new ResumeEntity();
        resume.setId(42L);
        resume.setResumeText("resume text");
        ResumeAnalysisResponse response = mock(ResumeAnalysisResponse.class);
        when(persistenceService.findById(42L)).thenReturn(Optional.of(resume));
        when(gradingService.analyzeResume("resume text")).thenReturn(response);

        consumer.consume(task, delivery, channel);

        verify(persistenceService).markAnalysisProcessing(42L);
        verify(persistenceService).completeAnalysis(
            42L,
            task.messageId().toString(),
            response
        );
        verify(channel).basicAck(7L, false);
    }

    @Test
    void shouldPublishRetryAndAckAfterBusinessFailure() throws Exception {
        ResumeAnalysisMessage task = task(0);
        ResumeEntity resume = new ResumeEntity();
        resume.setId(42L);
        resume.setResumeText("resume text");
        var destination = new ResumeAnalysisRetryPolicy.RetryDestination(
            "resume.analysis.retry.10s",
            java.time.Duration.ofSeconds(10),
            false
        );
        when(persistenceService.findById(42L)).thenReturn(Optional.of(resume));
        when(gradingService.analyzeResume("resume text")).thenThrow(new RuntimeException("AI down"));
        when(retryPolicy.destinationFor(0)).thenReturn(destination);

        consumer.consume(task, delivery, channel);

        verify(producer).publishRetry(task, destination);
        verify(channel).basicAck(7L, false);
    }

    private ResumeAnalysisMessage task(int retryCount) {
        UUID id = UUID.randomUUID();
        return new ResumeAnalysisMessage(id, 42L, retryCount, OffsetDateTime.now(), id);
    }
}
