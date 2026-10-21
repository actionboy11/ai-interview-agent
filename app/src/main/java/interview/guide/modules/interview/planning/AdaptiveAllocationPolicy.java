package interview.guide.modules.interview.planning;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.interview.model.HistoricalQuestion;
import interview.guide.modules.interview.planning.model.PlanningMode;
import interview.guide.modules.interview.planning.model.QuestionPlan;
import interview.guide.modules.interview.planning.model.QuestionPlanningCommand;
import interview.guide.modules.interview.planning.model.QuestionPurpose;
import interview.guide.modules.interview.planning.model.QuestionSlot;
import interview.guide.modules.interview.planning.model.QuestionSourceMode;
import interview.guide.modules.interview.skill.InterviewSkillService;
import interview.guide.modules.interview.skill.InterviewSkillService.SkillCategoryDTO;
import interview.guide.modules.memory.dto.AbilityProfileResponse;
import interview.guide.modules.memory.model.AbilityLevel;
import interview.guide.modules.memory.model.AbilityProfileStatus;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Pure, deterministic slot allocation; the fixed path uses the existing Skill allocator. */
@Component
@RequiredArgsConstructor
public class AdaptiveAllocationPolicy {
  private static final int PLAN_VERSION = 1;
  private static final int MANDATORY = 0;
  private static final int BASELINE = 1;
  private static final int WEAKNESS = 2;
  private static final int EXPLORATION = 3;
  private static final int STRENGTH = 4;
  private static final int[] WEIGHTS = {1, 4, 3, 1, 1};
  private static final Comparator<AbilityProfileResponse> WEAK_ORDER = Comparator
      .comparing(AbilityProfileResponse::level)
      .thenComparing(AbilityProfileResponse::confidence, Comparator.reverseOrder())
      .thenComparing(AbilityProfileResponse::lastEvaluatedAt,
          Comparator.nullsFirst(Comparator.naturalOrder()))
      .thenComparing(AbilityProfileResponse::categoryKey);

  private final InterviewSkillService skills;

  public QuestionPlan plan(QuestionPlanningCommand command, List<AbilityProfileResponse> profiles,
      Map<String, Long> confirmedSessionCounts) {
    requireValid(command);
    var categories = command.categories();
    Set<String> categoryKeys = new HashSet<>();
    categories.forEach(category -> categoryKeys.add(category.key()));
    var active = profiles == null ? List.<AbilityProfileResponse>of() : profiles.stream()
        .filter(profile -> profile != null && profile.status() == AbilityProfileStatus.ACTIVE)
        .filter(profile -> command.skillId().equals(profile.skillId()))
        .filter(profile -> categoryKeys.contains(profile.categoryKey()))
        .filter(profile -> profile.confirmedEvidenceCount() > 0
            && profile.lastConfirmedAt() != null)
        .filter(profile -> profile.level() != null)
        .sorted(Comparator.comparing(AbilityProfileResponse::categoryKey)
            .thenComparing(AbilityProfileResponse::id))
        .toList();
    if (active.isEmpty()) {
      return fallback(command, null);
    }

    Map<String, AbilityProfileResponse> byCategory = new LinkedHashMap<>();
    active.forEach(profile -> byCategory.putIfAbsent(profile.categoryKey(), profile));
    var weak = active.stream().filter(profile -> isWeak(profile.level()))
        .sorted(WEAK_ORDER).toList();
    var strong = active.stream().filter(profile -> isStrong(profile.level()))
        .sorted(Comparator.comparing(AbilityProfileResponse::level).reversed()
            .thenComparing(AbilityProfileResponse::confidence, Comparator.reverseOrder())
            .thenComparing(AbilityProfileResponse::categoryKey)).toList();
    var unknown = categories.stream().filter(category -> !byCategory.containsKey(category.key()))
        .toList();
    int mandatoryCount = (int) categories.stream()
        .filter(category -> "ALWAYS_ONE".equals(category.priority())).count();
    int[] budget = budget(command.totalQuestions(), mandatoryCount);
    if (weak.isEmpty()) {
      budget[BASELINE] += (budget[WEAKNESS] + 1) / 2;
      budget[EXPLORATION] += budget[WEAKNESS] / 2;
      budget[WEAKNESS] = 0;
    }
    if (unknown.isEmpty()) {
      budget[BASELINE] += budget[EXPLORATION];
      budget[EXPLORATION] = 0;
    }
    if (strong.isEmpty()) {
      budget[BASELINE] += budget[STRENGTH];
      budget[STRENGTH] = 0;
    }

    Map<String, Integer> assigned = new HashMap<>();
    List<Draft> drafts = new ArrayList<>();
    int cap = Math.max((int) Math.ceil(command.totalQuestions() * .3),
        (int) Math.ceil((double) command.totalQuestions() / categories.size()));
    int missing = addMandatory(categories, budget[MANDATORY], assigned, drafts);
    // Reserve later-purpose capacity before baseline selects its categories. The final
    // slot order still follows the shortage priority: mandatory, baseline, weak,
    // exploration, strength.
    List<Draft> reservedWeak = new ArrayList<>();
    List<Draft> reservedExploration = new ArrayList<>();
    List<Draft> reservedStrong = new ArrayList<>();
    missing += addProfiles(weak, budget[WEAKNESS], cap,
        QuestionPurpose.WEAKNESS_REINFORCEMENT, assigned, reservedWeak);
    missing += addExploration(command, unknown, budget[EXPLORATION], cap,
        assigned, reservedExploration);
    missing += addProfiles(strong, budget[STRENGTH], cap,
        QuestionPurpose.STRENGTH_CALIBRATION, assigned, reservedStrong);
    int baselineShortage = addBaseline(command, categories, budget[BASELINE] + missing,
        cap, assigned, drafts);
    if (baselineShortage > 0) {
      throw new BusinessException(ErrorCode.INTERVIEW_QUESTION_GENERATION_FAILED,
          "分类上限下无法满足面试题量");
    }
    drafts.addAll(reservedWeak);
    drafts.addAll(reservedExploration);
    drafts.addAll(reservedStrong);

    boolean qualified = byCategory.values().stream().filter(profile ->
        confirmedSessionCounts != null
            && confirmedSessionCounts.getOrDefault(profile.categoryKey(), 0L) >= 2).count() >= 2;
    int resumeTarget = command.hasResume()
        ? (int) Math.round(command.totalQuestions() * (qualified ? .4 : .6)) : 0;
    var avoided = avoidedTopics(command.historicalQuestions());
    List<QuestionSlot> slots = new ArrayList<>();
    int resumeAssigned = 0;
    for (Draft draft : drafts) {
      AbilityProfileResponse profile = byCategory.get(draft.categoryKey());
      boolean grounded = command.groundedResumeCategoryKeys().contains(draft.categoryKey());
      QuestionSourceMode source = resumeAssigned < resumeTarget && grounded
          ? QuestionSourceMode.RESUME : QuestionSourceMode.DIRECTION;
      if (source == QuestionSourceMode.RESUME) {
        resumeAssigned++;
      }
      String difficulty = adjustedDifficulty(command.baseDifficulty(), draft.purpose());
      List<String> focus = profile != null && profile.summary() != null
          && !profile.summary().isBlank() ? List.of(profile.summary()) : List.of();
      slots.add(new QuestionSlot(slotId(slots.size()), draft.categoryKey(), draft.purpose(),
          difficulty, source, focus, avoided.getOrDefault(draft.categoryKey(), List.of()),
          draft.purpose() == QuestionPurpose.WEAKNESS_REINFORCEMENT
              || draft.purpose() == QuestionPurpose.STRENGTH_CALIBRATION
              ? profile.id() : null));
    }
    return new QuestionPlan(PLAN_VERSION, PlanningMode.ADAPTIVE, slots, null);
  }

  public QuestionPlan fallback(QuestionPlanningCommand command, String reason) {
    requireValid(command);
    int resumeTarget = command.hasResume()
        ? (int) Math.round(command.totalQuestions() * .6) : 0;
    int cap = Math.max((int) Math.ceil(command.totalQuestions() * .3),
        (int) Math.ceil((double) command.totalQuestions() / command.categories().size()));
    List<SkillCategoryDTO> grounded = command.categories().stream()
        .filter(category -> command.groundedResumeCategoryKeys().contains(category.key()))
        .toList();
    int resumeCount = resumeTarget;
    Map<String, Integer> allocation;
    while (true) {
      int candidateDirectionCount = command.totalQuestions() - resumeCount;
      Map<String, Integer> candidateAllocation = reconciledFixedAllocation(
          command.categories(), candidateDirectionCount);
      int resumeCapacity = grounded.stream().mapToInt(category ->
          Math.max(0, cap - candidateAllocation.getOrDefault(category.key(), 0))).sum();
      allocation = candidateAllocation;
      if (resumeCount <= resumeCapacity) {
        break;
      }
      resumeCount = resumeCapacity;
    }
    var avoided = avoidedTopics(command.historicalQuestions());
    List<QuestionSlot> slots = new ArrayList<>();
    List<SkillCategoryDTO> ordered = command.categories().stream()
        .sorted(Comparator.comparingInt(category ->
            "ALWAYS_ONE".equals(category.priority()) ? 0 : 1)).toList();
    for (SkillCategoryDTO category : ordered) {
      for (int i = 0; i < allocation.getOrDefault(category.key(), 0); i++) {
        QuestionPurpose purpose = "ALWAYS_ONE".equals(category.priority()) && i == 0
            ? QuestionPurpose.MANDATORY : QuestionPurpose.BASELINE;
        slots.add(new QuestionSlot(slotId(slots.size()), category.key(), purpose,
            command.baseDifficulty(), QuestionSourceMode.DIRECTION, List.of(),
            avoided.getOrDefault(category.key(), List.of()), null));
      }
    }
    Map<String, Integer> assigned = new HashMap<>(allocation);
    for (int i = 0; i < resumeCount; i++) {
      SkillCategoryDTO category = grounded.stream()
          .filter(candidate -> assigned.getOrDefault(candidate.key(), 0) < cap)
          .min(Comparator.comparingInt((SkillCategoryDTO candidate) ->
              assigned.getOrDefault(candidate.key(), 0)).thenComparing(SkillCategoryDTO::key))
          .orElseThrow(() -> new BusinessException(ErrorCode.INTERVIEW_QUESTION_GENERATION_FAILED,
              "简历题槽缺少已验证的分类事实"));
      assigned.merge(category.key(), 1, Integer::sum);
      slots.add(new QuestionSlot(slotId(slots.size()), category.key(), QuestionPurpose.BASELINE,
          command.baseDifficulty(), QuestionSourceMode.RESUME, List.of(),
          avoided.getOrDefault(category.key(), List.of()), null));
    }
    return new QuestionPlan(PLAN_VERSION, PlanningMode.FIXED, slots, reason);
  }

  private Map<String, Integer> reconciledFixedAllocation(List<SkillCategoryDTO> categories,
      int directionCount) {
    Map<String, Integer> allocation = new LinkedHashMap<>(
        skills.calculateAllocation(categories, directionCount));
    int shortfall = directionCount - allocation.values().stream().mapToInt(Integer::intValue).sum();
    if (shortfall > 0) {
      allocation.merge(categories.getFirst().key(), shortfall, Integer::sum);
    }
    return allocation;
  }

  private static int[] budget(int total, int mandatoryCategories) {
    int[] result = new int[WEIGHTS.length];
    int remaining = total;
    for (int i = 0; i < WEIGHTS.length; i++) {
      result[i] = total * WEIGHTS[i] / 10;
      remaining -= result[i];
    }
    List<Integer> order = List.of(MANDATORY, BASELINE, WEAKNESS, EXPLORATION, STRENGTH)
        .stream().sorted(Comparator.<Integer>comparingInt(i ->
            total * WEIGHTS[i] % 10).reversed().thenComparingInt(i -> i)).toList();
    for (int i = 0; i < remaining; i++) {
      result[order.get(i)]++;
    }
    int target = Math.min(mandatoryCategories, total);
    if (target > result[MANDATORY]) {
      int extra = target - result[MANDATORY];
      result[MANDATORY] = target;
      for (int index : List.of(STRENGTH, EXPLORATION, WEAKNESS, BASELINE)) {
        int removed = Math.min(extra, result[index]);
        result[index] -= removed;
        extra -= removed;
      }
    }
    return result;
  }

  private static int addMandatory(List<SkillCategoryDTO> categories, int budget,
      Map<String, Integer> assigned, List<Draft> drafts) {
    int remaining = budget;
    for (SkillCategoryDTO category : categories) {
      if (remaining == 0) {
        break;
      }
      if ("ALWAYS_ONE".equals(category.priority())) {
        add(category.key(), QuestionPurpose.MANDATORY, assigned, drafts);
        remaining--;
      }
    }
    return remaining;
  }

  private static int addBaseline(QuestionPlanningCommand command,
      List<SkillCategoryDTO> categories, int budget, int cap,
      Map<String, Integer> assigned, List<Draft> drafts) {
    int remaining = budget;
    while (remaining > 0) {
      var next = categories.stream().filter(category -> assigned.getOrDefault(category.key(), 0) < cap)
          .min(Comparator.comparingInt((SkillCategoryDTO category) ->
                  command.recentCoverage().getOrDefault(category.key(), 0))
              .thenComparingInt(category -> assigned.getOrDefault(category.key(), 0))
              .thenComparingInt(category -> priority(category.priority()))
              .thenComparing(SkillCategoryDTO::key));
      if (next.isEmpty()) {
        break;
      }
      add(next.get().key(), QuestionPurpose.BASELINE, assigned, drafts);
      remaining--;
    }
    return remaining;
  }

  private static int addProfiles(List<AbilityProfileResponse> profiles, int budget, int cap,
      QuestionPurpose purpose, Map<String, Integer> assigned, List<Draft> drafts) {
    Map<String, Integer> purposeCount = new HashMap<>();
    int remaining = budget;
    while (remaining > 0) {
      AbilityProfileResponse next = profiles.stream()
          .filter(profile -> assigned.getOrDefault(profile.categoryKey(), 0) < cap)
          .min(Comparator.comparingInt((AbilityProfileResponse profile) ->
              purposeCount.getOrDefault(profile.categoryKey(), 0))
              .thenComparing(purpose != QuestionPurpose.WEAKNESS_REINFORCEMENT
                  ? Comparator.comparing(AbilityProfileResponse::categoryKey) : WEAK_ORDER))
          .orElse(null);
      if (next == null) {
        break;
      }
      add(next.categoryKey(), purpose, assigned, drafts);
      purposeCount.merge(next.categoryKey(), 1, Integer::sum);
      remaining--;
    }
    return remaining;
  }

  private static int addExploration(QuestionPlanningCommand command,
      List<SkillCategoryDTO> categories, int budget, int cap,
      Map<String, Integer> assigned, List<Draft> drafts) {
    int remaining = budget;
    while (remaining > 0) {
      var next = categories.stream().filter(category -> assigned.getOrDefault(category.key(), 0) < cap)
          .min(Comparator.comparingInt((SkillCategoryDTO category) ->
                  command.recentCoverage().getOrDefault(category.key(), 0))
              .thenComparingInt(category -> assigned.getOrDefault(category.key(), 0))
              .thenComparing(SkillCategoryDTO::key));
      if (next.isEmpty()) {
        break;
      }
      add(next.get().key(), QuestionPurpose.EXPLORATION, assigned, drafts);
      remaining--;
    }
    return remaining;
  }

  private static void add(String key, QuestionPurpose purpose,
      Map<String, Integer> assigned, List<Draft> drafts) {
    assigned.merge(key, 1, Integer::sum);
    drafts.add(new Draft(key, purpose));
  }

  private static Map<String, List<String>> avoidedTopics(List<HistoricalQuestion> history) {
    Map<String, LinkedHashSet<String>> values = new LinkedHashMap<>();
    for (HistoricalQuestion question : history) {
      if (question == null || question.type() == null || question.topicSummary() == null
          || question.topicSummary().isBlank()) {
        continue;
      }
      values.computeIfAbsent(question.type(), ignored -> new LinkedHashSet<>())
          .add(question.topicSummary());
    }
    Map<String, List<String>> result = new HashMap<>();
    values.forEach((key, topics) -> result.put(key, List.copyOf(topics)));
    return result;
  }

  private static String adjustedDifficulty(String base, QuestionPurpose purpose) {
    int index = List.of("junior", "mid", "senior").indexOf(base);
    if (purpose == QuestionPurpose.WEAKNESS_REINFORCEMENT) {
      index = Math.max(0, index - 1);
    } else if (purpose == QuestionPurpose.STRENGTH_CALIBRATION) {
      index = Math.min(2, index + 1);
    }
    return List.of("junior", "mid", "senior").get(index);
  }

  private static int priority(String value) {
    return switch (value == null ? "" : value) {
      case "CORE" -> 0;
      case "NORMAL" -> 1;
      case "ALWAYS_ONE" -> 2;
      default -> 3;
    };
  }

  private static boolean isWeak(AbilityLevel level) {
    return level == AbilityLevel.WEAK || level == AbilityLevel.DEVELOPING;
  }

  private static boolean isStrong(AbilityLevel level) {
    return level == AbilityLevel.STRONG || level == AbilityLevel.PROFICIENT;
  }

  private static String slotId(int index) {
    return "slot-%02d".formatted(index + 1);
  }

  private static void requireValid(QuestionPlanningCommand command) {
    if (command == null || command.skillId() == null || command.skillId().isBlank()
        || command.totalQuestions() <= 0 || command.categories().isEmpty()
        || !List.of("junior", "mid", "senior").contains(command.baseDifficulty())) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "无效的面试出题规划参数");
    }
    Set<String> keys = new HashSet<>();
    for (SkillCategoryDTO category : command.categories()) {
      if (category == null || category.key() == null || category.key().isBlank()
          || !keys.add(category.key())) {
        throw new BusinessException(ErrorCode.BAD_REQUEST, "Skill 分类键无效或重复");
      }
    }
  }

  private record Draft(String categoryKey, QuestionPurpose purpose) {
  }
}
