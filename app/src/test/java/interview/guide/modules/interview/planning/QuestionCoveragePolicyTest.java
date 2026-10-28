package interview.guide.modules.interview.planning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import interview.guide.common.exception.BusinessException;
import interview.guide.modules.interview.planning.model.PlanningMode;
import interview.guide.modules.interview.planning.model.QuestionPlanningCommand;
import interview.guide.modules.interview.planning.model.QuestionPurpose;
import interview.guide.modules.interview.skill.InterviewSkillService;
import interview.guide.modules.interview.skill.InterviewSkillService.SkillCategoryDTO;
import interview.guide.modules.memory.dto.AbilityProfileResponse;
import interview.guide.modules.memory.model.AbilityLevel;
import interview.guide.modules.memory.model.AbilityProfileStatus;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.DefaultResourceLoader;

class QuestionCoveragePolicyTest {
  private AdaptiveAllocationPolicy allocator;

  @BeforeEach
  void setUp() throws Exception {
    allocator = new AdaptiveAllocationPolicy(
        new InterviewSkillService(null, null, new DefaultResourceLoader(), null));
  }

  @ParameterizedTest
  @ValueSource(ints = {6, 8})
  @DisplayName("真实历史偏斜与项目弱项不能挤掉六类覆盖")
  void historicalRegressionCoversAllCategories(int total) {
    var command = command(total, categories(), Map.of("PROJECT", 3, "JAVA", 2,
        "MYSQL", 2, "REDIS", 2, "SPRING", 2, "SYSTEM_DESIGN_SCENARIO", 1));
    var plan = allocator.plan(command, profiles(), Map.of());

    assertThat(plan.version()).isEqualTo(2);
    assertThat(plan.slots()).hasSize(total);
    assertThat(plan.slots().subList(0, 6).stream().map(slot -> slot.categoryKey()))
        .containsExactly("PROJECT", "JAVA", "MYSQL", "REDIS", "SPRING",
            "SYSTEM_DESIGN_SCENARIO");
    assertThat(plan.slots().getFirst().purpose()).isEqualTo(QuestionPurpose.MANDATORY);
    assertThat(plan.slots().subList(1, 6)).allSatisfy(slot ->
        assertThat(slot.purpose()).isEqualTo(QuestionPurpose.BASELINE));
    assertThat(plan.slots().stream().collect(Collectors.groupingBy(
        slot -> slot.categoryKey(), Collectors.counting())).values())
        .allSatisfy(count -> assertThat(count).isLessThanOrEqualTo(total == 6 ? 1L : 2L));
    assertThat(plan.slots().getFirst().focusTopics()).containsExactly("PROJECT facts");
  }

  @Test
  @DisplayName("固定和无画像路径覆盖全部分类且不让简历目标挤掉覆盖")
  void fixedAndNoMemoryShareCoverage() {
    var input = new QuestionPlanningCommand("java-backend", categories(), 6, "mid", true,
        Map.of(), List.of(), Set.of("PROJECT"));
    var fixed = allocator.fallback(input, "MEMORY_FAILURE");
    var noMemory = allocator.plan(input, List.of(), Map.of());

    for (var plan : List.of(fixed, noMemory)) {
      assertThat(plan.version()).isEqualTo(2);
      assertThat(plan.mode()).isEqualTo(PlanningMode.FIXED);
      assertThat(plan.slots().stream().map(slot -> slot.categoryKey()).distinct()).hasSize(6);
      assertThat(plan.resumeSlots()).hasSize(1);
      assertThat(plan.resumeSlots().getFirst().categoryKey()).isEqualTo("PROJECT");
    }
    assertThat(fixed.fallbackReason()).isEqualTo("MEMORY_FAILURE");
    assertThat(noMemory.fallbackReason()).isNull();
  }

  @Test
  @DisplayName("分类与画像输入顺序不影响完整计划")
  void inputOrderDoesNotAffectPlan() {
    var reversed = new ArrayList<>(categories());
    Collections.reverse(reversed);
    var reversedProfiles = new ArrayList<>(profiles());
    Collections.reverse(reversedProfiles);
    var coverage = Map.of("JAVA", 5, "MYSQL", 1);

    assertThat(allocator.plan(command(12, reversed, coverage), reversedProfiles, Map.of()))
        .isEqualTo(allocator.plan(command(12, categories(), coverage), profiles(), Map.of()));
    assertThat(allocator.fallback(command(12, reversed, coverage), "FIXED"))
        .isEqualTo(allocator.fallback(command(12, categories(), coverage), "FIXED"));
  }

  @Test
  @DisplayName("题量不足时按必考核心普通及历史覆盖稳定选择")
  void fewerSlotsSelectMandatoryThenPriorityThenHistory() {
    var cats = List.of(cat("NORMAL", "NORMAL"), cat("CORE_HIGH", "CORE"),
        cat("MANDATORY", "ALWAYS_ONE"), cat("CORE_LOW_Z", "CORE"),
        cat("CORE_LOW_A", "CORE"));
    var input = command(3, cats, Map.of("MANDATORY", 100, "CORE_HIGH", 10));

    assertThat(allocator.fallback(input, null).slots().stream().map(slot -> slot.categoryKey()))
        .containsExactly("MANDATORY", "CORE_LOW_A", "CORE_LOW_Z");
    assertThat(allocator.plan(input,
        List.of(profile(1, "NORMAL", AbilityLevel.WEAK)), Map.of())
        .slots().stream().map(slot -> slot.categoryKey()))
        .containsExactly("MANDATORY", "CORE_LOW_A", "CORE_LOW_Z");
  }

  @Test
  @DisplayName("多个必考共享覆盖阶段且必考数量超预算时明确拒绝")
  void mandatoryCategoriesCannotExceedBudget() {
    var cats = List.of(cat("Z", "ALWAYS_ONE"), cat("A", "ALWAYS_ONE"),
        cat("CORE", "CORE"));
    var accepted = allocator.fallback(command(2, cats, Map.of()), null);
    assertThat(accepted.slots().stream().map(slot -> slot.categoryKey()))
        .containsExactly("A", "Z");
    assertThat(accepted.slots()).allSatisfy(slot ->
        assertThat(slot.purpose()).isEqualTo(QuestionPurpose.MANDATORY));
    assertThatThrownBy(() -> allocator.fallback(command(1, cats, Map.of()), null))
        .isInstanceOf(BusinessException.class);
    assertThatThrownBy(() -> allocator.plan(command(1, cats, Map.of()),
        List.of(profile(1, "A", AbilityLevel.WEAK)), Map.of()))
        .isInstanceOf(BusinessException.class);
  }

  @Test
  @DisplayName("重复分类配置被拒绝而不是产生双重必考")
  void rejectsDuplicateCategoryKeys() {
    var input = command(3, List.of(cat("A", "CORE"), cat("A", "ALWAYS_ONE")), Map.of());
    assertThatThrownBy(() -> allocator.fallback(input, null))
        .isInstanceOf(BusinessException.class);
  }

  @ParameterizedTest
  @ValueSource(ints = {3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20})
  @DisplayName("三至二十题在候选缺失及单分类下仍保持题量唯一性覆盖和整数容量")
  void budgetsRemainConservedAcrossBoundaries(int total) {
    var allKnown = categories().stream().map(category ->
        profile(category.key().hashCode(), category.key(), AbilityLevel.COMPETENT)).toList();
    for (var candidates : List.of(List.<AbilityProfileResponse>of(), profiles(), allKnown,
        List.of(profile(1, "PROJECT", AbilityLevel.WEAK)),
        List.of(profile(1, "MYSQL", AbilityLevel.STRONG)))) {
      var plan = allocator.plan(command(total, categories(), Map.of()), candidates, Map.of());
      assertThat(plan.slots()).hasSize(total);
      assertThat(plan.slots().stream().map(slot -> slot.slotId())).doesNotHaveDuplicates();
      assertThat(plan.slots().stream().map(slot -> slot.categoryKey()).distinct())
          .hasSize(Math.min(total, 6));
      int cap = Math.max((total * 3 + 9) / 10, (total + 5) / 6);
      assertThat(plan.slots().stream().collect(Collectors.groupingBy(
          slot -> slot.categoryKey(), Collectors.counting())).values())
          .allSatisfy(count -> assertThat(count).isLessThanOrEqualTo((long) cap));
    }
    var single = allocator.plan(command(total, List.of(cat("CUSTOM", "ALWAYS_ONE")), Map.of()),
        List.of(profile(1, "CUSTOM", AbilityLevel.WEAK)), Map.of());
    assertThat(single.slots()).hasSize(total);
    assertThat(single.slots().stream().map(slot -> slot.categoryKey()))
        .containsOnly("CUSTOM");
  }

  @Test
  @DisplayName("固定基础追加先平衡本场数量再看历史覆盖")
  void baselineDoesNotGiveEveryExtraSlotToHistoricallyRareCategory() {
    var plan = allocator.fallback(command(9, categories(), Map.of("PROJECT", 100,
        "JAVA", 100, "MYSQL", 100, "REDIS", 100, "SPRING", 100)), null);
    assertThat(plan.slots().subList(6, 9).stream().map(slot -> slot.categoryKey()))
        .containsExactly("SYSTEM_DESIGN_SCENARIO", "PROJECT", "JAVA");
  }

  @Test
  @DisplayName("没有探索候选时先把探索权重并入基础再取整")
  void unavailableExplorationWeightMergesBeforeRounding() {
    var known = categories().stream().map(category -> profile(category.key().hashCode(),
        category.key(), switch (category.key()) {
          case "PROJECT" -> AbilityLevel.WEAK;
          case "MYSQL" -> AbilityLevel.STRONG;
          default -> AbilityLevel.COMPETENT;
        })).toList();
    var plan = allocator.plan(command(10, categories(), Map.of()), known, Map.of());
    assertThat(plan.slots().stream().collect(Collectors.groupingBy(
        slot -> slot.purpose(), Collectors.counting()))).containsExactlyInAnyOrderEntriesOf(
            Map.of(QuestionPurpose.MANDATORY, 1L, QuestionPurpose.BASELINE, 7L,
                QuestionPurpose.WEAKNESS_REINFORCEMENT, 1L,
                QuestionPurpose.STRENGTH_CALIBRATION, 1L));
  }

  @Test
  @DisplayName("弱项分类容量耗尽后剩余名额回流基础而不丢题")
  void saturatedWeaknessBudgetReturnsToBaseline() {
    var cats = categories().subList(0, 4);
    var plan = allocator.plan(command(30, cats, Map.of()),
        List.of(profile(1, "PROJECT", AbilityLevel.WEAK)), Map.of());
    assertThat(plan.slots()).hasSize(30);
    assertThat(plan.slots().stream().collect(Collectors.groupingBy(
        slot -> slot.purpose(), Collectors.counting()))).containsExactlyInAnyOrderEntriesOf(
            Map.of(QuestionPurpose.MANDATORY, 1L, QuestionPurpose.BASELINE, 18L,
                QuestionPurpose.WEAKNESS_REINFORCEMENT, 8L, QuestionPurpose.EXPLORATION, 3L));
    assertThat(plan.slots().stream().collect(Collectors.groupingBy(
        slot -> slot.categoryKey(), Collectors.counting())).values())
        .allSatisfy(count -> assertThat(count).isLessThanOrEqualTo(9L));
  }

  @ParameterizedTest
  @CsvSource({"6,6,2", "8,6,3", "10,2,5", "20,1,20"})
  @DisplayName("共享整数容量取三成向上取整与均摊容量中的较大值")
  void categoryCapUsesNecessaryIntegerBound(int total, int count, int expected) {
    var input = command(total, categories().subList(0, count), Map.of());
    assertThat(new QuestionCoveragePolicy().categoryCap(input)).isEqualTo(expected);
  }

  @Test
  @DisplayName("共享覆盖策略返回稳定必考核心普通分类集合")
  void sharedPolicyReturnsRequiredCategories() {
    var policy = new QuestionCoveragePolicy();
    var input = command(3, categories(), Map.of("JAVA", 9, "MYSQL", 2));
    assertThat(policy.requiredCategories(input).stream().map(SkillCategoryDTO::key))
        .containsExactly("PROJECT", "REDIS", "SPRING");
    assertThat(policy.requiredCategories(command(6, categories(), input.recentCoverage()))
        .stream().map(SkillCategoryDTO::key)).containsExactly("PROJECT", "JAVA", "MYSQL",
            "REDIS", "SPRING", "SYSTEM_DESIGN_SCENARIO");
  }

  private static QuestionPlanningCommand command(int total, List<SkillCategoryDTO> categories,
      Map<String, Integer> history) {
    return new QuestionPlanningCommand("java-backend", categories, total, "mid", false,
        history, List.of(), Set.of());
  }

  private static List<SkillCategoryDTO> categories() {
    return List.of(cat("PROJECT", "ALWAYS_ONE"), cat("JAVA", "CORE"),
        cat("MYSQL", "CORE"), cat("REDIS", "CORE"), cat("SPRING", "CORE"),
        cat("SYSTEM_DESIGN_SCENARIO", "NORMAL"));
  }

  private static SkillCategoryDTO cat(String key, String priority) {
    return new SkillCategoryDTO(key, key, priority, null, false);
  }

  private static List<AbilityProfileResponse> profiles() {
    return List.of(profile(1, "PROJECT", AbilityLevel.WEAK),
        profile(2, "MYSQL", AbilityLevel.STRONG), profile(3, "REDIS", AbilityLevel.PROFICIENT));
  }

  private static AbilityProfileResponse profile(long id, String key, AbilityLevel level) {
    var evaluated = Instant.parse("2026-01-01T00:00:00Z");
    return new AbilityProfileResponse(id, "java-backend", key, level, 50, .9,
        key + " facts", 2, AbilityProfileStatus.ACTIVE, false, evaluated, evaluated, 0L);
  }
}
