package interview.guide.modules.interview.planning;

import static org.assertj.core.api.Assertions.assertThat;

import interview.guide.modules.interview.model.HistoricalQuestion;
import interview.guide.modules.interview.planning.model.PlanningMode;
import interview.guide.modules.interview.planning.model.QuestionPlanningCommand;
import interview.guide.modules.interview.planning.model.QuestionPurpose;
import interview.guide.modules.interview.planning.model.QuestionSourceMode;
import interview.guide.modules.interview.skill.InterviewSkillService;
import interview.guide.modules.interview.skill.InterviewSkillService.SkillCategoryDTO;
import interview.guide.modules.memory.dto.AbilityProfileResponse;
import interview.guide.modules.memory.model.AbilityLevel;
import interview.guide.modules.memory.model.AbilityProfileStatus;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.core.io.DefaultResourceLoader;

class AdaptiveAllocationPolicyTest {
  private static final String SKILL = "java-backend";
  private AdaptiveAllocationPolicy policy;
  private InterviewSkillService skills;

  @BeforeEach
  void setUp() throws Exception {
    skills = new InterviewSkillService(null, null, new DefaultResourceLoader(), null);
    policy = new AdaptiveAllocationPolicy(skills);
  }

  @ParameterizedTest(name = "题量 {0} 保留优先级并精确分配")
  @CsvSource({"5,1,2,2,0,0", "10,1,4,3,1,1"})
  @DisplayName("5 与 10 题预算按短缺优先级分配且总数精确")
  void budgets(int total, long mandatory, long baseline, long weakness,
      long exploration, long strength) {
    var plan = policy.plan(command(total, false, categories()), profiles(), Map.of());

    assertThat(plan.mode()).isEqualTo(PlanningMode.ADAPTIVE);
    assertThat(plan.slots()).hasSize(total);
    assertThat(plan.slots().getFirst().purpose()).isEqualTo(QuestionPurpose.MANDATORY);
    var byPurpose = plan.slots().stream().collect(Collectors.groupingBy(
        slot -> slot.purpose(), Collectors.counting()));
    assertThat(byPurpose.getOrDefault(QuestionPurpose.MANDATORY, 0L)).isEqualTo(mandatory);
    assertThat(byPurpose.getOrDefault(QuestionPurpose.BASELINE, 0L)).isEqualTo(baseline);
    assertThat(byPurpose.getOrDefault(QuestionPurpose.WEAKNESS_REINFORCEMENT, 0L))
        .isEqualTo(weakness);
    assertThat(byPurpose.getOrDefault(QuestionPurpose.EXPLORATION, 0L)).isEqualTo(exploration);
    assertThat(plan.slots().stream().filter(
        slot -> slot.purpose() == QuestionPurpose.STRENGTH_CALIBRATION).count())
        .isEqualTo(strength);
    assertThat(plan.slots().stream().filter(slot ->
        slot.purpose() == QuestionPurpose.EXPLORATION)
        .map(slot -> slot.categoryKey()).toList()).doesNotContain("JAVA", "MYSQL", "REDIS");
    assertThat(plan.slots().stream().collect(Collectors.groupingBy(
        slot -> slot.categoryKey(), Collectors.counting())).values())
        .allSatisfy(count -> assertThat(count).isLessThanOrEqualTo((long) Math.ceil(total * .3)));
  }

  @Test
  @DisplayName("弱项按等级、置信度、评估时间及分类键稳定排序")
  void ranksWeakProfilesDeterministically() {
    var cats = List.of(cat("PROJECT", "ALWAYS_ONE"), cat("JAVA", "CORE"),
        cat("MYSQL", "CORE"), cat("REDIS", "CORE"), cat("SPRING", "CORE"),
        cat("MQ", "NORMAL"), cat("OS", "NORMAL"), cat("NETWORK", "NORMAL"));
    var profiles = List.of(
        profile(1L, "JAVA", AbilityLevel.DEVELOPING, .9, "2026-01-01T00:00:00Z"),
        profile(2L, "MYSQL", AbilityLevel.WEAK, .7, "2026-01-01T00:00:00Z"),
        profile(3L, "REDIS", AbilityLevel.WEAK, .9, "2026-02-01T00:00:00Z"),
        profile(4L, "SPRING", AbilityLevel.WEAK, .9, "2026-01-01T00:00:00Z"),
        profile(5L, "MQ", AbilityLevel.WEAK, .9, "2026-01-01T00:00:00Z"));

    var plan = policy.plan(command(10, false, cats), profiles, Map.of());
    assertThat(plan.slots().stream()
        .filter(slot -> slot.purpose() == QuestionPurpose.WEAKNESS_REINFORCEMENT)
        .map(slot -> slot.categoryKey()).toList())
        .containsExactly("MQ", "SPRING", "REDIS");
  }

  @Test
  @DisplayName("只采纳当前 Skill 的 ACTIVE 画像，历史知识点用于避重")
  void filtersProfilesAndAvoidsHistory() {
    var command = new QuestionPlanningCommand(SKILL, categories(), 10, "mid", false,
        Map.of("JAVA", 4), List.of(new HistoricalQuestion("Q", "JAVA", "JVM GC")), Set.of());
    var profiles = List.of(
        profile(1L, "JAVA", AbilityLevel.WEAK, .9, "2026-01-01T00:00:00Z"),
        new AbilityProfileResponse(2L, "other", "MYSQL", AbilityLevel.WEAK, 30, .9,
            "other", 2, AbilityProfileStatus.ACTIVE, false, Instant.now(), Instant.now(), 0L),
        new AbilityProfileResponse(3L, SKILL, "REDIS", AbilityLevel.WEAK, 30, .9,
            "disabled", 2, AbilityProfileStatus.DISABLED, false, Instant.now(), Instant.now(), 0L),
        new AbilityProfileResponse(4L, SKILL, "SPRING", AbilityLevel.WEAK, 30, .9,
            "review", 2, AbilityProfileStatus.NEEDS_REVIEW, false, Instant.now(), Instant.now(), 0L),
        new AbilityProfileResponse(5L, SKILL, "MQ", AbilityLevel.WEAK, 30, .9,
            "unconfirmed", 0, AbilityProfileStatus.ACTIVE, false, Instant.now(), null, 0L));

    var plan = policy.plan(command, profiles, Map.of());
    assertThat(plan.slots().stream().filter(slot -> slot.relatedProfileId() != null)
        .map(slot -> slot.relatedProfileId()).distinct().toList()).containsExactly(1L);
    assertThat(plan.slots().stream().filter(slot -> slot.categoryKey().equals("JAVA"))
        .allMatch(slot -> slot.avoidedTopics().contains("JVM GC"))).isTrue();
    assertThat(plan.slots().stream().filter(slot -> slot.categoryKey().equals("JAVA")
        && slot.purpose() == QuestionPurpose.WEAKNESS_REINFORCEMENT)
        .allMatch(slot -> slot.targetDifficulty().equals("junior"))).isTrue();
  }

  @Test
  @DisplayName("少分类时放宽三成上限并保留总题量")
  void relaxesCapWhenCategoriesAreFew() {
    var cats = List.of(cat("PROJECT", "ALWAYS_ONE"), cat("JAVA", "CORE"));
    var plan = policy.plan(command(10, false, cats),
        List.of(profile(1L, "JAVA", AbilityLevel.WEAK, .9, "2026-01-01T00:00:00Z")),
        Map.of());
    assertThat(plan.slots()).hasSize(10);
    assertThat(plan.slots().stream().filter(slot -> slot.categoryKey().equals("JAVA")).count())
        .isGreaterThan(3);
  }

  @Test
  @DisplayName("无画像沿用现有 Skill 固定分配，简历存在时按六四分源")
  void noMemoryUsesExistingAllocation() {
    var command = command(10, true, categories());
    var plan = policy.plan(command, List.of(), Map.of());
    assertThat(plan.mode()).isEqualTo(PlanningMode.FIXED);
    assertThat(plan.resumeSlots()).hasSize(6);
    assertThat(plan.directionSlots()).hasSize(4);
    assertThat(plan.directionSlots().stream().collect(Collectors.groupingBy(
        slot -> slot.categoryKey(), Collectors.summingInt(slot -> 1))))
        .containsExactlyInAnyOrderEntriesOf(skills.calculateAllocation(categories(), 4)
            .entrySet().stream().filter(entry -> entry.getValue() > 0)
            .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue)));
  }

  @Test
  @DisplayName("当前 Skill 两个画像各有两场已确认面试时按四六分源")
  void qualifiedProfilesShiftSourceRatio() {
    var plan = policy.plan(command(10, true, categories()), profiles(),
        Map.of("JAVA", 2L, "MYSQL", 2L));
    assertThat(plan.resumeSlots()).hasSize(4);
    assertThat(plan.directionSlots()).hasSize(6);
    assertThat(plan.slots().stream().filter(slot ->
        slot.purpose() == QuestionPurpose.WEAKNESS_REINFORCEMENT)
        .allMatch(slot -> slot.sourceMode() == QuestionSourceMode.DIRECTION)).isTrue();
  }

  @Test
  @DisplayName("只有一个达到两场确认的画像时仍维持六四来源比例")
  void lowEvidenceKeepsSourceRatio() {
    var plan = policy.plan(command(10, true, categories()), profiles(),
        Map.of("JAVA", 2L, "MYSQL", 1L));
    assertThat(plan.resumeSlots()).hasSize(6);
    assertThat(plan.directionSlots()).hasSize(4);
  }

  @Test
  @DisplayName("简历证实相关方向时允许简历弱项验证")
  void groundedResumeMayVerifyWeakness() {
    var command = new QuestionPlanningCommand(SKILL, categories(), 10, "mid", true,
        Map.of(), List.of(), Set.of("JAVA"));
    var plan = policy.plan(command, profiles(), Map.of());
    assertThat(plan.slots().stream().filter(slot ->
        slot.purpose() == QuestionPurpose.WEAKNESS_REINFORCEMENT
            && slot.sourceMode() == QuestionSourceMode.RESUME)
        .map(slot -> slot.categoryKey()).toList()).contains("JAVA");
  }

  @Test
  @DisplayName("只有强项时校准题不超过一成，弱项预算回流基础与探索")
  void strongCalibrationIsCapped() {
    var strong = List.of(profile(3L, "REDIS", AbilityLevel.STRONG, .9,
        "2026-01-03T00:00:00Z"));
    var plan = policy.plan(command(20, false, categories()), strong, Map.of());
    assertThat(plan.slots()).hasSize(20);
    assertThat(plan.slots().stream().filter(slot ->
        slot.purpose() == QuestionPurpose.STRENGTH_CALIBRATION).count()).isLessThanOrEqualTo(2);
    assertThat(plan.slots().stream().filter(slot ->
        slot.purpose() == QuestionPurpose.EXPLORATION).count()).isGreaterThan(0);
  }

  @Test
  @DisplayName("固定分配即使 Skill 将强制类目排在后面，也先安排强制题")
  void fixedAllocationPlacesMandatoryFirst() {
    var cats = List.of(cat("JAVA", "CORE"), cat("PROJECT", "ALWAYS_ONE"),
        cat("MYSQL", "CORE"));
    var plan = policy.plan(command(5, false, cats), List.of(), Map.of());
    assertThat(plan.slots().getFirst().categoryKey()).isEqualTo("PROJECT");
    assertThat(plan.slots().getFirst().purpose()).isEqualTo(QuestionPurpose.MANDATORY);
  }

  @Test
  @DisplayName("仅有强制分类时固定回退仍须生成精确题槽数")
  void fixedAllocationReconcilesSingleMandatoryCategory() {
    var plan = policy.plan(command(5, false, List.of(cat("PROJECT", "ALWAYS_ONE"))),
        List.of(), Map.of());
    assertThat(plan.slots()).hasSize(5);
  }

  @Test
  @DisplayName("没有简历时全部是方向题且不会虚构简历弱项")
  void noResumeMeansDirectionOnly() {
    var plan = policy.plan(command(10, false, categories()), profiles(), Map.of());
    assertThat(plan.resumeSlots()).isEmpty();
    assertThat(plan.directionSlots()).hasSize(10);
  }

  private static QuestionPlanningCommand command(int total, boolean resume,
      List<SkillCategoryDTO> categories) {
    return new QuestionPlanningCommand(SKILL, categories, total, "mid", resume,
        Map.of(), List.of(), Set.of());
  }

  private static List<SkillCategoryDTO> categories() {
    return List.of(cat("PROJECT", "ALWAYS_ONE"), cat("JAVA", "CORE"),
        cat("MYSQL", "CORE"), cat("REDIS", "CORE"), cat("SPRING", "CORE"),
        cat("MQ", "NORMAL"), cat("OS", "NORMAL"), cat("NETWORK", "NORMAL"));
  }

  private static SkillCategoryDTO cat(String key, String priority) {
    return new SkillCategoryDTO(key, key, priority, null, false);
  }

  private static List<AbilityProfileResponse> profiles() {
    return List.of(profile(1L, "JAVA", AbilityLevel.WEAK, .9, "2026-01-01T00:00:00Z"),
        profile(2L, "MYSQL", AbilityLevel.DEVELOPING, .8, "2026-01-02T00:00:00Z"),
        profile(3L, "REDIS", AbilityLevel.STRONG, .9, "2026-01-03T00:00:00Z"));
  }

  private static AbilityProfileResponse profile(Long id, String key, AbilityLevel level,
      double confidence, String evaluated) {
    return new AbilityProfileResponse(id, SKILL, key, level, 30, confidence, key + " topic",
        2, AbilityProfileStatus.ACTIVE, false, Instant.parse(evaluated),
        Instant.parse(evaluated), 0L);
  }
}
