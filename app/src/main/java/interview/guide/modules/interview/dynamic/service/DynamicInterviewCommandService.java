package interview.guide.modules.interview.dynamic.service;

import interview.guide.modules.interview.dynamic.dto.DynamicRuntimeSnapshot;
import interview.guide.modules.interview.dynamic.dto.DynamicSubmissionReceipt;
import interview.guide.modules.interview.dynamic.dto.DynamicSubmitRequest;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.interview.dynamic.model.DynamicGroupEndReason;
import interview.guide.modules.interview.dynamic.model.DynamicInterviewRuntimeEntity;
import interview.guide.modules.interview.dynamic.model.DynamicPhase;
import interview.guide.modules.interview.dynamic.model.FollowUpDecisionEntity;
import interview.guide.modules.interview.dynamic.model.SubmissionIntent;
import interview.guide.modules.interview.dynamic.repository.DynamicInterviewRuntimeRepository;
import interview.guide.modules.interview.dynamic.repository.FollowUpDecisionRepository;
import interview.guide.modules.interview.model.InterviewAnswerEntity;
import interview.guide.modules.interview.model.InterviewSessionEntity.SessionStatus;
import interview.guide.modules.interview.repository.InterviewAnswerRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
@RequiredArgsConstructor
public class DynamicInterviewCommandService {
  private final DynamicInterviewRuntimeRepository runtimes;
  private final InterviewAnswerRepository answers;
  private final FollowUpDecisionRepository decisions;
  private final DynamicAdvancePolicy policy;
  private final DynamicInterviewTransitionService transitions;
  private final DynamicInterviewQueryService queries;
  private final ObjectMapper mapper;
  private final Clock clock;
  private final DynamicInterviewProperties properties;

  @Transactional
  public DynamicSubmissionReceipt submit(UUID ownerId, String sessionId, DynamicSubmitRequest request) {
    var runtime = ownedLock(ownerId, sessionId);
    validate(request);
    String fingerprint = fingerprint(sessionId, request);
    var previous = answers.findBySubmissionOwnerIdAndRequestId(ownerId, request.requestId());
    if (previous.isPresent()) {
      if (!fingerprint.equals(previous.get().getRequestFingerprint())) {
        throw invalid("请求ID已用于不同的提交内容");
      }
      UUID decisionId = decisions.findBySourceAnswer_Id(previous.get().getId())
          .map(FollowUpDecisionEntity::getId).orElse(null);
      return new DynamicSubmissionReceipt(request.requestId(), decisionId, queries.snapshot(runtime));
    }
    requireWaiting(runtime, request.questionIndex(), request.expectedVersion());
    var answer = answerRow(runtime, request.questionIndex());
    if (answer.getRequestId() != null) { throw invalid("已提交的答案不可修改"); }
    answer.setUserAnswer(request.answer());
    answer.setSubmissionIntent(request.submissionIntent());
    answer.setSubmissionOwnerId(runtime.getOwner().getId());
    answer.setRequestId(request.requestId());
    answer.setRequestFingerprint(fingerprint);
    answers.saveAndFlush(answer);
    runtime.setNoticeCode(null);
    runtime.getSession().setStatus(SessionStatus.IN_PROGRESS);
    var reason = policy.closeReason(request.submissionIntent(), runtime.getGroupUsedFollowUps(),
        runtime.getUsedFollowUps(), runtime.getMaxFollowUpsPerGroup(), runtime.getTotalFollowUpBudget());
    UUID decisionId = null;
    if (reason != null) {
      transitions.closeGroup(runtime, reason);
    } else {
      var now = clock.instant();
      var decision = new FollowUpDecisionEntity(runtime, answer, transitions.decisionContext(runtime),
          now, now.plusSeconds(runtime.getDecisionTimeoutSeconds()));
      decision.setExpectedRuntimeVersion(runtime.getVersion() + 1);
      decisions.save(decision);
      decisionId = decision.getId();
      runtime.setPendingDecisionId(decisionId);
      runtime.setPhase(DynamicPhase.DECISION_PENDING);
    }
    runtimes.flush();
    return new DynamicSubmissionReceipt(request.requestId(), decisionId, queries.snapshot(runtime));
  }

  @Transactional
  public DynamicRuntimeSnapshot complete(UUID ownerId, String sessionId, long expectedVersion) {
    var runtime = ownedLock(ownerId, sessionId);
    if (runtime.getPhase() == DynamicPhase.COMPLETED) { return queries.snapshot(runtime); }
    requireVersion(runtime, expectedVersion);
    transitions.finish(runtime, DynamicGroupEndReason.EARLY_COMPLETE);
    runtimes.flush();
    return queries.snapshot(runtime);
  }

  @Transactional
  public void saveDraft(UUID ownerId, String sessionId, int questionIndex, long expectedVersion,
      String answer) {
    validateLength(answer);
    var runtime = ownedLock(ownerId, sessionId);
    requireWaiting(runtime, questionIndex, expectedVersion);
    var row = answerRow(runtime, questionIndex);
    if (row.getRequestId() != null) { throw invalid("已提交的答案不可修改"); }
    row.setUserAnswer(answer);
    answers.save(row);
  }

  private DynamicInterviewRuntimeEntity ownedLock(UUID ownerId, String sessionId) {
    var runtime = runtimes.findBySessionIdForUpdate(sessionId)
        .orElseThrow(() -> new BusinessException(ErrorCode.INTERVIEW_SESSION_NOT_FOUND));
    DynamicInterviewQueryService.requireOwner(runtime, ownerId);
    return runtime;
  }

  private InterviewAnswerEntity answerRow(DynamicInterviewRuntimeEntity runtime, int index) {
    return answers.findBySession_SessionIdAndQuestionIndex(runtime.getSession().getSessionId(), index)
        .orElseGet(() -> {
          var question = queries.questions(runtime).get(index);
          var row = new InterviewAnswerEntity();
          row.setSession(runtime.getSession());
          row.setQuestionIndex(index);
          row.setQuestion(question.question());
          row.setCategoryKey(question.type());
          row.setCategoryLabel(question.category());
          return row;
        });
  }

  private void requireWaiting(DynamicInterviewRuntimeEntity runtime, int index, long version) {
    requireVersion(runtime, version);
    if (runtime.getPhase() != DynamicPhase.WAITING_ANSWER
        || runtime.getCurrentQuestionIndex() != index) {
      throw invalid("当前问题不允许作答，请刷新状态");
    }
  }

  private void requireVersion(DynamicInterviewRuntimeEntity runtime, long version) {
    if (version != runtime.getVersion()) { throw invalid("面试状态已更新，请刷新后重试"); }
  }

  private void validate(DynamicSubmitRequest request) {
    if (request == null || request.requestId() == null || request.submissionIntent() == null
        || request.questionIndex() < 0 || request.expectedVersion() < 0
        || (request.submissionIntent() == SubmissionIntent.ANSWER
            && (request.answer() == null || request.answer().isBlank()))) {
      throw invalid("提交参数不合法，普通回答不能为空");
    }
    validateLength(request.answer());
  }

  private void validateLength(String answer) {
    if (answer != null && answer.length() > properties.getMaxAnswerLength()) {
      throw invalid("答案长度超过允许上限");
    }
  }

  private String fingerprint(String sessionId, DynamicSubmitRequest request) {
    // Version participates in the exact request fingerprint; a network retry resends the same body.
    String json = mapper.writeValueAsString(new Fingerprint(sessionId, request));
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
          .digest(json.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException failure) {
      throw new IllegalStateException("JVM不支持SHA-256", failure);
    }
  }

  private BusinessException invalid(String message) {
    return new BusinessException(ErrorCode.BAD_REQUEST, message);
  }

  private record Fingerprint(String sessionId, DynamicSubmitRequest request) {}
}
