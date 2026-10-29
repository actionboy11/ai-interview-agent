package interview.guide.modules.interview.dynamic.service;

import interview.guide.common.ai.LlmProviderRegistry;
import interview.guide.common.ai.PromptSanitizer;
import interview.guide.common.ai.StructuredOutputInvoker;
import interview.guide.common.ai.PromptSecurityConstants;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.interview.dynamic.model.AssessmentTarget;
import interview.guide.modules.interview.dynamic.model.DynamicMainQuestion;
import interview.guide.modules.interview.planning.model.QuestionPlan;
import interview.guide.modules.interview.planning.model.QuestionSlot;
import interview.guide.modules.interview.skill.InterviewSkillService.SkillDTO;
import java.util.List;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.nio.charset.StandardCharsets;
import java.io.IOException;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class DynamicMainQuestionGenerator {
  private final LlmProviderRegistry registry;
  private final StructuredOutputInvoker invoker;
  private final PromptSanitizer sanitizer;
  private final PromptTemplate prompt;
  private final BeanOutputConverter<MainPoolOutput> converter =
      new BeanOutputConverter<>(MainPoolOutput.class);

  public DynamicMainQuestionGenerator(LlmProviderRegistry registry, StructuredOutputInvoker invoker,
      PromptSanitizer sanitizer, ResourceLoader loader) throws IOException {
    this.registry = registry;
    this.invoker = invoker;
    this.sanitizer = sanitizer;
    this.prompt = new PromptTemplate(loader.getResource(
        "classpath:prompts/interview/dynamic-main-questions.st")
        .getContentAsString(StandardCharsets.UTF_8));
  }

  public List<DynamicMainQuestion> generate(String provider, SkillDTO skill, String resumeText,
      QuestionPlan plan) {
    if (plan.slots().isEmpty() || (!plan.resumeSlots().isEmpty()
        && (resumeText == null || resumeText.isBlank()))) {
      throw invalidOutput();
    }
    Map<String, String> labels = skill.categories().stream().collect(Collectors.toMap(
        category -> category.key(), category -> category.label(), (first, second) -> first));
    Map<String, Object> variables = new HashMap<>();
    variables.put("skillName", data("skill_name", skill.name()));
    variables.put("skillDescription", data("skill_description", skill.description()));
    variables.put("persona", data("skill_persona", skill.persona()));
    variables.put("jd", data("jd", skill.sourceJd()));
    variables.put("slotSection", slotSection(plan, labels));
    variables.put("resume", plan.resumeSlots().isEmpty() ? "无简历数据；所有槽位均为方向题。"
        : data("resume", resumeText));
    var output = invoker.invoke(registry.getChatClientOrDefault(provider),
        "你是技术面试官。严格遵守服务端题槽，一次生成完整主问题池及每题的可观察目标。"
            + PromptSecurityConstants.DATA_BOUNDARY_INSTRUCTION + converter.getFormat(),
        prompt.render(variables), converter, ErrorCode.INTERVIEW_QUESTION_GENERATION_FAILED,
        "动态主问题生成失败：", "动态主问题池", provider, "unknown",
        value -> validate(value, plan, labels), log);
    Map<String, MainOutput> bySlot = output.questions().stream().collect(Collectors.toMap(
        MainOutput::slotId, question -> question));
    return plan.slots().stream().map(slot -> {
      var item = bySlot.get(slot.slotId());
      var targets = item.assessmentTargets().stream().map(target -> new AssessmentTarget(
          target.id().trim(), target.description().trim())).toList();
      return new DynamicMainQuestion(slot.slotId(), item.question().trim(), slot.categoryKey(),
          labels.get(slot.categoryKey()), item.topicSummary(), targets, slot.targetDifficulty(),
          slot.focusTopics());
    }).toList();
  }

  private void validate(MainPoolOutput output, QuestionPlan plan, Map<String, String> labels) {
    if (output == null || output.questions() == null
        || output.questions().size() != plan.slots().size()) {
      throw invalidOutput();
    }
    Map<String, QuestionSlot> expected = plan.slots().stream().collect(Collectors.toMap(
        QuestionSlot::slotId, slot -> slot));
    Set<String> seen = new HashSet<>();
    for (var item : output.questions()) {
      var slot = item == null ? null : expected.get(item.slotId());
      if (slot == null || !seen.add(item.slotId()) || blank(item.question())
          || !slot.categoryKey().equals(item.type())
          || !labels.containsKey(item.type())
          || !labels.get(item.type()).equals(item.category())
          || !slot.sourceMode().name().equals(item.sourceMode())
          || item.assessmentTargets() == null || item.assessmentTargets().size() < 2
          || item.assessmentTargets().size() > 4) {
        throw invalidOutput();
      }
      Set<String> ids = new HashSet<>();
      for (var target : item.assessmentTargets()) {
        if (target == null || blank(target.id()) || blank(target.description())
            || !ids.add(target.id().trim())) {
          throw invalidOutput();
        }
      }
    }
  }

  private String slotSection(QuestionPlan plan, Map<String, String> labels) {
    List<String> lines = new ArrayList<>();
    for (var slot : plan.slots()) {
      lines.add("slotId=" + slot.slotId() + "; categoryKey=" + slot.categoryKey()
          + "; category=" + singleLine(labels.get(slot.categoryKey()))
          + "; sourceMode=" + slot.sourceMode() + "; difficulty=" + slot.targetDifficulty()
          + "; purpose=" + slot.purpose()
          + "; focusTopics=" + topics(slot.focusTopics())
          + "; avoidedTopics=" + topics(slot.avoidedTopics()));
    }
    return String.join("\n", lines);
  }

  private String topics(List<String> topics) {
    return topics.stream().limit(3).map(this::singleLine).collect(Collectors.joining(" | "));
  }

  private String singleLine(String value) {
    if (value == null) { return ""; }
    String clean = sanitizer.sanitize(value).replaceAll("[\\r\\n]+", " ");
    return clean.substring(0, Math.min(80, clean.length()));
  }

  private String data(String tag, String value) {
    return sanitizer.wrapWithDelimiters(tag, sanitizer.sanitize(value == null ? "" : value));
  }

  private boolean blank(String value) {
    return value == null || value.isBlank();
  }

  private BusinessException invalidOutput() {
    return new BusinessException(ErrorCode.INTERVIEW_QUESTION_GENERATION_FAILED,
        "主问题必须匹配原题槽、分类和来源，每题须含二至四条编号唯一且描述非空的考查目标");
  }

  public record MainPoolOutput(List<MainOutput> questions) {}

  public record MainOutput(String slotId, String question, String type, String category,
      String sourceMode, String topicSummary, List<AssessmentTarget> assessmentTargets) {}
}
