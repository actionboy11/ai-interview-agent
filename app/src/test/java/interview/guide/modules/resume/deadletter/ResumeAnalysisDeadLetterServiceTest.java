package interview.guide.modules.resume.deadletter;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import interview.guide.common.transaction.TransactionalExecutor;
import interview.guide.modules.resume.messaging.ResumeAnalysisTaskPublisher;
import interview.guide.modules.resume.messaging.rabbit.ResumeAnalysisMessage;
import interview.guide.modules.resume.repository.ResumeRepository;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class ResumeAnalysisDeadLetterServiceTest {

    @Test
    void shouldPersistFirstDeadLetterAndIgnoreDuplicate() {
        ResumeAnalysisDeadLetterRepository repository =
            mock(ResumeAnalysisDeadLetterRepository.class);
        ResumeAnalysisDeadLetterService service = new ResumeAnalysisDeadLetterService(
            repository,
            mock(ResumeRepository.class),
            mock(ResumeAnalysisTaskPublisher.class),
            new ObjectMapper(),
            mock(TransactionalExecutor.class)
        );
        UUID id = UUID.randomUUID();
        ResumeAnalysisMessage message =
            new ResumeAnalysisMessage(id, 42L, 3, OffsetDateTime.now(), id);

        service.record(message, "AI unavailable");
        verify(repository).save(any(ResumeAnalysisDeadLetterEntity.class));

        when(repository.existsByOriginalMessageId(id.toString())).thenReturn(true);
        service.record(message, "duplicate");
        verify(repository).save(any(ResumeAnalysisDeadLetterEntity.class));
    }
}
