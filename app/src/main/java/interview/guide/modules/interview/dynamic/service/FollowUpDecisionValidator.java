package interview.guide.modules.interview.dynamic.service;

import interview.guide.modules.interview.dynamic.model.FollowUpContext;
import interview.guide.modules.interview.dynamic.model.FollowUpModelDecision;
import interview.guide.modules.interview.dynamic.model.FollowUpModelDecision.Action;
import interview.guide.modules.interview.dynamic.model.FollowUpModelDecision.Reason;
import interview.guide.modules.interview.dynamic.model.SubmissionIntent;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class FollowUpDecisionValidator {
  private final DynamicInterviewProperties properties;

  public void validateContext(FollowUpContext context) {
    if (context == null || context.snapshot() == null) { throw invalidContext(); }
    var snapshot = context.snapshot();
    var main = snapshot.mainQuestion();
    if (snapshot.schemaVersion() != 1 || main == null || blank(main.slotId())
        || blank(main.question()) || blank(main.type()) || blank(main.category())
        || main.assessmentTargets().size() < 2 || main.assessmentTargets().size() > 4
        || snapshot.questions().isEmpty() || snapshot.questions().size() > 3
        || snapshot.answers().size() != snapshot.questions().size()
        || snapshot.maxFollowUpsPerGroup() != 2 || snapshot.totalFollowUpBudget() < 1
        || snapshot.groupUsedFollowUps() < 0 || snapshot.groupUsedFollowUps() > 2
        || snapshot.usedFollowUps() < snapshot.groupUsedFollowUps()
        || snapshot.usedFollowUps() > snapshot.totalFollowUpBudget()) {
      throw invalidContext();
    }
    Set<String> targets = new HashSet<>();
    for (var target : main.assessmentTargets()) {
      if (target == null || blank(target.id()) || blank(target.description())
          || !targets.add(target.id())) { throw invalidContext(); }
    }
    Set<Long> answerIds = new HashSet<>();
    Set<UUID> requestIds = new HashSet<>();
    int mainIndex = snapshot.questions().getFirst().questionIndex();
    for (int i = 0; i < snapshot.questions().size(); i++) {
      var question = snapshot.questions().get(i);
      var answer = snapshot.answers().get(i);
      if (question == null || answer == null || question.questionIndex() != mainIndex + i
          || mainIndex < 0 || !main.slotId().equals(question.slotId())
          || !main.type().equals(question.type()) || !main.category().equals(question.category())
          || blank(question.question()) || question.isFollowUp() != (i > 0)
          || (i == 0 && (!main.question().equals(question.question())
              || question.parentQuestionIndex() != null))
          || (i > 0 && !Integer.valueOf(mainIndex).equals(question.parentQuestionIndex()))
          || answer.questionIndex() != question.questionIndex()
          || !question.question().equals(answer.question())
          || !main.type().equals(answer.categoryKey())
          || !main.category().equals(answer.categoryLabel())
          || answer.answerId() == null || !answerIds.add(answer.answerId())
          || answer.requestId() == null || !requestIds.add(answer.requestId())
          || answer.submissionIntent() == null
          || (answer.submissionIntent() == SubmissionIntent.ANSWER && blank(answer.answer()))
          || (answer.answer() != null && length(answer.answer()) > properties.getMaxAnswerLength())
          || (i < snapshot.answers().size() - 1
              && answer.submissionIntent() != SubmissionIntent.ANSWER)) {
        throw invalidContext();
      }
    }
    if (snapshot.groupUsedFollowUps() != snapshot.questions().size() - 1) {
      throw invalidContext();
    }
  }

  public void validate(FollowUpContext context, FollowUpModelDecision decision) {
    validateContext(context);
    var snapshot = context.snapshot();
    Set<String> targets = new HashSet<>();
    snapshot.mainQuestion().assessmentTargets().forEach(target -> targets.add(target.id()));
    if (decision == null || decision.action() == null || decision.reasonCode() == null
        || decision.reasonSummary() == null || length(decision.reasonSummary()) > 500
        || !validIds(decision.coveredTargetIds(), targets)
        || !validIds(decision.missingTargetIds(), targets)) { throw invalidDecision(); }
    Set<String> intersection = new HashSet<>(decision.coveredTargetIds());
    intersection.retainAll(decision.missingTargetIds());
    if (!intersection.isEmpty()) { throw invalidDecision(); }
    if (decision.action() == Action.FOLLOW_UP) {
      if (decision.reasonCode() != Reason.IMPORTANT_GAP || decision.missingTargetIds().isEmpty()
          || blank(decision.followUpQuestion()) || length(decision.followUpQuestion()) > 1000
          || snapshot.groupUsedFollowUps() >= snapshot.maxFollowUpsPerGroup()
          || snapshot.usedFollowUps() >= snapshot.totalFollowUpBudget()
          || snapshot.answers().getLast().submissionIntent() != SubmissionIntent.ANSWER
          || snapshot.questions().stream().anyMatch(question -> question.question().strip()
              .equals(decision.followUpQuestion().strip()))) { throw invalidDecision(); }
    } else if (decision.reasonCode() == Reason.IMPORTANT_GAP
        || (decision.followUpQuestion() != null && !decision.followUpQuestion().isEmpty())
        || (decision.reasonCode() == Reason.SUFFICIENT && !decision.missingTargetIds().isEmpty())) {
      throw invalidDecision();
    }
  }

  private boolean validIds(List<String> ids, Set<String> allowed) {
    return ids != null && new HashSet<>(ids).size() == ids.size()
        && ids.stream().allMatch(id -> id != null && allowed.contains(id));
  }

  private boolean blank(String text) { return text == null || text.isBlank(); }
  private int length(String text) { return text.length(); }
  private BusinessException invalidContext() {
    return new BusinessException(ErrorCode.BAD_REQUEST, "追问上下文版本、正式问答、目标或预算无效，答案不得超长");
  }
  private BusinessException invalidDecision() {
    return new BusinessException(ErrorCode.INTERVIEW_QUESTION_GENERATION_FAILED,
        "追问建议不满足动作、理由、目标、题面或预算约束");
  }
}
