package interview.guide.modules.interview.dynamic.service;

import interview.guide.common.ai.LlmProviderRegistry;
import interview.guide.common.ai.PromptSanitizer;
import interview.guide.common.ai.StructuredOutputInvoker;
import interview.guide.common.ai.PromptSecurityConstants;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.interview.dynamic.model.DynamicGroupEvaluation;
import interview.guide.modules.interview.dynamic.model.DynamicInterviewEvaluationSnapshot;
import interview.guide.modules.interview.dynamic.model.DynamicInterviewEvaluationSnapshot.Group;
import interview.guide.modules.interview.dynamic.model.InterviewExecutionMode;
import interview.guide.modules.interview.dynamic.model.SubmissionIntent;
import interview.guide.modules.interview.dynamic.service.DynamicInterviewTransitionService.FormalAnswer;
import interview.guide.modules.interview.model.InterviewReportDTO;
import interview.guide.modules.interview.model.InterviewReportDTO.CategoryScore;
import interview.guide.modules.interview.model.InterviewReportDTO.QuestionEvaluation;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

@Service
@Slf4j
public class DynamicInterviewEvaluationService {
  private final LlmProviderRegistry registry;
  private final StructuredOutputInvoker invoker;
  private final PromptSanitizer sanitizer;
  private final PromptTemplate prompt;
  private final JsonMapper mapper = JsonMapper.builder().build();
  private final BeanOutputConverter<EvaluationOutput> converter =
      new BeanOutputConverter<>(EvaluationOutput.class);

  public DynamicInterviewEvaluationService(LlmProviderRegistry registry,
      StructuredOutputInvoker invoker, PromptSanitizer sanitizer, ResourceLoader loader)
      throws IOException {
    this.registry = registry;
    this.invoker = invoker;
    this.sanitizer = sanitizer;
    this.prompt = new PromptTemplate(loader.getResource(
        "classpath:prompts/interview/dynamic-evaluation.st")
        .getContentAsString(StandardCharsets.UTF_8));
  }

  public InterviewReportDTO evaluate(String provider, DynamicInterviewEvaluationSnapshot snapshot) {
    validateSnapshot(snapshot);
    EvaluationOutput output;
    if (snapshot.groups().stream().flatMap(group -> group.answers().stream())
        .noneMatch(DynamicInterviewEvaluationService::observed)) {
      output = new EvaluationOutput(snapshot.groups().stream().map(group ->
          new DynamicGroupEvaluation(group.mainQuestion().slotId(), null, "未评估",
              List.of(), List.of())).toList(),
          snapshot.groups().stream().flatMap(group -> group.questions().stream())
              .map(question -> new QuestionFeedback(question.questionIndex(), null, "未评估")).toList(),
          "没有可用于评分的正式观测，未评估。", List.of(), List.of(), List.of());
    } else {
      String data = sanitizer.wrapWithDelimiters("formal_interview_evidence",
          mapper.writeValueAsString(snapshot));
      output = invoker.invoke(registry.getChatClientOrDefault(provider),
          "你是技术面试评价员。仅根据已发布主问题组中的正式作答评价考查点。"
              + PromptSecurityConstants.DATA_BOUNDARY_INSTRUCTION + converter.getFormat(),
          prompt.render(Map.of("context", data)), converter,
          ErrorCode.INTERVIEW_EVALUATION_FAILED, "动态面试评价失败：", "动态主问题组评价",
          provider, "unknown", value -> validateOutput(snapshot, value), log);
    }
    validateOutput(snapshot, output);
    var byIndex = output.questionFeedbacks().stream().collect(Collectors.toMap(
        QuestionFeedback::questionIndex, Function.identity()));
    var details = new ArrayList<QuestionEvaluation>();
    for (var group : snapshot.groups()) {
      var answers = group.answers().stream().collect(Collectors.toMap(
          FormalAnswer::questionIndex, Function.identity()));
      for (var question : group.questions()) {
        var answer = answers.get(question.questionIndex());
        var feedback = byIndex.get(question.questionIndex());
        details.add(new QuestionEvaluation(question.questionIndex(), question.question(),
            group.mainQuestion().type(), group.mainQuestion().category(),
            answer == null ? null : answer.answer(), feedback.score(), feedback.feedback()));
      }
    }
    return new InterviewReportDTO(snapshot.sessionId(), details.size(),
        DynamicReportAggregation.aggregate(output.groupEvaluations()).score(),
        categoryScores(snapshot, output.groupEvaluations()), details, output.overallFeedback(),
        output.strengths(), output.improvements(), output.referenceAnswers(),
        output.groupEvaluations(), InterviewExecutionMode.DYNAMIC_FOLLOW_UP, 1,
        snapshot.frozenVersion());
  }

  private static List<CategoryScore> categoryScores(DynamicInterviewEvaluationSnapshot snapshot,
      List<DynamicGroupEvaluation> groups) {
    var bySlot = groups.stream().collect(Collectors.toMap(DynamicGroupEvaluation::slotId,
        Function.identity()));
    var categories = new LinkedHashMap<String, List<DynamicGroupEvaluation>>();
    var labels = new LinkedHashMap<String, String>();
    for (var group : snapshot.groups()) {
      var main = group.mainQuestion();
      categories.computeIfAbsent(main.type(), ignored -> new ArrayList<>()).add(bySlot.get(main.slotId()));
      labels.putIfAbsent(main.type(), main.category());
    }
    return categories.entrySet().stream().map(entry -> {
      var result = DynamicReportAggregation.aggregate(entry.getValue());
      return new CategoryScore(entry.getKey(), labels.get(entry.getKey()), result.score(),
          result.questionCount());
    }).toList();
  }

  /** Persistence rechecks the report against frozen evidence before committing any feedback. */
  public static void validateReport(DynamicInterviewEvaluationSnapshot snapshot,
      InterviewReportDTO report) {
    validateSnapshot(snapshot);
    if (report == null || !snapshot.sessionId().equals(report.sessionId())
        || report.executionMode() != InterviewExecutionMode.DYNAMIC_FOLLOW_UP
        || !Objects.equals(report.groupEvaluationSchemaVersion(), 1)
        || !Objects.equals(report.frozenVersion(), snapshot.frozenVersion())
        || report.questionDetails() == null) { throw invalid(); }
    validateOutput(snapshot, new EvaluationOutput(report.groupEvaluations(),
        report.questionDetails().stream().map(detail -> new QuestionFeedback(detail.questionIndex(),
            detail.score(), detail.feedback())).toList(), report.overallFeedback(),
        report.strengths(), report.improvements(), report.referenceAnswers()));
    var questions = snapshot.groups().stream().flatMap(group -> group.questions().stream())
        .collect(Collectors.toMap(question -> question.questionIndex(), Function.identity()));
    var answers = snapshot.groups().stream().flatMap(group -> group.answers().stream())
        .collect(Collectors.toMap(FormalAnswer::questionIndex, Function.identity()));
    if (report.totalQuestions() != questions.size()
        || !Objects.equals(report.overallScore(),
            DynamicReportAggregation.aggregate(report.groupEvaluations()).score())
        || !categoryScores(snapshot, report.groupEvaluations()).equals(report.categoryScores())) {
      throw invalid();
    }
    for (var detail : report.questionDetails()) {
      var q = questions.get(detail.questionIndex());
      var answer = answers.get(detail.questionIndex());
      if (!Objects.equals(q.question(), detail.question())
          || !Objects.equals(q.type(), detail.categoryKey())
          || !Objects.equals(q.category(), detail.categoryLabel())
          || !Objects.equals(answer == null ? null : answer.answer(), detail.userAnswer())) {
        throw invalid();
      }
    }
  }

  private static void validateSnapshot(DynamicInterviewEvaluationSnapshot snapshot) {
    if (snapshot == null || snapshot.schemaVersion() != 1 || snapshot.ownerId() == null
        || blank(snapshot.sessionId()) || snapshot.frozenVersion() < 0 || snapshot.groups().isEmpty()) {
      throw invalid();
    }
    Set<String> slots = new HashSet<>();
    Set<Long> answerIds = new HashSet<>();
    Set<UUID> requestIds = new HashSet<>();
    int index = 0;
    for (var group : snapshot.groups()) {
      var main = group.mainQuestion();
      if (main == null || blank(main.slotId()) || !slots.add(main.slotId())
          || blank(main.question()) || blank(main.type()) || blank(main.category())
          || main.assessmentTargets() == null || main.assessmentTargets().size() < 2
          || main.assessmentTargets().size() > 4 || group.questions().isEmpty()
          || group.questions().size() > 3) { throw invalid(); }
      var targets = new HashSet<String>();
      for (var target : main.assessmentTargets()) {
        if (target == null || blank(target.id()) || blank(target.description())
            || !targets.add(target.id())) { throw invalid(); }
      }
      int mainIndex = index;
      Set<Integer> indexes = new HashSet<>();
      for (var q : group.questions()) {
        if (q == null || q.questionIndex() != index++ || !main.slotId().equals(q.slotId())
            || !Objects.equals(main.type(), q.type()) || !Objects.equals(main.category(), q.category())
            || blank(q.question()) || (q.questionIndex() == mainIndex
                ? q.isFollowUp() || q.parentQuestionIndex() != null || !main.question().equals(q.question())
                : !q.isFollowUp() || !Objects.equals(q.parentQuestionIndex(), mainIndex))) {
          throw invalid();
        }
        indexes.add(q.questionIndex());
      }
      var seenAnswers = new HashSet<Integer>();
      for (var answer : group.answers()) {
        if (answer == null || !indexes.contains(answer.questionIndex())
            || !seenAnswers.add(answer.questionIndex()) || answer.answerId() == null
            || answer.answerId() < 1 || !answerIds.add(answer.answerId()) || answer.requestId() == null
            || !requestIds.add(answer.requestId()) || answer.submissionIntent() == null
            || !main.type().equals(answer.categoryKey()) || !main.category().equals(answer.categoryLabel())
            || !group.questions().stream().anyMatch(q -> q.questionIndex() == answer.questionIndex()
                && q.question().equals(answer.question()))
            || (answer.submissionIntent() == SubmissionIntent.ANSWER && blank(answer.answer()))) {
          throw invalid();
        }
      }
    }
  }

  private static void validateOutput(DynamicInterviewEvaluationSnapshot snapshot,
      EvaluationOutput output) {
    if (output == null || output.groupEvaluations() == null || output.questionFeedbacks() == null
        || blank(output.overallFeedback()) || output.strengths() == null || output.improvements() == null
        || output.referenceAnswers() == null || output.groupEvaluations().size() != snapshot.groups().size()) {
      throw invalid();
    }
    var slots = snapshot.groups().stream().collect(Collectors.toMap(group -> group.mainQuestion().slotId(),
        Function.identity()));
    var seenSlots = new HashSet<String>();
    for (var evaluation : output.groupEvaluations()) {
      Group group = evaluation == null ? null : slots.get(evaluation.slotId());
      if (group == null || !seenSlots.add(evaluation.slotId()) || blank(evaluation.evidenceSummary())
          || evaluation.supportingQuestionIndexes() == null || evaluation.observedTargetIds() == null
          || !unique(evaluation.supportingQuestionIndexes()) || !unique(evaluation.observedTargetIds())) {
        throw invalid();
      }
      var observedIndexes = group.answers().stream().filter(DynamicInterviewEvaluationService::observed)
          .map(FormalAnswer::questionIndex).toList();
      var targetIds = group.mainQuestion().assessmentTargets().stream().map(target -> target.id()).toList();
      if (!observedIndexes.containsAll(evaluation.supportingQuestionIndexes())
          || !targetIds.containsAll(evaluation.observedTargetIds())
          || (observedIndexes.isEmpty() ? evaluation.score() != null
              || !evaluation.supportingQuestionIndexes().isEmpty() || !evaluation.observedTargetIds().isEmpty()
              : !validScore(evaluation.score()) || evaluation.supportingQuestionIndexes().isEmpty()
                  || evaluation.observedTargetIds().isEmpty())
          || !evaluation.supportingQuestionIndexes().containsAll(group.answers().stream()
              .filter(answer -> answer.submissionIntent() == SubmissionIntent.DONT_KNOW)
              .map(FormalAnswer::questionIndex).toList())) { throw invalid(); }
    }
    var questions = snapshot.groups().stream().flatMap(group -> group.questions().stream())
        .collect(Collectors.toMap(question -> question.questionIndex(), Function.identity()));
    var answers = snapshot.groups().stream().flatMap(group -> group.answers().stream())
        .collect(Collectors.toMap(FormalAnswer::questionIndex, Function.identity()));
    var seen = new HashSet<Integer>();
    for (var feedback : output.questionFeedbacks()) {
      if (feedback == null || !questions.containsKey(feedback.questionIndex())
          || !seen.add(feedback.questionIndex()) || blank(feedback.feedback())
          || (observed(answers.get(feedback.questionIndex()))
              ? !validScore(feedback.score()) : feedback.score() != null)) { throw invalid(); }
    }
    if (seen.size() != questions.size()) { throw invalid(); }
    seen.clear();
    for (var reference : output.referenceAnswers()) {
      if (reference == null || !questions.containsKey(reference.questionIndex())
          || !seen.add(reference.questionIndex()) || blank(reference.referenceAnswer())
          || reference.keyPoints() == null
          || !questions.get(reference.questionIndex()).question().equals(reference.question())) {
        throw invalid();
      }
    }
  }

  private static boolean observed(FormalAnswer answer) {
    return answer != null && (answer.submissionIntent() == SubmissionIntent.DONT_KNOW
        || answer.submissionIntent() == SubmissionIntent.ANSWER && !blank(answer.answer()));
  }
  private static boolean unique(List<?> values) {
    return values.stream().noneMatch(Objects::isNull) && new HashSet<>(values).size() == values.size();
  }
  private static boolean validScore(Integer score) { return score != null && score >= 0 && score <= 100; }
  private static boolean blank(String value) { return value == null || value.isBlank(); }
  private static BusinessException invalid() {
    return new BusinessException(ErrorCode.INTERVIEW_EVALUATION_FAILED, "正式评价与冻结证据不一致");
  }

  public record EvaluationOutput(List<DynamicGroupEvaluation> groupEvaluations,
      List<QuestionFeedback> questionFeedbacks, String overallFeedback,
      List<String> strengths, List<String> improvements,
      List<InterviewReportDTO.ReferenceAnswer> referenceAnswers) {}
  public record QuestionFeedback(int questionIndex, Integer score, String feedback) {}
}
