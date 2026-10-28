package interview.guide.modules.interview.planning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import interview.guide.common.exception.BusinessException;
import interview.guide.modules.interview.planning.model.PlanningMode;
import interview.guide.modules.interview.planning.model.QuestionPlan;
import interview.guide.modules.interview.planning.model.QuestionPlanningCommand;
import interview.guide.modules.interview.planning.model.QuestionPurpose;
import interview.guide.modules.interview.planning.model.QuestionSlot;
import interview.guide.modules.interview.planning.model.QuestionSourceMode;
import interview.guide.modules.interview.skill.InterviewSkillService.SkillCategoryDTO;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

class QuestionPlanValidatorTest {
  private final QuestionPlanValidator validator = new QuestionPlanValidator();

  @ParameterizedTest
  @ValueSource(strings = {"PROJECT", "JAVA", "MYSQL"})
  @DisplayName("新版计划缺任一应覆盖分类均拒绝且不补写原计划")
  void rejectsMissingCoverageWithoutRewriting(String omitted) {
    var input = coverageCommand(3);
    var slots = input.categories().stream().map(SkillCategoryDTO::key)
        .filter(key -> !key.equals(omitted)).map(key -> slot(key, key)).toList();
    var incomplete = new ArrayList<>(slots);
    incomplete.add(slot("extra", slots.getFirst().categoryKey()));
    var plan = new QuestionPlan(2, PlanningMode.FIXED, incomplete, "SAVED_REASON");

    assertThatThrownBy(() -> validator.validate(input, plan))
        .isInstanceOf(BusinessException.class);
    assertThat(plan.slots()).containsExactlyElementsOf(incomplete);
    assertThat(plan.fallbackReason()).isEqualTo("SAVED_REASON");
  }

  @Test
  @DisplayName("题量少于分类数时新版计划仍须覆盖所选必考及核心分类")
  void rejectsMissingSelectedCategoryWhenBudgetIsSmall() {
    var plan = new QuestionPlan(2, PlanningMode.FIXED,
        List.of(slot("1", "JAVA"), slot("2", "MYSQL")), null);
    assertThatThrownBy(() -> validator.validate(coverageCommand(2), plan))
        .isInstanceOf(BusinessException.class);
  }

  @ParameterizedTest
  @EnumSource(PlanningMode.class)
  @DisplayName("新版计划在自适应和固定模式都执行整数分类容量限制")
  void rejectsCapViolationInBothModes(PlanningMode mode) {
    var slots = List.of(slot("1", "PROJECT"), slot("2", "JAVA"), slot("3", "MYSQL"),
        slot("4", "JAVA"), slot("5", "JAVA"), slot("6", "JAVA"));
    assertThatThrownBy(() -> validator.validate(coverageCommand(6),
        new QuestionPlan(2, mode, slots, null))).isInstanceOf(BusinessException.class);
  }

  @Test
  @DisplayName("旧固定计划继续使用原验证规则且不被补题或改写")
  void legacyFixedPlanKeepsOldCoverageAndCapacityRules() {
    var slots = List.of(slot("1", "JAVA"), slot("2", "JAVA"), slot("3", "JAVA"));
    var plan = new QuestionPlan(1, PlanningMode.FIXED, slots, "LEGACY");
    assertThatCode(() -> validator.validate(coverageCommand(3), plan)).doesNotThrowAnyException();
    assertThat(plan.slots()).containsExactlyElementsOf(slots);
    assertThat(plan.version()).isEqualTo(1);
  }

  @Test
  @DisplayName("旧自适应计划不追加新版覆盖要求")
  void legacyAdaptivePlanDoesNotGainCoverageRules() {
    var slots = List.of(slot("1", "JAVA"), slot("2", "JAVA"), slot("3", "MYSQL"),
        slot("4", "MYSQL"));
    assertThatCode(() -> validator.validate(coverageCommand(4),
        new QuestionPlan(1, PlanningMode.ADAPTIVE, slots, null))).doesNotThrowAnyException();
  }

  @Test
  @DisplayName("新版计划拒绝重复题槽编号和非法分类")
  void rejectsDuplicateIdsAndIllegalCategory() {
    assertThatThrownBy(() -> validator.validate(coverageCommand(3),
        new QuestionPlan(2, PlanningMode.FIXED,
            List.of(slot("1", "PROJECT"), slot("1", "JAVA"), slot("3", "MYSQL")), null)))
        .isInstanceOf(BusinessException.class);
    assertThatThrownBy(() -> validator.validate(coverageCommand(3),
        new QuestionPlan(2, PlanningMode.FIXED,
            List.of(slot("1", "PROJECT"), slot("2", "JAVA"), slot("3", "UNKNOWN")), null)))
        .isInstanceOf(BusinessException.class);
  }

  @Test
  @DisplayName("新版合法计划只验证而不更改题槽顺序用途和来源")
  void validPlanIsNotRewritten() {
    var slots = List.of(slot("3", "MYSQL"), slot("2", "JAVA"), slot("1", "PROJECT"));
    var plan = new QuestionPlan(2, PlanningMode.FIXED, slots, null);
    validator.validate(coverageCommand(3), plan);
    assertThat(plan.slots()).containsExactlyElementsOf(slots);
  }

  @Test
  @DisplayName("八个分类足够时拒绝单分类超过总题量三成的计划")
  void rejectsAvoidableCategoryCapViolation() {
    var categories = List.of("PROJECT", "JAVA", "MYSQL", "REDIS", "SPRING", "MQ", "OS", "NETWORK")
        .stream().map(key -> new SkillCategoryDTO(key, key, "CORE", null, false)).toList();
    var command = new QuestionPlanningCommand("java-backend", categories, 10, "mid", false,
        Map.of(), List.of(), Set.of());
    var slots = new ArrayList<QuestionSlot>();
    for (int i = 0; i < 10; i++) {
      String category = i < 4 ? "JAVA" : categories.get(i - 4).key();
      slots.add(new QuestionSlot("slot-%02d".formatted(i + 1), category,
          QuestionPurpose.BASELINE, "mid", QuestionSourceMode.DIRECTION,
          List.of(), List.of(), null));
    }

    assertThatThrownBy(() -> validator.validate(command,
        new QuestionPlan(1, PlanningMode.ADAPTIVE, slots, null)))
        .isInstanceOf(BusinessException.class);
  }

  @ParameterizedTest(name = "未核实简历分类拒绝 {0} 简历题槽")
  @EnumSource(QuestionPurpose.class)
  @DisplayName("任一用途都不能把无简历事实的分类标为简历题")
  void rejectsUngroundedResumeSlot(QuestionPurpose purpose) {
    var command = new QuestionPlanningCommand("java-backend",
        List.of(new SkillCategoryDTO("JAVA", "Java", "CORE", null, false)),
        1, "mid", true, Map.of(), List.of(), Set.of());
    var slot = new QuestionSlot("slot-01", "JAVA", purpose, "mid",
        QuestionSourceMode.RESUME, List.of(), List.of(), null);

    for (int version : List.of(1, 2)) {
      assertThatThrownBy(() -> validator.validate(command,
          new QuestionPlan(version, PlanningMode.ADAPTIVE, List.of(slot), null)))
          .isInstanceOf(BusinessException.class);
    }
  }

  private static QuestionPlanningCommand coverageCommand(int total) {
    return new QuestionPlanningCommand("java-backend", List.of(
        new SkillCategoryDTO("PROJECT", "Project", "ALWAYS_ONE", null, false),
        new SkillCategoryDTO("JAVA", "Java", "CORE", null, false),
        new SkillCategoryDTO("MYSQL", "MySQL", "NORMAL", null, false)),
        total, "mid", false, Map.of(), List.of(), Set.of());
  }

  private static QuestionSlot slot(String id, String key) {
    return new QuestionSlot(id, key, QuestionPurpose.BASELINE, "mid",
        QuestionSourceMode.DIRECTION, List.of(), List.of(), null);
  }
}
