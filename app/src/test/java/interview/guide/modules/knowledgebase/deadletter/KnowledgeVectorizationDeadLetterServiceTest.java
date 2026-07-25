package interview.guide.modules.knowledgebase.deadletter;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import interview.guide.common.transaction.TransactionalExecutor;
import interview.guide.modules.knowledgebase.messaging.KnowledgeVectorizationTaskPublisher;
import interview.guide.modules.knowledgebase.messaging.rabbit.KnowledgeVectorizationMessage;
import interview.guide.modules.knowledgebase.repository.KnowledgeBaseRepository;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class KnowledgeVectorizationDeadLetterServiceTest {

    @Test
    void shouldPersistFirstDeadLetterAndIgnoreDuplicate() {
        KnowledgeVectorizationDeadLetterRepository repository =
            mock(KnowledgeVectorizationDeadLetterRepository.class);
        KnowledgeVectorizationDeadLetterService service = new KnowledgeVectorizationDeadLetterService(
            repository,
            mock(KnowledgeBaseRepository.class),
            mock(KnowledgeVectorizationTaskPublisher.class),
            new ObjectMapper(),
            mock(TransactionalExecutor.class)
        );
        UUID id = UUID.randomUUID();
        KnowledgeVectorizationMessage message =
            new KnowledgeVectorizationMessage(id, 42L, 3, OffsetDateTime.now(), id);

        service.record(message, "AI unavailable");
        verify(repository).save(any(KnowledgeVectorizationDeadLetterEntity.class));

        when(repository.existsByOriginalMessageId(id.toString())).thenReturn(true);
        service.record(message, "duplicate");
        verify(repository).save(any(KnowledgeVectorizationDeadLetterEntity.class));
    }
}
