package interview.guide.modules.ownership;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.auth.model.UserEntity;
import interview.guide.modules.auth.model.UserRole;
import interview.guide.modules.interview.model.CreateInterviewRequest;
import interview.guide.modules.interview.model.InterviewSessionDTO.SessionStatus;
import interview.guide.modules.interview.model.InterviewSessionEntity;
import interview.guide.modules.interview.model.SubmitAnswerRequest;
import interview.guide.modules.interview.deadletter.InterviewEvaluationDeadLetterEntity;
import interview.guide.modules.interview.deadletter.InterviewEvaluationDeadLetterStatus;
import interview.guide.modules.interview.model.InterviewQuestionDTO;
import interview.guide.modules.interview.model.InterviewReportDTO;
import interview.guide.modules.interview.messaging.rabbit.InterviewEvaluationMessage;
import interview.guide.modules.interview.messaging.rabbit.InterviewEvaluationRabbitConsumer;
import interview.guide.modules.interview.messaging.rabbit.InterviewEvaluationRabbitProducer;
import interview.guide.modules.interview.messaging.rabbit.InterviewEvaluationRetryPolicy;
import interview.guide.modules.interview.messaging.InterviewEvaluationTaskPublisher;
import interview.guide.modules.interview.listener.EvaluateStreamConsumer;
import interview.guide.common.constant.AsyncTaskStreamConstants;
import interview.guide.common.model.AsyncTaskStatus;
import interview.guide.common.transaction.TransactionalExecutor;
import interview.guide.infrastructure.redis.InterviewSessionCache.CachedSession;
import jakarta.persistence.EntityManager;
import org.redisson.api.stream.StreamMessageId;
import org.springframework.test.util.ReflectionTestUtils;
import com.rabbitmq.client.Channel;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;

@DisplayName("文字面试工作流双用户隔离")
class TextInterviewOwnershipIntegrationTest extends WorkflowOwnershipTestSupport {
  @Autowired EntityManager entityManager;
  @Autowired TransactionalExecutor transactionalExecutor;

  @Test
  @DisplayName("无缓存与持久化上下文时仍能恢复自己的简历面试并读取历史题目")
  void coldResumeRecoveryAndHistory() {
    var ownResume = resume(first, UUID.randomUUID().toString());
    var interview = session(first);
    interview.setResume(ownResume);
    interview.setSkillId("java");
    interview.setQuestionsJson("[{\"questionIndex\":0,\"question\":\"Cold question\","
        + "\"type\":\"TECHNICAL\",\"category\":\"Java\",\"isFollowUp\":false}]");
    sessions.saveAndFlush(interview);
    transactionalExecutor.run(entityManager::clear);
    cachedValues.clear();

    assertThat(interviewService.findUnfinishedSessionOrThrow(ownResume.getId()).sessionId())
        .isEqualTo(interview.getSessionId());
    transactionalExecutor.run(entityManager::clear);
    assertThat(interviewPersistence.getHistoricalQuestions("java", ownResume.getId()))
        .extracting(value -> value.question()).containsExactly("Cold question");
  }

  @Test
  @DisplayName("自己的会话可以创建、恢复、暂存、提交、生成报告、导出和删除")
  void permitsOwnerLifecycleAndDiscardsDeletedCache() {
    var resume = resume(first, UUID.randomUUID().toString());
    when(questions.generateQuestionsBySkill(any(), any(), any(), any(), anyInt(), any(), any(), any()))
        .thenAnswer(invocation -> {
          assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
          return new ArrayList<>(List.of(InterviewQuestionDTO.create(0, "Question", "java", "Java")));
        });
    var request = new CreateInterviewRequest("text", 3, resume.getId(), false, null, "java", "mid", null, null);
    var created = interviewService.createSession(request);
    assertThat(interviewPersistence.findBySessionId(created.sessionId())).get()
        .extracting(value -> value.getOwner().getId()).isEqualTo(first.getId());
    assertThat(interviewService.findUnfinishedSessionOrThrow(resume.getId()).sessionId())
        .isEqualTo(created.sessionId());
    assertThat(interviewService.getCurrentQuestion(created.sessionId()).question()).isEqualTo("Question");
    interviewService.saveAnswer(new SubmitAnswerRequest(created.sessionId(), 0, "draft"));
    assertThat(interviewPersistence.findAnswersBySessionId(created.sessionId())).singleElement()
        .extracting(value -> value.getUserAnswer()).isEqualTo("draft");
    var submitted = interviewService.submitAnswer(new SubmitAnswerRequest(created.sessionId(), 0, "final"));
    assertThat(submitted.hasNextQuestion()).isFalse();
    when(evaluation.evaluateInterview(any(), eq(created.sessionId()), any(), any()))
        .thenAnswer(invocation -> {
          assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
          return report(created.sessionId());
        });
    assertThat(interviewService.generateReport(created.sessionId()).overallScore()).isEqualTo(80);
    assertThat(interviewHistory.getInterviewDetail(created.sessionId())).isNotNull();
    when(pdf.exportInterviewReport(any())).thenReturn(new byte[] {1, 2});
    assertThat(interviewHistory.exportInterviewPdf(created.sessionId())).containsExactly(1, 2);
    interviewService.deleteSession(created.sessionId());
    assertThatThrownBy(() -> interviewService.getSession(created.sessionId())).isInstanceOf(BusinessException.class);
    assertThat(cache.getSession(created.sessionId())).isEmpty();
    cache.saveSession(created.sessionId(), "stale", resume.getId(), List.of(), 0, SessionStatus.CREATED);
    assertThatThrownBy(() -> interviewService.getSession(created.sessionId()))
        .isInstanceOf(BusinessException.class);
  }

  @Test
  @DisplayName("异步无需用户上下文，可处理一致关系，丢弃已删除和跨用户关联")
  void workersValidateOwnershipWithoutUserContext() throws Exception {
    var ownResume = resume(first, UUID.randomUUID().toString());
    var valid = session(first);
    valid.setResume(ownResume);
    sessions.saveAndFlush(valid);
    var invalid = session(first);
    invalid.setResume(resume(second, UUID.randomUUID().toString()));
    sessions.saveAndFlush(invalid);
    cache.saveSession(valid.getSessionId(), "resume", ownResume.getId(), List.of(), 0, SessionStatus.COMPLETED);
    when(currentUser.requireUserId()).thenThrow(new AssertionError("Worker must not require request identity"));
    when(evaluation.evaluateInterview(any(), eq(valid.getSessionId()), any(), any()))
        .thenAnswer(invocation -> {
          assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
          return report(valid.getSessionId());
        });
    var consumer = new InterviewEvaluationRabbitConsumer(sessions, evaluation, interviewPersistence,
        mapper, llm, mock(InterviewEvaluationRabbitProducer.class),
        mock(InterviewEvaluationRetryPolicy.class), cache);
    var channel = mock(Channel.class);
    for (String id : List.of(valid.getSessionId(), invalid.getSessionId(), "deleted")) {
      var messageId = UUID.randomUUID();
      var properties = new MessageProperties();
      properties.setDeliveryTag(1);
      consumer.consume(new InterviewEvaluationMessage(messageId, id, 0, OffsetDateTime.now(), messageId),
          new Message(new byte[0], properties), channel);
    }
    verify(channel, times(3)).basicAck(1, false);
    assertThat(sessions.findBySessionId(valid.getSessionId()).orElseThrow().getOverallScore()).isEqualTo(80);
    assertThat(sessions.findBySessionId(invalid.getSessionId()).orElseThrow().getOverallScore()).isNull();
    assertThat(((CachedSession) cachedValues.get("interview:session:" + first.getId()
        + ":" + valid.getSessionId())).getStatus()).isEqualTo(SessionStatus.EVALUATED);
    verify(evaluation, never()).evaluateInterview(any(), eq(invalid.getSessionId()), any(), any());
  }

  @Test
  @DisplayName("Redis Stream 也丢弃跨用户关联与删除会话，并使用内部持久化入口")
  void streamWorkerValidatesRelations() {
    var valid = session(first);
    var invalid = session(first);
    invalid.setResume(resume(second, UUID.randomUUID().toString()));
    sessions.saveAndFlush(invalid);
    when(currentUser.requireUserId()).thenThrow(new AssertionError("Worker must not require request identity"));
    when(evaluation.evaluateInterview(any(), eq(valid.getSessionId()), any(), any()))
        .thenReturn(report(valid.getSessionId()));
    var consumer = new EvaluateStreamConsumer(redis, sessions, evaluation, interviewPersistence, mapper, llm, cache);
    for (String id : List.of(valid.getSessionId(), invalid.getSessionId(), "deleted")) {
      ReflectionTestUtils.invokeMethod(consumer, "processMessage", new StreamMessageId(5, 1),
          Map.of(AsyncTaskStreamConstants.FIELD_SESSION_ID, id));
    }
    assertThat(sessions.findBySessionId(valid.getSessionId()).orElseThrow().getEvaluateStatus())
        .isEqualTo(AsyncTaskStatus.COMPLETED);
    assertThat(sessions.findBySessionId(invalid.getSessionId()).orElseThrow().getOverallScore()).isNull();
    verify(redis, times(3)).streamAck(any(), any(), any());
    verify(evaluation, never()).evaluateInterview(any(), eq(invalid.getSessionId()), any(), any());
  }

  private InterviewReportDTO report(String sessionId) {
    return new InterviewReportDTO(sessionId, 1, 80, List.of(), List.of(), "Good", List.of(), List.of(), List.of());
  }

  @Test
  @DisplayName("损坏的跨用户关联不能经会话列表或历史问题泄漏")
  void hidesInconsistentGraphFromListsAndHistory() {
    var corrupt = session(first);
    corrupt.setResume(resume(second, UUID.randomUUID().toString()));
    corrupt.setSkillId("java");
    corrupt.setQuestionsJson("[{\"questionIndex\":0,\"question\":\"foreign-derived\",\"isFollowUp\":false}]");
    sessions.saveAndFlush(corrupt);
    assertThat(interviewPersistence.findAll()).isEmpty();
    assertThat(interviewPersistence.getHistoricalQuestions("java", null)).isEmpty();
  }

  @Test
  @DisplayName("管理员文本面试死信按会话所有者隔离，重放前先校验")
  void isolatesDeadLettersBeforeReplayClaim() {
    first.setRole(UserRole.ADMIN);
    users.saveAndFlush(first);
    var own = session(first);
    var foreign = session(second);
    var ownedDead = deadLetter(own.getSessionId());
    var foreignDead = deadLetter(foreign.getSessionId());
    var orphan = deadLetter("missing");
    assertThat(interviewDeadLetterService.list(null, 0, 100).items())
        .extracting(value -> value.id()).containsExactly(ownedDead.getId());
    assertThat(interviewDeadLetterService.get(ownedDead.getId()).id()).isEqualTo(ownedDead.getId());
    for (var rejected : List.of(foreignDead, orphan)) {
      assertThatThrownBy(() -> interviewDeadLetterService.get(rejected.getId()))
          .isInstanceOf(BusinessException.class);
      assertThatThrownBy(() -> interviewDeadLetterService.replay(rejected.getId()))
          .isInstanceOf(BusinessException.class);
      assertThat(interviewDeadLetters.findById(rejected.getId()).orElseThrow().getStatus())
          .isEqualTo(InterviewEvaluationDeadLetterStatus.PENDING);
    }
    when(interviewPublisher.publish(own.getSessionId()))
        .thenReturn(new InterviewEvaluationTaskPublisher.PublishReceipt(UUID.randomUUID()));
    assertThat(interviewDeadLetterService.replay(ownedDead.getId()).status())
        .isEqualTo(InterviewEvaluationDeadLetterStatus.REPLAYED);
  }

  private InterviewSessionEntity session(UserEntity owner) {
    var session = new InterviewSessionEntity();
    session.setOwner(owner);
    session.setSessionId(UUID.randomUUID().toString());
    session.setQuestionsJson("[]");
    return sessions.saveAndFlush(session);
  }

  private InterviewEvaluationDeadLetterEntity deadLetter(String sessionId) {
    var dead = new InterviewEvaluationDeadLetterEntity();
    dead.setSessionId(sessionId);
    dead.setRetryCount(3);
    dead.setOriginalMessageId(UUID.randomUUID().toString());
    dead.setFailedAt(OffsetDateTime.now());
    return interviewDeadLetters.saveAndFlush(dead);
  }

  @ParameterizedTest
  @ValueSource(strings = {"get", "question", "save", "submit", "complete", "report", "detail",
      "export", "delete", "answers", "status", "index"})
  @DisplayName("热缓存不能绕过所有权，他人的会话与不存在会话一致")
  void rejectsForeignSessionEvenWithWarmCache(String operation) {
    var session = new InterviewSessionEntity();
    session.setOwner(second);
    session.setSessionId(UUID.randomUUID().toString());
    session.setQuestionsJson("[]");
    sessions.saveAndFlush(session);
    when(currentUser.requireUserId()).thenReturn(second.getId());
    cache.saveSession(session.getSessionId(), "secret", null, List.of(), 0, SessionStatus.CREATED);
    when(currentUser.requireUserId()).thenReturn(first.getId());
    assertThatThrownBy(() -> {
      var answer = new SubmitAnswerRequest(session.getSessionId(), 0, "answer");
      switch (operation) {
        case "get" -> interviewService.getSession(session.getSessionId());
        case "question" -> interviewService.getCurrentQuestionResponse(session.getSessionId());
        case "save" -> interviewService.saveAnswer(answer);
        case "submit" -> interviewService.submitAnswer(answer);
        case "complete" -> interviewService.completeInterview(session.getSessionId());
        case "report" -> interviewService.generateReport(session.getSessionId());
        case "detail" -> interviewHistory.getInterviewDetail(session.getSessionId());
        case "export" -> interviewHistory.exportInterviewPdf(session.getSessionId());
        case "delete" -> interviewService.deleteSession(session.getSessionId());
        case "answers" -> interviewPersistence.findAnswersBySessionId(session.getSessionId());
        case "status" -> interviewPersistence.updateSessionStatus(session.getSessionId(),
            InterviewSessionEntity.SessionStatus.COMPLETED);
        default -> interviewPersistence.updateCurrentQuestionIndex(session.getSessionId(), 5);
      }
    }).isInstanceOfSatisfying(BusinessException.class, error -> assertThat(error.getCode())
        .isEqualTo(ErrorCode.INTERVIEW_SESSION_NOT_FOUND.getCode()));
    assertThat(sessions.findBySessionId(session.getSessionId())).isPresent();
  }

  @Test
  @DisplayName("创建与恢复面试前必须校验简历，包括强制创建")
  void rejectsForeignResumeBeforeGeneratingQuestions() {
    var foreign = resume(second, UUID.randomUUID().toString());
    for (boolean force : List.of(false, true)) {
      assertThatThrownBy(() -> interviewService.createSession(new CreateInterviewRequest(
          "text", 3, foreign.getId(), force, null, "java", "mid", null, null)))
          .isInstanceOfSatisfying(BusinessException.class, error -> assertThat(error.getCode())
              .isEqualTo(ErrorCode.RESUME_NOT_FOUND.getCode()));
    }
    assertThatThrownBy(() -> interviewService.findUnfinishedSession(foreign.getId()))
        .isInstanceOf(BusinessException.class);
  }

  @Test
  @DisplayName("通用历史、会话列表与缓存键按用户分区")
  void isolatesHistoryAndCacheKeys() {
    var foreign = new InterviewSessionEntity();
    foreign.setOwner(second);
    foreign.setSessionId(UUID.randomUUID().toString());
    foreign.setSkillId("java");
    foreign.setQuestionsJson("[{\"questionIndex\":0,\"question\":\"secret\",\"isFollowUp\":false}]");
    sessions.saveAndFlush(foreign);
    assertThat(interviewPersistence.findAll()).isEmpty();
    assertThat(interviewPersistence.getHistoricalQuestions("java", null)).isEmpty();
    cache.saveSession("equal-id", "A", 1L, List.of(), 0, SessionStatus.CREATED);
    when(currentUser.requireUserId()).thenReturn(second.getId());
    assertThat(cache.getSession("equal-id")).isEmpty();
    cache.saveSession("equal-id", "B", 1L, List.of(), 0, SessionStatus.CREATED);
    assertThat(cachedValues.keySet()).contains("interview:session:" + first.getId() + ":equal-id",
        "interview:session:" + second.getId() + ":equal-id",
        "interview:resume:" + first.getId() + ":1", "interview:resume:" + second.getId() + ":1");
  }
}
