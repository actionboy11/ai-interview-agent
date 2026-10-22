package interview.guide.modules.interview.deadletter;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import interview.guide.common.model.AsyncTaskStatus;
import interview.guide.common.transaction.TransactionalExecutor;
import interview.guide.modules.auth.model.UserEntity;
import interview.guide.modules.auth.security.CurrentUserProvider;
import interview.guide.modules.interview.messaging.InterviewEvaluationTaskPublisher;
import interview.guide.modules.interview.messaging.rabbit.InterviewEvaluationMessage;
import interview.guide.modules.interview.model.InterviewSessionEntity;
import interview.guide.modules.interview.repository.InterviewSessionRepository;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
            mock(TransactionalExecutor.class),
            mock(interview.guide.modules.auth.security.CurrentUserProvider.class)
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

  @Test
  @DisplayName("重投旧快照遇到已完成报告时不写回旧会话也不再次发布")
  void replayCannotRegressCompletedWinner() {
    var deadLetters = mock(InterviewEvaluationDeadLetterRepository.class);
    var sessions = mock(InterviewSessionRepository.class);
    var publisher = mock(InterviewEvaluationTaskPublisher.class);
    var currentUser = mock(CurrentUserProvider.class);
    UUID ownerId = UUID.randomUUID();
    String sessionId = "session-race";
    var deadLetter = new InterviewEvaluationDeadLetterEntity();
    deadLetter.setId(42L);
    deadLetter.setSessionId(sessionId);
    deadLetter.setRetryCount(1);
    deadLetter.setFailedAt(OffsetDateTime.now());
    deadLetter.setStatus(InterviewEvaluationDeadLetterStatus.PENDING);

    var stale = session(ownerId, sessionId, AsyncTaskStatus.FAILED);
    var winner = session(ownerId, sessionId, AsyncTaskStatus.COMPLETED);
    winner.setStatus(InterviewSessionEntity.SessionStatus.EVALUATED);
    winner.setReportJson("{\"overallScore\":90}");
    winner.setEvaluationMessageId("winning-message");
    winner.setMemoryDerivationStatus(AsyncTaskStatus.COMPLETED);
    when(currentUser.requireUserId()).thenReturn(ownerId);
    when(deadLetters.findOwnedById(ownerId, 42L)).thenReturn(Optional.of(deadLetter));
    when(deadLetters.claimForReplay(42L)).thenReturn(1);
    when(deadLetters.save(any())).thenAnswer(call -> call.getArgument(0));
    // The old, unlocked lookup sees a pre-completion snapshot; the locked lookup sees the winner.
    when(sessions.findByOwnerIdAndSessionId(ownerId, sessionId)).thenReturn(Optional.of(stale));
    when(sessions.findBySessionIdForDerivation(sessionId)).thenReturn(Optional.of(winner));
    when(publisher.publish(sessionId)).thenReturn(
        new InterviewEvaluationTaskPublisher.PublishReceipt(UUID.randomUUID()));

    var service = new InterviewEvaluationDeadLetterService(deadLetters, sessions, publisher,
        new ObjectMapper(), new TransactionalExecutor(), currentUser);
    var result = service.replay(42L);

    assertThat(result.status()).isEqualTo(InterviewEvaluationDeadLetterStatus.RESOLVED);
    assertThat(winner.getEvaluateStatus()).isEqualTo(AsyncTaskStatus.COMPLETED);
    assertThat(winner.getReportJson()).isEqualTo("{\"overallScore\":90}");
    assertThat(winner.getEvaluationMessageId()).isEqualTo("winning-message");
    assertThat(winner.getMemoryDerivationStatus()).isEqualTo(AsyncTaskStatus.COMPLETED);
    verify(sessions, never()).save(any());
    verify(publisher, never()).publish(any());
  }

  @Test
  @DisplayName("重投发布失败仍恢复死信待处理状态")
  void publishFailureRestoresPendingDeadLetter() {
    var deadLetters = mock(InterviewEvaluationDeadLetterRepository.class);
    var sessions = mock(InterviewSessionRepository.class);
    var publisher = mock(InterviewEvaluationTaskPublisher.class);
    var currentUser = mock(CurrentUserProvider.class);
    UUID ownerId = UUID.randomUUID();
    var deadLetter = new InterviewEvaluationDeadLetterEntity();
    deadLetter.setId(43L);
    deadLetter.setSessionId("session-publish-fails");
    deadLetter.setStatus(InterviewEvaluationDeadLetterStatus.PENDING);
    var session = session(ownerId, deadLetter.getSessionId(), AsyncTaskStatus.FAILED);
    when(currentUser.requireUserId()).thenReturn(ownerId);
    when(deadLetters.findOwnedById(ownerId, 43L)).thenReturn(Optional.of(deadLetter));
    when(deadLetters.claimForReplay(43L)).thenReturn(1);
    when(sessions.findBySessionIdForDerivation(deadLetter.getSessionId()))
        .thenReturn(Optional.of(session));
    when(publisher.publish(deadLetter.getSessionId()))
        .thenThrow(new IllegalStateException("broker unavailable"));
    var service = new InterviewEvaluationDeadLetterService(deadLetters, sessions, publisher,
        new ObjectMapper(), new TransactionalExecutor(), currentUser);

    assertThatThrownBy(() -> service.replay(43L))
        .isInstanceOf(IllegalStateException.class);
    assertThat(deadLetter.getStatus()).isEqualTo(InterviewEvaluationDeadLetterStatus.PENDING);
    assertThat(session.getEvaluateStatus()).isEqualTo(AsyncTaskStatus.PENDING);
    verify(deadLetters).save(deadLetter);
  }

  private InterviewSessionEntity session(UUID ownerId, String sessionId,
      AsyncTaskStatus status) {
    var session = new InterviewSessionEntity();
    session.setSessionId(sessionId);
    session.setOwner(UserEntity.builder().id(ownerId).build());
    session.setEvaluateStatus(status);
    return session;
  }
}
