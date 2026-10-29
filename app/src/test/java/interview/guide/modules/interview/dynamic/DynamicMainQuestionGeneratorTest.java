package interview.guide.modules.interview.dynamic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import interview.guide.common.ai.LlmProviderRegistry;
import interview.guide.common.ai.PromptSanitizer;
import interview.guide.common.ai.StructuredOutputInvoker;
import interview.guide.common.ai.StructuredOutputProperties;
import interview.guide.common.ai.telemetry.AiCallObservation;
import interview.guide.common.ai.telemetry.AiCallTelemetryRecorder;
import interview.guide.common.ai.telemetry.AiParseOutcome;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.config.LlmProviderProperties;
import interview.guide.modules.interview.dynamic.service.DynamicMainQuestionGenerator;
import interview.guide.modules.interview.planning.model.PlanningMode;
import interview.guide.modules.interview.planning.model.QuestionPlan;
import interview.guide.modules.interview.planning.model.QuestionPurpose;
import interview.guide.modules.interview.planning.model.QuestionSlot;
import interview.guide.modules.interview.planning.model.QuestionSourceMode;
import interview.guide.modules.interview.skill.InterviewSkillService.SkillCategoryDTO;
import interview.guide.modules.interview.skill.InterviewSkillService.SkillDTO;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.core.io.DefaultResourceLoader;
import tools.jackson.databind.json.JsonMapper;

@DisplayName("动态主问题与可观察考查目标生成")
class DynamicMainQuestionGeneratorTest {
  static final SkillDTO SKILL = new SkillDTO("java", "Java", "后端", List.of(
      new SkillCategoryDTO("JAVA", "Java", "CORE", null, false)), true, null, null, null);

  @Test
  @DisplayName("八槽位一次生成八道主问题，按原计划顺序且不预生成追问")
  void eightSlotsGenerateEightMainsWithoutStaticFollowUps() throws Exception {
    var plan = plan(8, QuestionSourceMode.RESUME);
    var rows = new ArrayList<>(rows(plan, 2));
    Collections.reverse(rows);
    var fixture = fixture(json(rows));

    var result = fixture.generator().generate("test", SKILL, "Java 简历事实", plan);

    assertThat(result).hasSize(8);
    assertThat(result).extracting(item -> item.slotId()).containsExactly(
        "slot-1", "slot-2", "slot-3", "slot-4", "slot-5", "slot-6", "slot-7", "slot-8");
    assertThat(result).allSatisfy(item -> {
      assertThat(item.assessmentTargets()).hasSize(2);
      assertThat(item.type()).isEqualTo("JAVA");
      assertThat(item.difficulty()).isEqualTo("mid");
      assertThat(item.profileFocus()).containsExactly("事务边界");
    });
    assertThat(fixture.model().calls.get()).isEqualTo(1);
    assertThat(plan.version()).isEqualTo(2);
    assertThat(plan.slots().getFirst().sourceMode()).isEqualTo(QuestionSourceMode.RESUME);
    assertThat(JsonMapper.builder().build().writeValueAsString(result)).doesNotContain("followUps");
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1, 5})
  @DisplayName("每题目标必须为二至四条，耗尽统一有限重试后失败")
  void requiresTwoToFourUniqueTargets(int count) throws Exception {
    var plan = plan(1, QuestionSourceMode.DIRECTION);
    var fixture = fixture(json(rows(plan, count)));
    assertThatThrownBy(() -> fixture.generator().generate("test", SKILL, null, plan))
        .isInstanceOf(BusinessException.class);
    assertThat(fixture.model().calls.get()).isEqualTo(2);
    assertThat(fixture.observations()).hasSize(2).allSatisfy(observation -> {
      assertThat(observation.parseOutcome()).isEqualTo(AiParseOutcome.PARSE_FAILURE);
      assertThat(observation.rawResponse()).isEqualTo(json(rows(plan, count)));
    });
  }

  @Test
  @DisplayName("重复目标编号经统一重试修复，只记录最终成功")
  void duplicateTargetsRetryThroughSharedInvoker() throws Exception {
    var plan = plan(1, QuestionSourceMode.DIRECTION);
    String valid = json(rows(plan, 4));
    var fixture = fixture(valid.replace("target-2", "target-1"), valid);
    var result = fixture.generator().generate("test", SKILL, "不应进入方向题的简历", plan);
    assertThat(result.getFirst().assessmentTargets()).hasSize(4);
    assertThat(fixture.model().calls.get()).isEqualTo(2);
    assertThat(fixture.observations()).extracting(AiCallObservation::parseOutcome)
        .containsExactly(AiParseOutcome.PARSE_FAILURE, AiParseOutcome.DIRECT_SUCCESS);
    assertThat(fixture.model().prompts.getFirst().getContents())
        .contains("DIRECTION").doesNotContain("不应进入方向题的简历");
  }

  @ParameterizedTest
  @ValueSource(strings = {"slot-1", "JAVA", "DIRECTION"})
  @DisplayName("拒绝未知题槽、错误分类或来源模式，不重写原计划")
  void rejectsSlotCategoryOrSourceMismatch(String field) throws Exception {
    var plan = plan(1, QuestionSourceMode.DIRECTION);
    var fixture = fixture(json(rows(plan, 2)).replace(field, "INVALID"));
    assertThatThrownBy(() -> fixture.generator().generate("test", SKILL, null, plan))
        .isInstanceOf(BusinessException.class);
    assertThat(plan.slots().getFirst().slotId()).isEqualTo("slot-1");
    assertThat(fixture.model().calls.get()).isEqualTo(2);
  }

  @Test
  @DisplayName("空白目标描述不能成为不可变目标快照")
  void blankDescriptionIsRejected() throws Exception {
    var plan = plan(1, QuestionSourceMode.DIRECTION);
    var fixture = fixture(json(rows(plan, 2)).replace("观察点2", " "));
    assertThatThrownBy(() -> fixture.generator().generate("test", SKILL, null, plan))
        .isInstanceOf(BusinessException.class);
  }

  @Test
  @DisplayName("同一调用显式区分简历与方向槽位，保留各自来源约束")
  void mixedSourcesShareOneCallWithoutChangingSlotModes() throws Exception {
    var resumeSlot = plan(1, QuestionSourceMode.RESUME).slots().getFirst();
    var directionSlot = new QuestionSlot("slot-2", "JAVA", QuestionPurpose.BASELINE,
        "mid", QuestionSourceMode.DIRECTION, List.of(), List.of(), null);
    var plan = new QuestionPlan(2, PlanningMode.ADAPTIVE,
        List.of(resumeSlot, directionSlot), null);
    var fixture = fixture(json(rows(plan, 2)));

    var result = fixture.generator().generate("test", SKILL, "Java 项目事实", plan);

    assertThat(result).extracting(item -> item.slotId()).containsExactly("slot-1", "slot-2");
    assertThat(fixture.model().calls.get()).isEqualTo(1);
    assertThat(fixture.model().prompts.getFirst().getContents()).contains("sourceMode=RESUME",
        "sourceMode=DIRECTION", "Java 项目事实", "禁止使用候选人的简历事实");
    assertThat(plan.directionSlots()).containsExactly(directionSlot);
    assertThat(plan.resumeSlots()).containsExactly(resumeSlot);
  }

  @Test
  @DisplayName("简历槽位缺少简历事实时直接失败，不让模型虚构事实")
  void resumeSlotsRequireResumeFacts() throws Exception {
    var plan = plan(1, QuestionSourceMode.RESUME);
    var fixture = fixture(json(rows(plan, 2)));
    assertThatThrownBy(() -> fixture.generator().generate("test", SKILL, " ", plan))
        .isInstanceOf(BusinessException.class);
    assertThat(fixture.model().calls.get()).isZero();
  }

  static QuestionPlan plan(int count, QuestionSourceMode source) {
    return new QuestionPlan(2, PlanningMode.ADAPTIVE, IntStream.rangeClosed(1, count)
        .mapToObj(i -> new QuestionSlot("slot-" + i, "JAVA", QuestionPurpose.BASELINE,
            "mid", source, List.of("事务边界"), List.of("锁"), 1L)).toList(), null);
  }

  static List<Map<String, Object>> rows(QuestionPlan plan, int targetCount) {
    return plan.slots().stream().map(slot -> Map.<String, Object>of(
        "slotId", slot.slotId(), "question", "解释事务" + slot.slotId(), "type", "JAVA",
        "category", "Java", "sourceMode", slot.sourceMode().name(), "topicSummary", "事务",
        "assessmentTargets", IntStream.rangeClosed(1, targetCount).mapToObj(i ->
            Map.of("id", "target-" + i, "description", "观察点" + i)).toList())).toList();
  }

  static String json(List<Map<String, Object>> rows) {
    return JsonMapper.builder().build().writeValueAsString(Map.of("questions", rows));
  }

  static Fixture fixture(String... responses) throws Exception {
    var model = new ScriptedModel(responses);
    var registry = mock(LlmProviderRegistry.class);
    when(registry.getChatClientOrDefault("test")).thenReturn(ChatClient.create(model));
    var properties = new StructuredOutputProperties();
    properties.setStructuredSchemaValidationEnabled(false);
    List<AiCallObservation> observations = new ArrayList<>();
    var invoker = new StructuredOutputInvoker(properties,
        new AiCallTelemetryRecorder(properties, null, List.of(observations::add)));
    return new Fixture(new DynamicMainQuestionGenerator(registry, invoker,
        new PromptSanitizer(new LlmProviderProperties()), new DefaultResourceLoader()), model,
        observations);
  }

  record Fixture(DynamicMainQuestionGenerator generator, ScriptedModel model,
      List<AiCallObservation> observations) {}

  static class ScriptedModel implements ChatModel {
    final AtomicInteger calls = new AtomicInteger();
    final List<Prompt> prompts = new ArrayList<>();
    final String[] responses;

    ScriptedModel(String... responses) { this.responses = responses; }

    @Override
    public ChatResponse call(Prompt prompt) {
      prompts.add(prompt);
      int index = Math.min(calls.getAndIncrement(), responses.length - 1);
      return new ChatResponse(List.of(new Generation(new AssistantMessage(responses[index]))));
    }
  }
}
