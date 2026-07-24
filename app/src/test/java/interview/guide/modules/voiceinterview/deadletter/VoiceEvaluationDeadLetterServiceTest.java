package interview.guide.modules.voiceinterview.deadletter;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import interview.guide.common.transaction.TransactionalExecutor;
import interview.guide.modules.voiceinterview.messaging.VoiceEvaluationTaskPublisher;
import interview.guide.modules.voiceinterview.messaging.rabbit.VoiceEvaluationMessage;
import interview.guide.modules.voiceinterview.repository.VoiceInterviewSessionRepository;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class VoiceEvaluationDeadLetterServiceTest {
    @Test
    void shouldPersistFirstDeadLetterAndIgnoreDuplicate() {
        VoiceEvaluationDeadLetterRepository repository =
            mock(VoiceEvaluationDeadLetterRepository.class);
        VoiceEvaluationDeadLetterService service = new VoiceEvaluationDeadLetterService(
            repository,
            mock(VoiceInterviewSessionRepository.class),
            mock(VoiceEvaluationTaskPublisher.class),
            new ObjectMapper(),
            mock(TransactionalExecutor.class)
        );
        UUID id = UUID.randomUUID();
        VoiceEvaluationMessage message =
            new VoiceEvaluationMessage(id, 42L, 3, OffsetDateTime.now(), id);

        service.record(message, "AI unavailable");
        verify(repository).save(any(VoiceEvaluationDeadLetterEntity.class));

        when(repository.existsByOriginalMessageId(id.toString())).thenReturn(true);
        service.record(message, "duplicate");
        verify(repository).save(any(VoiceEvaluationDeadLetterEntity.class));
    }
}
