package interview.guide.modules.interview.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.ArgumentMatchers.any;

import interview.guide.common.model.AsyncTaskStatus;
import interview.guide.modules.auth.security.CurrentUserProvider;
import interview.guide.modules.auth.repository.UserRepository;
import interview.guide.modules.auth.model.UserEntity;
import interview.guide.modules.interview.model.InterviewReportDTO;
import interview.guide.modules.interview.model.InterviewSessionEntity;
import interview.guide.modules.interview.repository.InterviewAnswerRepository;
import interview.guide.modules.interview.repository.InterviewSessionRepository;
import interview.guide.modules.interview.dynamic.repository.DynamicInterviewRuntimeRepository;
import interview.guide.modules.interview.dynamic.repository.DynamicEvaluationOutboxRepository;
import interview.guide.modules.memory.service.AbilityMemoryDeletionService;
import interview.guide.modules.resume.repository.ResumeRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class InterviewEvaluationPersistenceIdempotencyTest {

  private InterviewSessionRepository sessionRepository;
  private InterviewPersistenceService service;
  private CurrentUserProvider currentUser;

  @BeforeEach
  void setUp() {
    sessionRepository = mock(InterviewSessionRepository.class);
    currentUser = mock(CurrentUserProvider.class);
    InterviewAnswerRepository answerRepository = mock(InterviewAnswerRepository.class);
    when(answerRepository.findBySession_SessionIdOrderByQuestionIndex("session-42"))
        .thenReturn(List.of());
    service = new InterviewPersistenceService(
        sessionRepository,
        answerRepository,
        mock(ResumeRepository.class),
        new ObjectMapper(),
        currentUser,
        mock(UserRepository.class),
        mock(AbilityMemoryDeletionService.class),
        mock(DynamicInterviewRuntimeRepository.class),
        mock(DynamicEvaluationOutboxRepository.class)
    );
  }

  @Test
  @DisplayName("首次完成原子保存报告状态和消息标识")
  void shouldCompleteWithMessageId() {
    InterviewSessionEntity session = new InterviewSessionEntity();
    session.setOwner(UserEntity.builder().id(java.util.UUID.randomUUID()).build());
    session.setSessionId("session-42");
    session.setQuestionsJson("[]");
    when(sessionRepository.findBySessionIdForDerivation("session-42"))
        .thenReturn(Optional.of(session));

    var result = service.saveReport("session-42", report(), "message-1");

    assertThat(result).isEqualTo(
        InterviewPersistenceService.CompletionResult.CREATED
    );
    assertThat(session.getEvaluationMessageId()).isEqualTo("message-1");
    assertThat(session.getEvaluateStatus()).isEqualTo(AsyncTaskStatus.COMPLETED);
    assertThat(session.getStatus())
        .isEqualTo(InterviewSessionEntity.SessionStatus.EVALUATED);
    verify(sessionRepository).findBySessionIdForDerivation("session-42");
  }

  @Test
  @DisplayName("相同消息重复完成时不覆盖报告")
  void shouldSkipDuplicateMessage() {
    InterviewSessionEntity session = new InterviewSessionEntity();
    session.setOwner(UserEntity.builder().id(java.util.UUID.randomUUID()).build());
    session.setSessionId("session-42");
    session.setQuestionsJson("[]");
    session.setEvaluationMessageId("message-1");
    when(sessionRepository.findBySessionIdForDerivation("session-42"))
        .thenReturn(Optional.of(session));

    var result = service.saveReport("session-42", report(), "message-1");

    assertThat(result).isEqualTo(
        InterviewPersistenceService.CompletionResult.ALREADY_COMPLETED
    );
  }

  @Test
  @DisplayName("会话不存在时返回缺失结果")
  void shouldReturnMissingSession() {
    when(sessionRepository.findBySessionIdForDerivation("missing")).thenReturn(Optional.empty());

    assertThat(service.saveReport("missing", report(), "message-1"))
        .isEqualTo(InterviewPersistenceService.CompletionResult.SESSION_MISSING);
  }

  @Test
  @DisplayName("两参数完成不得预加载旧实体后在同一持久化上下文覆盖锁定赢家")
  void ownedCompletionLocksBeforeLoadingSession() {
    UUID ownerId = UUID.randomUUID();
    when(currentUser.requireUserId()).thenReturn(ownerId);
    var stale = new InterviewSessionEntity();
    stale.setSessionId("session-42");
    stale.setOwner(UserEntity.builder().id(ownerId).build());
    stale.setQuestionsJson("[]");
    stale.setEvaluateStatus(AsyncTaskStatus.FAILED);
    var winner = new InterviewSessionEntity();
    winner.setSessionId("session-42");
    winner.setOwner(UserEntity.builder().id(ownerId).build());
    winner.setQuestionsJson("[]");
    winner.setStatus(InterviewSessionEntity.SessionStatus.EVALUATED);
    winner.setEvaluateStatus(AsyncTaskStatus.COMPLETED);
    winner.setReportJson("winning report");
    winner.setEvaluationMessageId("winning-message");
    var preloaded = new AtomicBoolean();
    when(sessionRepository.findByOwnerIdAndSessionId(ownerId, "session-42"))
        .thenAnswer(call -> {
          preloaded.set(true);
          return Optional.of(stale);
        });
    when(sessionRepository.findBySessionIdForDerivation("session-42"))
        .thenAnswer(call -> Optional.of(preloaded.get() ? stale : winner));

    service.saveReport("session-42", report());

    assertThat(preloaded.get()).isFalse();
    assertThat(winner.getReportJson()).isEqualTo("winning report");
    assertThat(winner.getEvaluationMessageId()).isEqualTo("winning-message");
    verify(sessionRepository, never()).save(any());
  }

  private InterviewReportDTO report() {
    return new InterviewReportDTO(
        "session-42",
        0,
        88,
        List.of(),
        List.of(),
        "good",
        List.of("clear"),
        List.of("detail"),
        List.of()
    );
  }
}
