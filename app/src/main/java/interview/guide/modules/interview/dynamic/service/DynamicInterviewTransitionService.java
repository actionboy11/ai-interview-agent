package interview.guide.modules.interview.dynamic.service;

import interview.guide.common.model.AsyncTaskStatus;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.interview.dynamic.model.DynamicEvaluationOutboxEntity;
import interview.guide.modules.interview.dynamic.model.DynamicGroupEndReason;
import interview.guide.modules.interview.dynamic.model.DynamicInterviewRuntimeEntity;
import interview.guide.modules.interview.dynamic.model.DynamicMainQuestion;
import interview.guide.modules.interview.dynamic.model.DynamicPhase;
import interview.guide.modules.interview.dynamic.model.FollowUpDecisionEntity;
import interview.guide.modules.interview.dynamic.model.FollowUpDecisionEntity.Action;
import interview.guide.modules.interview.dynamic.model.FollowUpDecisionEntity.Reason;
import interview.guide.modules.interview.dynamic.model.FollowUpDecisionEntity.Status;
import interview.guide.modules.interview.dynamic.model.SubmissionIntent;
import interview.guide.modules.interview.dynamic.repository.DynamicEvaluationOutboxRepository;
import interview.guide.modules.interview.dynamic.repository.DynamicInterviewRuntimeRepository;
import interview.guide.modules.interview.dynamic.repository.FollowUpDecisionRepository;
import interview.guide.modules.interview.dynamic.service.DynamicInterviewProperties.PolicySnapshot;
import interview.guide.modules.interview.model.InterviewQuestionDTO;
import interview.guide.modules.interview.model.InterviewSessionEntity.SessionStatus;
import interview.guide.modules.interview.repository.InterviewAnswerRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** Shared transaction mutations. All callers acquire the runtime lock before any decision lock. */
@Service
@RequiredArgsConstructor
public class DynamicInterviewTransitionService {
  private final DynamicInterviewRuntimeRepository runtimes;
  private final FollowUpDecisionRepository decisions;
  private final DynamicEvaluationOutboxRepository outboxes;
  private final InterviewAnswerRepository answers;
  private final DynamicInterviewQueryService queries;
  private final DynamicAdvancePolicy policy;
  private final ObjectMapper mapper;
  private final Clock clock;

  public enum ApplicationResult { APPLIED, IGNORED }

  /** Audit and transition data, independent of the model adapter's output record. */
  public record DecisionApplication(Action modelAction, Reason modelReason, String reasonSummary,
      String modelDecisionJson, String followUpQuestion, DynamicGroupEndReason endReason,
      String errorCode) {}

  @Transactional
  public ApplicationResult applyDecision(String sessionId, UUID decisionId, UUID attemptToken,
      DecisionApplication application) {
    var runtime = runtimes.findBySessionIdForUpdate(sessionId).orElse(null);
    if (runtime == null || decisionId == null) { return ApplicationResult.IGNORED; }
    var decision = decisions.findByIdForUpdate(decisionId).orElse(null);
    if (!currentDecision(runtime, decision) || decision.getStatus() != Status.PROCESSING
        || attemptToken == null || !attemptToken.equals(decision.getAttemptToken())
        || decision.getLeaseUntil() == null || !clock.instant().isBefore(decision.getLeaseUntil())
        || !clock.instant().isBefore(decision.getDeadline())) {
      return ApplicationResult.IGNORED;
    }
    validate(application);
    decision.setModelAction(application.modelAction());
    decision.setModelReason(application.modelReason());
    decision.setReasonSummary(application.reasonSummary());
    decision.setModelDecisionJson(application.modelDecisionJson());
    decision.setErrorCode(application.errorCode());
    decision.setStatus(application.endReason() == DynamicGroupEndReason.SYSTEM_UNAVAILABLE
        ? Status.FAILED : Status.APPLIED);
    decision.setAttemptToken(null);
    decision.setLeaseUntil(null);
    var end = application.endReason();
    if (application.modelAction() == Action.FOLLOW_UP && end == null) {
      end = policy.closeReason(SubmissionIntent.ANSWER, runtime.getGroupUsedFollowUps(),
          runtime.getUsedFollowUps(), runtime.getMaxFollowUpsPerGroup(), runtime.getTotalFollowUpBudget());
      var actual = new ArrayList<>(queries.questions(runtime));
      if (end == null && actual.stream().anyMatch(q ->
          q.question().strip().equals(application.followUpQuestion().strip()))) {
        end = DynamicGroupEndReason.NO_USEFUL_FOLLOW_UP;
      }
      if (end == null) {
        var main = actual.stream().filter(q -> !q.isFollowUp()
            && runtime.getCurrentSlotId().equals(q.slotId())).findFirst().orElseThrow();
        actual.add(InterviewQuestionDTO.planned(actual.size(), application.followUpQuestion(),
            main.type(), main.category(), main.topicSummary(), true, main.questionIndex(), main.slotId()));
        runtime.setGroupUsedFollowUps(runtime.getGroupUsedFollowUps() + 1);
        runtime.setUsedFollowUps(runtime.getUsedFollowUps() + 1);
        runtime.setNoticeCode(null);
        publish(runtime, actual);
        decision.setFinalAction(Action.FOLLOW_UP);
      }
    }
    if (end != null) {
      decision.setFinalAction(Action.NEXT_MAIN);
      decision.setEndReason(end);
      runtime.setNoticeCode(end == DynamicGroupEndReason.SYSTEM_UNAVAILABLE
          ? application.errorCode() : null);
      advance(runtime, end);
    }
    runtimes.flush();
    decision.setAppliedRuntimeVersion(runtime.getVersion());
    return ApplicationResult.APPLIED;
  }

  @Transactional
  public ApplicationResult expireDecision(String sessionId, UUID decisionId) {
    var runtime = runtimes.findBySessionIdForUpdate(sessionId).orElse(null);
    if (runtime == null || decisionId == null) { return ApplicationResult.IGNORED; }
    var decision = decisions.findByIdForUpdate(decisionId).orElse(null);
    if (!currentDecision(runtime, decision) || clock.instant().isBefore(decision.getDeadline())) {
      return ApplicationResult.IGNORED;
    }
    decision.setStatus(Status.FAILED);
    decision.setFinalAction(Action.NEXT_MAIN);
    decision.setEndReason(DynamicGroupEndReason.SYSTEM_UNAVAILABLE);
    decision.setErrorCode("DECISION_TIMEOUT");
    decision.setAttemptToken(null);
    decision.setLeaseUntil(null);
    runtime.setNoticeCode("DECISION_TIMEOUT");
    advance(runtime, DynamicGroupEndReason.SYSTEM_UNAVAILABLE);
    runtimes.flush();
    decision.setAppliedRuntimeVersion(runtime.getVersion());
    return ApplicationResult.APPLIED;
  }

  /** Internal command collaborator; requires the managed, locked and authorized runtime. */
  @Transactional(propagation = Propagation.MANDATORY)
  public void closeGroup(DynamicInterviewRuntimeEntity runtime, DynamicGroupEndReason reason) {
    advance(runtime, reason);
  }

  private void advance(DynamicInterviewRuntimeEntity runtime, DynamicGroupEndReason reason) {
    recordEnd(runtime, reason);
    if (runtime.getCurrentMainOrdinal() + 1 == runtime.getPlannedMainQuestionCount()) {
      freeze(runtime);
      return;
    }
    int ordinal = runtime.getCurrentMainOrdinal() + 1;
    var main = pool(runtime).get(ordinal);
    var actual = new ArrayList<>(queries.questions(runtime));
    int index = actual.size();
    actual.add(InterviewQuestionDTO.planned(index, main.question(), main.type(), main.category(),
        main.topicSummary(), false, null, main.slotId()));
    runtime.setCurrentMainOrdinal(ordinal);
    runtime.setCurrentSlotId(main.slotId());
    runtime.setGroupUsedFollowUps(0);
    publish(runtime, actual);
  }

  private boolean currentDecision(DynamicInterviewRuntimeEntity runtime, FollowUpDecisionEntity task) {
    return task != null && DynamicInterviewQueryService.ownerActive(runtime)
        && runtime.getPhase() == DynamicPhase.DECISION_PENDING
        && task.getId().equals(runtime.getPendingDecisionId())
        && task.getSession().getId().equals(runtime.getSession().getId())
        && task.getOwner().getId().equals(runtime.getOwner().getId())
        && task.getExpectedRuntimeVersion() == runtime.getVersion()
        && (task.getStatus() == Status.PENDING || task.getStatus() == Status.PROCESSING);
  }

  private void validate(DecisionApplication value) {
    boolean valid = value != null && (value.reasonSummary() == null || value.reasonSummary().length() <= 500)
        && (value.errorCode() == null || value.errorCode().length() <= 64);
    if (valid && value.endReason() == DynamicGroupEndReason.SYSTEM_UNAVAILABLE) {
      valid = value.errorCode() != null && !value.errorCode().isBlank();
    } else if (valid && value.modelAction() == Action.FOLLOW_UP) {
      valid = value.modelReason() == Reason.IMPORTANT_GAP && value.endReason() == null
          && value.followUpQuestion() != null && !value.followUpQuestion().isBlank();
    } else if (valid) {
      valid = value.modelAction() == Action.NEXT_MAIN && value.modelReason() != null
          && switch (value.modelReason()) {
            case SUFFICIENT -> value.endReason() == DynamicGroupEndReason.SUFFICIENT;
            case NO_USEFUL_FOLLOW_UP -> value.endReason() == DynamicGroupEndReason.NO_USEFUL_FOLLOW_UP;
            case USER_DONT_KNOW -> value.endReason() == DynamicGroupEndReason.DONT_KNOW;
            case IMPORTANT_GAP -> false;
          };
    }
    if (!valid) { throw new BusinessException(ErrorCode.BAD_REQUEST, "决策应用参数不一致"); }
  }

  /** Early completion does not promote an existing draft to a formal answer. */
  @Transactional(propagation = Propagation.MANDATORY)
  public void finish(DynamicInterviewRuntimeEntity runtime, DynamicGroupEndReason reason) {
    if (runtime.getPhase() == DynamicPhase.COMPLETED) { return; }
    recordEnd(runtime, reason);
    freeze(runtime);
  }

  String decisionContext(DynamicInterviewRuntimeEntity runtime) {
    String slot = runtime.getCurrentSlotId();
    var questions = queries.questions(runtime).stream().filter(q -> slot.equals(q.slotId())).toList();
    var indexes = questions.stream().map(InterviewQuestionDTO::questionIndex).toList();
    var transcript = formalAnswers(runtime).stream()
        .filter(a -> indexes.contains(a.questionIndex())).toList();
    return mapper.writeValueAsString(new DecisionContextSnapshot(1,
        pool(runtime).get(runtime.getCurrentMainOrdinal()), questions, transcript,
        runtime.getGroupUsedFollowUps(), runtime.getUsedFollowUps(),
        runtime.getMaxFollowUpsPerGroup(), runtime.getTotalFollowUpBudget()));
  }

  private void recordEnd(DynamicInterviewRuntimeEntity runtime, DynamicGroupEndReason reason) {
    var ends = mapper.readValue(runtime.getGroupEndReasonsJson(), GroupEnds.class);
    var groups = new LinkedHashMap<>(ends.groups());
    if (groups.putIfAbsent(runtime.getCurrentSlotId(), reason) == null) {
      runtime.setCompletedMainCount(runtime.getCompletedMainCount() + 1);
    }
    runtime.setGroupEndReasonsJson(mapper.writeValueAsString(new GroupEnds(1, groups)));
  }

  private void freeze(DynamicInterviewRuntimeEntity runtime) {
    // Cancellation and transcript/outbox persistence share the caller's transaction.
    decisions.findBySession_IdAndStatusIn(runtime.getSession().getId(),
        List.of(Status.PENDING, Status.PROCESSING)).forEach(task -> {
          task.setStatus(Status.CANCELLED);
          task.setAttemptToken(null);
          task.setLeaseUntil(null);
        });
    runtime.setPhase(DynamicPhase.COMPLETED);
    runtime.setPendingDecisionId(null);
    long frozenVersion = runtime.getVersion() + 1;
    runtime.setFrozenVersion(frozenVersion);
    var session = runtime.getSession();
    session.setStatus(SessionStatus.COMPLETED);
    session.setCompletedAt(LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC));
    session.setEvaluateStatus(AsyncTaskStatus.PENDING);
    var frozen = new FrozenSnapshot(1, session.getSessionId(), runtime.getOwner().getId(),
        frozenVersion, pool(runtime), mapper.readValue(runtime.getPolicyJson(), PolicySnapshot.class),
        queries.questions(runtime), formalAnswers(runtime),
        mapper.readValue(runtime.getGroupEndReasonsJson(), GroupEnds.class));
    var outbox = new DynamicEvaluationOutboxEntity(runtime, frozenVersion,
        mapper.writeValueAsString(frozen), clock.instant());
    outboxes.save(outbox);
    session.setEvaluationMessageId(outbox.getMessageId().toString());
  }

  private void publish(DynamicInterviewRuntimeEntity runtime, List<InterviewQuestionDTO> actual) {
    runtime.getSession().setQuestionsJson(mapper.writeValueAsString(actual));
    runtime.getSession().setTotalQuestions(actual.size());
    runtime.getSession().setCurrentQuestionIndex(actual.size() - 1);
    runtime.setCurrentQuestionIndex(actual.size() - 1);
    runtime.setPendingDecisionId(null);
    runtime.setPhase(DynamicPhase.WAITING_ANSWER);
  }

  private List<DynamicMainQuestion> pool(DynamicInterviewRuntimeEntity runtime) {
    return mapper.readValue(runtime.getMainQuestionsJson(), mapper.getTypeFactory()
        .constructCollectionType(List.class, DynamicMainQuestion.class));
  }

  private List<FormalAnswer> formalAnswers(DynamicInterviewRuntimeEntity runtime) {
    return answers.findBySessionIdOrderByQuestionIndex(runtime.getSession().getId()).stream()
        .filter(a -> a.getRequestId() != null && a.getSubmissionOwnerId() != null
            && a.getSubmissionIntent() != null && a.getRequestFingerprint() != null)
        .map(a -> new FormalAnswer(a.getId(), a.getQuestionIndex(), a.getQuestion(),
            a.getCategoryKey(), a.getCategoryLabel(), a.getUserAnswer(), a.getSubmissionIntent(),
            a.getRequestId())).toList();
  }

  public record FormalAnswer(Long answerId, int questionIndex, String question, String categoryKey,
      String categoryLabel, String answer, SubmissionIntent submissionIntent, UUID requestId) {}
  public record GroupEnds(int schemaVersion, Map<String, DynamicGroupEndReason> groups) {
    public GroupEnds { groups = Map.copyOf(groups); }
  }
  public record FrozenSnapshot(int schemaVersion, String sessionId, UUID ownerId, long frozenVersion,
      List<DynamicMainQuestion> mainQuestions, PolicySnapshot policy,
      List<InterviewQuestionDTO> questions, List<FormalAnswer> answers, GroupEnds groupEnds) {
    public FrozenSnapshot {
      mainQuestions = List.copyOf(mainQuestions);
      questions = List.copyOf(questions);
      answers = List.copyOf(answers);
    }
  }
  public record DecisionContextSnapshot(int schemaVersion, DynamicMainQuestion mainQuestion,
      List<InterviewQuestionDTO> questions, List<FormalAnswer> answers, int groupUsedFollowUps,
      int usedFollowUps, int maxFollowUpsPerGroup, int totalFollowUpBudget) {
    public DecisionContextSnapshot {
      questions = List.copyOf(questions);
      answers = List.copyOf(answers);
    }
  }
}
