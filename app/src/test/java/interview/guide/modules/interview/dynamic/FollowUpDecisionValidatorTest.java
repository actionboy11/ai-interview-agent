package interview.guide.modules.interview.dynamic;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import interview.guide.common.exception.BusinessException;
import interview.guide.modules.interview.dynamic.model.AssessmentTarget;
import interview.guide.modules.interview.dynamic.model.DynamicMainQuestion;
import interview.guide.modules.interview.dynamic.model.FollowUpContext;
import interview.guide.modules.interview.dynamic.model.FollowUpModelDecision;
import interview.guide.modules.interview.dynamic.model.FollowUpModelDecision.Action;
import interview.guide.modules.interview.dynamic.model.FollowUpModelDecision.Reason;
import interview.guide.modules.interview.dynamic.model.SubmissionIntent;
import interview.guide.modules.interview.dynamic.service.DynamicInterviewProperties;
import interview.guide.modules.interview.dynamic.service.DynamicInterviewTransitionService.DecisionContextSnapshot;
import interview.guide.modules.interview.dynamic.service.DynamicInterviewTransitionService.FormalAnswer;
import interview.guide.modules.interview.dynamic.service.FollowUpDecisionValidator;
import interview.guide.modules.interview.model.InterviewQuestionDTO;
import java.util.List;
import java.util.ArrayList;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("追问建议的服务端边界")
class FollowUpDecisionValidatorTest {
  final FollowUpDecisionValidator validator = new FollowUpDecisionValidator(
      new DynamicInterviewProperties());

  @Test
  @DisplayName("拒绝未知目标和当前组已发布的完全相同题面")
  void rejectsUnknownTargetAndExactRepeatedQuestion() {
    var context = context("分析堆和GC根", "不会发生内存泄漏", 0, 0);
    assertRejected(context, decision(Action.FOLLOW_UP, Reason.IMPORTANT_GAP,
        List.of("foreign"), List.of("t2"), "如何验证泄漏？"));
    assertRejected(context, decision(Action.FOLLOW_UP, Reason.IMPORTANT_GAP,
        List.of("t1"), List.of("t2"), " 分析堆和GC根 "));
    assertRejected(context("主问题", "回答", 1, 1), decision(Action.FOLLOW_UP, Reason.IMPORTANT_GAP,
        List.of("t1"), List.of("t2"), "已发追问1"));
  }

  @Test
  @DisplayName("目标不可重复或交叠，追问必须有合法缺口及余量")
  void rejectsContradictoryTargetsAndExhaustedBudget() {
    var context = context("分析堆和GC根", "已回答", 0, 0);
    assertRejected(context, decision(Action.FOLLOW_UP, Reason.IMPORTANT_GAP,
        List.of("t1", "t1"), List.of("t2"), "新问题"));
    assertRejected(context, decision(Action.FOLLOW_UP, Reason.IMPORTANT_GAP,
        List.of("t1"), List.of("t1"), "新问题"));
    assertRejected(context, decision(Action.FOLLOW_UP, Reason.IMPORTANT_GAP,
        List.of("t1", "t2"), List.of(), "新问题"));
    assertRejected(context("主问题", "已回答", 2, 2), decision(Action.FOLLOW_UP,
        Reason.IMPORTANT_GAP, List.of("t1"), List.of("t2"), "新问题"));
    assertRejected(context("主问题", "已回答", 0, 2), decision(Action.FOLLOW_UP,
        Reason.IMPORTANT_GAP, List.of("t1"), List.of("t2"), "新问题"));
  }

  @Test
  @DisplayName("答案上限使用同一集中配置，边界完整保留且过长明确失败")
  void answerLengthUsesConfiguredLimit() {
    var properties = new DynamicInterviewProperties();
    properties.setMaxAnswerLength(3);
    var configured = new FollowUpDecisionValidator(properties);
    assertThatCode(() -> configured.validateContext(context("主问题", "完整答", 0, 0)))
        .doesNotThrowAnyException();
    assertThatThrownBy(() -> configured.validateContext(context("主问题", "完整答案", 0, 0)))
        .isInstanceOf(BusinessException.class);
  }

  @Test
  @DisplayName("动作理由及题面长度遵守固定协议")
  void validatesActionReasonAndLength() {
    var context = context("主问题", "已回答", 0, 0);
    assertRejected(context, decision(Action.NEXT_MAIN, Reason.SUFFICIENT,
        List.of("t1", "t2"), List.of(), "额外题面"));
    assertRejected(context, decision(Action.FOLLOW_UP, Reason.SUFFICIENT,
        List.of("t1"), List.of("t2"), "追问"));
    assertRejected(context, decision(Action.FOLLOW_UP, Reason.IMPORTANT_GAP,
        List.of("t1"), List.of("t2"), " "));
    assertRejected(context, decision(Action.FOLLOW_UP, Reason.IMPORTANT_GAP,
        List.of("t1"), List.of("t2"), "问".repeat(1001)));
    assertRejected(context, new FollowUpModelDecision(Action.NEXT_MAIN, Reason.SUFFICIENT,
        "理".repeat(501), List.of("t1", "t2"), List.of(), ""));
    assertThatCode(() -> validator.validate(context, new FollowUpModelDecision(Action.FOLLOW_UP,
        Reason.IMPORTANT_GAP, "理".repeat(500), List.of("t1"), List.of("t2"), "问".repeat(1000))))
        .doesNotThrowAnyException();
  }

  @Test
  @DisplayName("拒绝未知快照版本、跨组问答及超长答案而不截断")
  void rejectsUnsafeContextAndOverlongAnswer() {
    var original = context("主问题", "完整回答", 0, 0).snapshot();
    var unknown = new DecisionContextSnapshot(2, original.mainQuestion(), original.questions(),
        original.answers(), 0, 0, 2, 2);
    assertThatThrownBy(() -> validator.validateContext(new FollowUpContext(unknown)))
        .isInstanceOf(BusinessException.class);
    var foreign = InterviewQuestionDTO.planned(0, "别组", "JAVA", "Java", null,
        false, null, "foreign");
    var unsafe = new DecisionContextSnapshot(1, original.mainQuestion(), List.of(foreign),
        original.answers(), 0, 0, 2, 2);
    assertThatThrownBy(() -> validator.validateContext(new FollowUpContext(unsafe)))
        .isInstanceOf(BusinessException.class);
    assertThatThrownBy(() -> validator.validateContext(context("主问题", "答".repeat(20001), 0, 0)))
        .isInstanceOf(BusinessException.class);
  }

  void assertRejected(FollowUpContext context, FollowUpModelDecision decision) {
    assertThatThrownBy(() -> validator.validate(context, decision))
        .isInstanceOf(BusinessException.class);
  }

  static FollowUpModelDecision decision(Action action, Reason reason, List<String> covered,
      List<String> missing, String question) {
    return new FollowUpModelDecision(action, reason, "依据本组正式回答", covered, missing, question);
  }

  static FollowUpContext context(String question, String answer, int groupUsed, int used) {
    var main = new DynamicMainQuestion("slot-1", question, "JAVA", "Java", "堆分析",
        List.of(new AssessmentTarget("t1", "解释GC根"), new AssessmentTarget("t2", "验证泄漏")),
        "mid", List.of("诊断"));
    var questions = new ArrayList<InterviewQuestionDTO>();
    var answers = new ArrayList<FormalAnswer>();
    for (int i = 0; i <= groupUsed; i++) {
      String text = i == 0 ? question : "已发追问" + i;
      questions.add(InterviewQuestionDTO.planned(i, text, "JAVA", "Java", "堆分析", i > 0,
          i == 0 ? null : 0, "slot-1"));
      answers.add(new FormalAnswer((long) i + 1, i, text, "JAVA", "Java", answer,
          SubmissionIntent.ANSWER, UUID.randomUUID()));
    }
    return new FollowUpContext(new DecisionContextSnapshot(1, main, questions,
        answers, groupUsed, used, 2, 2));
  }
}
