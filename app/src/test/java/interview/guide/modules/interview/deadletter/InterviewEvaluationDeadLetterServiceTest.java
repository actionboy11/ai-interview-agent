package interview.guide.modules.interview.deadletter;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import interview.guide.common.transaction.TransactionalExecutor;
import interview.guide.modules.interview.messaging.InterviewEvaluationTaskPublisher;
import interview.guide.modules.interview.messaging.rabbit.InterviewEvaluationMessage;
import interview.guide.modules.interview.repository.InterviewSessionRepository;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class InterviewEvaluationDeadLetterServiceTest {

  @Test
  @DisplayName("相同原始消息只保存一次死信审计")
  void shouldPersistOnlyFirstDeadLetter() {
    InterviewEvaluationDeadLetterRepository repository =
        mock(InterviewEvaluationDeadLetterRepository.class);
    InterviewEvaluationDeadLetterService service =
        new InterviewEvaluationDeadLetterService(
            repository,
            mock(InterviewSessionRepository.class),
            mock(InterviewEvaluationTaskPublisher.class),
            new ObjectMapper(),
            mock(TransactionalExecutor.class)
        );
    UUID id = UUID.randomUUID();
    var message = new InterviewEvaluationMessage(
        id, "session-42", 3, OffsetDateTime.now(), id
    );

    service.record(message, "AI unavailable");
    when(repository.existsByOriginalMessageId(id.toString())).thenReturn(true);
    service.record(message, "duplicate");

    verify(repository, times(1))
        .save(any(InterviewEvaluationDeadLetterEntity.class));
  }
}
