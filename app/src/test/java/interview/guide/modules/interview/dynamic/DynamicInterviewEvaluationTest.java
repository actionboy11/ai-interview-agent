package interview.guide.modules.interview.dynamic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import interview.guide.common.ai.LlmProviderRegistry;
import interview.guide.common.ai.PromptSanitizer;
import interview.guide.common.ai.StructuredOutputInvoker;
import interview.guide.common.ai.StructuredOutputProperties;
import interview.guide.common.ai.telemetry.AiCallTelemetryRecorder;
import interview.guide.common.config.LlmProviderProperties;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.common.evaluation.UnifiedEvaluationService;
import interview.guide.modules.interview.dynamic.model.AssessmentTarget;
import interview.guide.modules.interview.dynamic.model.DynamicGroupEvaluation;
import interview.guide.modules.interview.dynamic.model.DynamicGroupEndReason;
import interview.guide.modules.interview.dynamic.model.DynamicInterviewEvaluationSnapshot;
import interview.guide.modules.interview.dynamic.model.DynamicMainQuestion;
import interview.guide.modules.interview.dynamic.model.SubmissionIntent;
import interview.guide.modules.interview.dynamic.service.DynamicInterviewEvaluationService;
import interview.guide.modules.interview.dynamic.service.DynamicInterviewEvaluationService.EvaluationOutput;
import interview.guide.modules.interview.dynamic.service.DynamicInterviewEvaluationService.QuestionFeedback;
import interview.guide.modules.interview.dynamic.service.DynamicInterviewProperties;
import interview.guide.modules.interview.dynamic.service.DynamicInterviewTransitionService.FormalAnswer;
import interview.guide.modules.interview.dynamic.service.DynamicInterviewTransitionService.FrozenSnapshot;
import interview.guide.modules.interview.dynamic.service.DynamicInterviewTransitionService.GroupEnds;
import interview.guide.modules.interview.dynamic.service.DynamicReportAggregation;
import interview.guide.modules.interview.model.InterviewQuestionDTO;
import interview.guide.modules.interview.model.InterviewSessionEntity;
import interview.guide.modules.interview.dynamic.model.InterviewExecutionMode;
import interview.guide.modules.interview.service.AnswerEvaluationService;
import interview.guide.modules.interview.service.InterviewPersistenceService;
import interview.guide.modules.interview.skill.InterviewSkillService;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.core.io.DefaultResourceLoader;
import tools.jackson.databind.json.JsonMapper;

@DisplayName("动态面试按主问题组正式计分")
class DynamicInterviewEvaluationTest {
  @Test
  @DisplayName("包含两次追问的组也只有一份权重")
  void oneGroupWithTwoFollowUpsHasOneWeight() {
    var result = DynamicReportAggregation.aggregate(List.of(
        new DynamicGroupEvaluation("one", 40, "证据", List.of(0, 1, 2), List.of("t1")),
        new DynamicGroupEvaluation("two", 80, "证据", List.of(3), List.of("t2"))));
    assertThat(result.score()).isEqualTo(60);
    assertThat(result.questionCount()).isEqualTo(2);
  }

  @Test
  @DisplayName("全部跳过没有分数而非零分")
  void allSkipHasNullScore() {
    var result = DynamicReportAggregation.aggregate(List.of(
        new DynamicGroupEvaluation("one", null, "未评估", List.of(), List.of())));
    assertThat(result.score()).isNull();
    assertThat(result.questionCount()).isZero();
  }

  @Test
  @DisplayName("真实零分仍参与平均并四舍五入")
  void zeroIsObservedAndMeanRounds() {
    var result = DynamicReportAggregation.aggregate(List.of(
        new DynamicGroupEvaluation("one", 0, "不足", List.of(0), List.of("t1")),
        new DynamicGroupEvaluation("two", 81, "证据", List.of(1), List.of("t2")),
        new DynamicGroupEvaluation("three", null, "未评估", List.of(), List.of())));
    assertThat(result.score()).isEqualTo(41);
    assertThat(result.questionCount()).isEqualTo(2);
  }

  @Test
  @DisplayName("明确不会无文本仍以正式来源评估考查点不足")
  void dontKnowWithoutTextIsObservedGap() throws Exception {
    var model = new ScriptedModel(output(30, "不会基础概念"));
    var report = evaluator(model).evaluate("test", snapshot(SubmissionIntent.DONT_KNOW, null));
    assertThat(report.overallScore()).isEqualTo(30);
    assertThat(report.groupEvaluations()).hasSize(1);
    assertThat(report.groupEvaluations().getFirst().supportingQuestionIndexes()).containsExactly(0);
    assertThat(report.groupEvaluations().getFirst().observedTargetIds()).containsExactly("t1");
    assertThat(report.questionDetails().getFirst().userAnswer()).isNull();
    assertThat(report.questionDetails().getFirst().score()).isEqualTo(30);
    assertThat(model.prompts.getFirst().getContents()).contains("DONT_KNOW", "考查点");
  }

  @Test
  @DisplayName("系统失败保留答案并排除未发布未来主问题")
  void systemFailureDoesNotInventZeroAnswer() throws Exception {
    var frozen = frozen(SubmissionIntent.ANSWER, "原始有效答案");
    var scope = DynamicInterviewEvaluationSnapshot.fromFrozen(frozen);
    assertThat(scope.groups()).hasSize(1);
    var model = new ScriptedModel(output(75, "原始答案覆盖基本目标"));
    var report = evaluator(model).evaluate("test", scope);
    assertThat(report.overallScore()).isEqualTo(75);
    assertThat(report.totalQuestions()).isEqualTo(1);
    assertThat(report.questionDetails().getFirst().userAnswer()).isEqualTo("原始有效答案");
    assertThat(report.categoryScores().getFirst().questionCount()).isEqualTo(1);
    assertThat(model.prompts.getFirst().getContents())
        .contains("原始有效答案").doesNotContain("未发布未来主问题", "SYSTEM_UNAVAILABLE");
  }

  @Test
  @DisplayName("全部跳过不调用模型且逐项分数为空")
  void skipIsNotObservable() throws Exception {
    var model = new ScriptedModel(output(99, "不得采用"));
    var report = evaluator(model).evaluate("test", snapshot(SubmissionIntent.SKIP, null));
    assertThat(report.overallScore()).isNull();
    assertThat(report.groupEvaluations()).hasSize(1);
    assertThat(report.groupEvaluations().getFirst().score()).isNull();
    assertThat(report.questionDetails().getFirst().score()).isNull();
    assertThat(report.categoryScores().getFirst().score()).isNull();
    assertThat(report.categoryScores().getFirst().questionCount()).isZero();
    assertThat(model.prompts).isEmpty();
  }

  @Test
  @DisplayName("正式评价拒绝越界分数、外来目标、未来槽位和跳过来源")
  void rejectsUntrustedEvidence() throws Exception {
    for (var group : List.of(
        new DynamicGroupEvaluation("one", 101, "越界", List.of(0), List.of("t1")),
        new DynamicGroupEvaluation("one", 50, "外来目标", List.of(0), List.of("foreign")),
        new DynamicGroupEvaluation("future", 50, "未来", List.of(0), List.of("t1")),
        new DynamicGroupEvaluation("one", 50, "伪造来源", List.of(9), List.of("t1")),
        new DynamicGroupEvaluation("one", 50, "无来源", List.of(), List.of("t1")) )) {
      var value = new EvaluationOutput(List.of(group), List.of(new QuestionFeedback(0, 50, "评价")),
          "总体", List.of(), List.of(), List.of());
      var evaluator = evaluator(new ScriptedModel(value));
      assertThatThrownBy(() -> evaluator.evaluate("test", snapshot(SubmissionIntent.ANSWER, "答案")))
          .isInstanceOf(BusinessException.class);
    }
  }

  @Test
  @DisplayName("旧逐题评价适配器不能绕过动态冻结组评分")
  void legacyAdapterRejectsDynamic() {
    var persistence = mock(InterviewPersistenceService.class);
    var session = new InterviewSessionEntity();
    session.setExecutionMode(InterviewExecutionMode.DYNAMIC_FOLLOW_UP);
    when(persistence.findBySessionIdInternal("dynamic")).thenReturn(Optional.of(session));
    var adapter = new AnswerEvaluationService(mock(UnifiedEvaluationService.class), persistence,
        mock(InterviewSkillService.class));
    assertThatThrownBy(() -> adapter.evaluateInterview(mock(ChatClient.class), "dynamic", "", List.of()))
        .isInstanceOfSatisfying(BusinessException.class,
            failure -> assertThat(failure.getCode()).isEqualTo(ErrorCode.BAD_REQUEST.getCode()));
  }

  static FrozenSnapshot frozen(SubmissionIntent intent, String answer) {
    var main = main("one", "已发布主问题");
    var q = InterviewQuestionDTO.planned(0, main.question(), "JAVA", "Java", null, false, null, "one");
    return new FrozenSnapshot(1, "dynamic", UUID.randomUUID(), 3,
        List.of(main, main("future", "未发布未来主问题")),
        new DynamicInterviewProperties().snapshot(2), List.of(q),
        List.of(new FormalAnswer(7L, 0, q.question(), "JAVA", "Java", answer, intent, UUID.randomUUID())),
        new GroupEnds(1, Map.of("one", DynamicGroupEndReason.SYSTEM_UNAVAILABLE)));
  }

  static DynamicInterviewEvaluationSnapshot snapshot(SubmissionIntent intent, String answer) {
    var frozen = frozen(intent, answer);
    return new DynamicInterviewEvaluationSnapshot(1, frozen.sessionId(), frozen.ownerId(), 3,
        List.of(new DynamicInterviewEvaluationSnapshot.Group(frozen.mainQuestions().getFirst(),
            frozen.questions(), frozen.answers())));
  }

  static DynamicMainQuestion main(String slot, String text) {
    return new DynamicMainQuestion(slot, text, "JAVA", "Java", "考查点",
        List.of(new AssessmentTarget("t1", "基础概念"), new AssessmentTarget("t2", "应用验证")),
        "MEDIUM", List.of());
  }

  static EvaluationOutput output(Integer score, String summary) {
    return new EvaluationOutput(List.of(new DynamicGroupEvaluation("one", score, summary,
        List.of(0), List.of("t1"))), List.of(new QuestionFeedback(0, score, summary)),
        "总体", List.of(), List.of("改进基础"), List.of());
  }

  static DynamicInterviewEvaluationService evaluator(ScriptedModel model) throws Exception {
    var registry = mock(LlmProviderRegistry.class);
    when(registry.getChatClientOrDefault("test")).thenReturn(ChatClient.create(model));
    var properties = new StructuredOutputProperties();
    properties.setStructuredSchemaValidationEnabled(false);
    properties.setStructuredMaxAttempts(1);
    var invoker = new StructuredOutputInvoker(properties,
        new AiCallTelemetryRecorder(properties, null, List.of()));
    return new DynamicInterviewEvaluationService(registry, invoker,
        new PromptSanitizer(new LlmProviderProperties()), new DefaultResourceLoader());
  }

  static class ScriptedModel implements ChatModel {
    final EvaluationOutput output;
    final List<Prompt> prompts = new ArrayList<>();
    ScriptedModel(EvaluationOutput output) { this.output = output; }
    public ChatResponse call(Prompt prompt) {
      prompts.add(prompt);
      return new ChatResponse(List.of(new Generation(new AssistantMessage(
          JsonMapper.builder().build().writeValueAsString(output)))));
    }
  }
}
