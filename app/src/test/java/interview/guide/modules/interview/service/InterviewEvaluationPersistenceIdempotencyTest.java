package interview.guide.modules.interview.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import interview.guide.common.model.AsyncTaskStatus;
import interview.guide.modules.interview.model.InterviewReportDTO;
import interview.guide.modules.interview.model.InterviewSessionEntity;
import interview.guide.modules.interview.repository.InterviewAnswerRepository;
import interview.guide.modules.interview.repository.InterviewSessionRepository;
import interview.guide.modules.resume.repository.ResumeRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class InterviewEvaluationPersistenceIdempotencyTest {

  private InterviewSessionRepository sessionRepository;
  private InterviewPersistenceService service;

  @BeforeEach
  void setUp() {
    sessionRepository = mock(InterviewSessionRepository.class);
    InterviewAnswerRepository answerRepository = mock(InterviewAnswerRepository.class);
    when(answerRepository.findBySession_SessionIdOrderByQuestionIndex("session-42"))
        .thenReturn(List.of());
    service = new InterviewPersistenceService(
        sessionRepository,
        answerRepository,
        mock(ResumeRepository.class),
        new ObjectMapper()
    );
  }

  @Test
  @DisplayName("首次完成原子保存报告状态和消息标识")
  void shouldCompleteWithMessageId() {
    InterviewSessionEntity session = new InterviewSessionEntity();
    session.setSessionId("session-42");
    when(sessionRepository.findBySessionId("session-42"))
        .thenReturn(Optional.of(session));

    var result = service.saveReport("session-42", report(), "message-1");

    assertThat(result).isEqualTo(
        InterviewPersistenceService.CompletionResult.CREATED
    );
    assertThat(session.getEvaluationMessageId()).isEqualTo("message-1");
    assertThat(session.getEvaluateStatus()).isEqualTo(AsyncTaskStatus.COMPLETED);
    assertThat(session.getStatus())
        .isEqualTo(InterviewSessionEntity.SessionStatus.EVALUATED);
  }

  @Test
  @DisplayName("相同消息重复完成时不覆盖报告")
  void shouldSkipDuplicateMessage() {
    InterviewSessionEntity session = new InterviewSessionEntity();
    session.setSessionId("session-42");
    session.setEvaluationMessageId("message-1");
    when(sessionRepository.findBySessionId("session-42"))
        .thenReturn(Optional.of(session));

    var result = service.saveReport("session-42", report(), "message-1");

    assertThat(result).isEqualTo(
        InterviewPersistenceService.CompletionResult.ALREADY_COMPLETED
    );
  }

  @Test
  @DisplayName("会话不存在时返回缺失结果")
  void shouldReturnMissingSession() {
    when(sessionRepository.findBySessionId("missing")).thenReturn(Optional.empty());

    assertThat(service.saveReport("missing", report(), "message-1"))
        .isEqualTo(InterviewPersistenceService.CompletionResult.SESSION_MISSING);
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
