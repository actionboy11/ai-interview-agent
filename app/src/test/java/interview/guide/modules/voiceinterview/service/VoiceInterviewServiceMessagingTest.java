package interview.guide.modules.voiceinterview.service;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import interview.guide.common.ai.LlmProviderRegistry;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.model.AsyncTaskStatus;
import interview.guide.modules.voiceinterview.config.VoiceInterviewProperties;
import interview.guide.modules.voiceinterview.messaging.VoiceEvaluationTaskPublisher;
import interview.guide.modules.voiceinterview.model.VoiceInterviewSessionEntity;
import interview.guide.modules.voiceinterview.repository.VoiceInterviewEvaluationRepository;
import interview.guide.modules.voiceinterview.repository.VoiceInterviewMessageRepository;
import interview.guide.modules.voiceinterview.repository.VoiceInterviewSessionRepository;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;

class VoiceInterviewServiceMessagingTest {
    @Test
    void shouldPublishSessionThroughTransportPort() {
        VoiceInterviewSessionRepository sessionRepository =
            mock(VoiceInterviewSessionRepository.class);
        VoiceEvaluationTaskPublisher publisher = mock(VoiceEvaluationTaskPublisher.class);
        VoiceInterviewSessionEntity session = VoiceInterviewSessionEntity.builder()
            .id(42L)
            .build();
        when(sessionRepository.findById(42L)).thenReturn(Optional.of(session));

        VoiceInterviewService service = new VoiceInterviewService(
            sessionRepository,
            mock(VoiceInterviewMessageRepository.class),
            mock(VoiceInterviewEvaluationRepository.class),
            mock(RedissonClient.class),
            mock(VoiceInterviewProperties.class),
            publisher,
            mock(LlmProviderRegistry.class)
        );

        service.triggerEvaluation(42L);

        verify(publisher).publish(42L);
        verify(sessionRepository).save(session);
        org.assertj.core.api.Assertions.assertThat(session.getEvaluateStatus())
            .isEqualTo(AsyncTaskStatus.PENDING);
    }

    @Test
    void shouldMarkEvaluationFailedWhenInitialPublishFails() {
        VoiceInterviewSessionRepository sessionRepository =
            mock(VoiceInterviewSessionRepository.class);
        VoiceEvaluationTaskPublisher publisher = mock(VoiceEvaluationTaskPublisher.class);
        VoiceInterviewSessionEntity session = VoiceInterviewSessionEntity.builder()
            .id(42L)
            .build();
        when(sessionRepository.findById(42L)).thenReturn(Optional.of(session));
        when(publisher.publish(42L)).thenThrow(new BusinessException("broker unavailable"));
        VoiceInterviewService service = newService(sessionRepository, publisher);

        service.triggerEvaluation(42L);

        org.assertj.core.api.Assertions.assertThat(session.getEvaluateStatus())
            .isEqualTo(AsyncTaskStatus.FAILED);
        org.assertj.core.api.Assertions.assertThat(session.getEvaluateError())
            .contains("broker unavailable");
    }

    private VoiceInterviewService newService(
        VoiceInterviewSessionRepository sessionRepository,
        VoiceEvaluationTaskPublisher publisher
    ) {
        return new VoiceInterviewService(
            sessionRepository,
            mock(VoiceInterviewMessageRepository.class),
            mock(VoiceInterviewEvaluationRepository.class),
            mock(RedissonClient.class),
            mock(VoiceInterviewProperties.class),
            publisher,
            mock(LlmProviderRegistry.class)
        );
    }
}
