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

/** Pure, deterministic coverage-first slot allocation for new plans. */
@Component
@RequiredArgsConstructor
public class AdaptiveAllocationPolicy {
  private static final int PLAN_VERSION = 2;
  private static final int BASELINE = 0;
  private static final int WEAKNESS = 1;
  private static final int EXPLORATION = 2;
  private static final int STRENGTH = 3;
  private static final Comparator<AbilityProfileResponse> WEAK_ORDER = Comparator
      .comparing(AbilityProfileResponse::level)
      .thenComparing(AbilityProfileResponse::confidence, Comparator.reverseOrder())
      .thenComparing(AbilityProfileResponse::lastEvaluatedAt,
          Comparator.nullsFirst(Comparator.naturalOrder()))
      .thenComparing(AbilityProfileResponse::categoryKey);
  private static final Comparator<AbilityProfileResponse> STRONG_ORDER = Comparator
      .comparing(AbilityProfileResponse::level).reversed()
      .thenComparing(AbilityProfileResponse::confidence, Comparator.reverseOrder())
      .thenComparing(AbilityProfileResponse::categoryKey);

  private final InterviewSkillService skills;
  private final QuestionCoveragePolicy coverage = new QuestionCoveragePolicy();

  public QuestionPlan plan(QuestionPlanningCommand command, List<AbilityProfileResponse> profiles,
      Map<String, Long> confirmedSessionCounts) {
    var required = coverage.requiredCategories(command);
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
    var weak = byCategory.values().stream().filter(profile -> isWeak(profile.level()))
        .sorted(WEAK_ORDER).toList();
    var strong = byCategory.values().stream().filter(profile -> isStrong(profile.level()))
        .sorted(STRONG_ORDER).toList();
    var unknown = categories.stream().filter(category -> !byCategory.containsKey(category.key()))
        .toList();
    Map<String, Integer> assigned = new HashMap<>();
    List<Draft> drafts = new ArrayList<>();
    addCoverage(required, assigned, drafts);
    int cap = coverage.categoryCap(command);
    int[] budget = budget(command.totalQuestions() - drafts.size(),
        !weak.isEmpty(), !unknown.isEmpty(), !strong.isEmpty());
    // Coverage is already secured. Reserve only residual personalization capacity
    // so baseline additions cannot consume the remaining weak/strong candidates.
    List<Draft> reservedWeak = new ArrayList<>();
    List<Draft> reservedExploration = new ArrayList<>();
    List<Draft> reservedStrong = new ArrayList<>();
    int missing = addProfiles(weak, budget[WEAKNESS], cap,
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
    return buildPlan(command, drafts, byCategory, qualified, PlanningMode.ADAPTIVE, null);
  }

  private QuestionPlan buildPlan(QuestionPlanningCommand command, List<Draft> drafts,
      Map<String, AbilityProfileResponse> byCategory, boolean qualified,
      PlanningMode mode, String reason) {
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
    return new QuestionPlan(PLAN_VERSION, mode, slots, reason);
  }

  public QuestionPlan fallback(QuestionPlanningCommand command, String reason) {
    var required = coverage.requiredCategories(command);
    Map<String, Integer> assigned = new HashMap<>();
    List<Draft> drafts = new ArrayList<>();
    addCoverage(required, assigned, drafts);
    int shortage = addBaseline(command, command.categories(),
        command.totalQuestions() - drafts.size(), coverage.categoryCap(command), assigned, drafts);
    if (shortage > 0) {
      throw new BusinessException(ErrorCode.INTERVIEW_QUESTION_GENERATION_FAILED,
          "分类上限下无法满足面试题量");
    }
    return buildPlan(command, drafts, Map.of(), false, PlanningMode.FIXED, reason);
  }

  private static int[] budget(int total, boolean hasWeak, boolean hasUnknown,
      boolean hasStrong) {
    int[] weights = {4, 3, 1, 1};
    for (int index : List.of(WEAKNESS, EXPLORATION, STRENGTH)) {
      boolean available = switch (index) {
        case WEAKNESS -> hasWeak;
        case EXPLORATION -> hasUnknown;
        default -> hasStrong;
      };
      if (!available) {
        weights[BASELINE] += weights[index];
        weights[index] = 0;
      }
    }
    int[] result = new int[weights.length];
    int remaining = total;
    for (int i = 0; i < weights.length; i++) {
      result[i] = total * weights[i] / 9;
      remaining -= result[i];
    }
    List<Integer> order = List.of(BASELINE, WEAKNESS, EXPLORATION, STRENGTH)
        .stream().sorted(Comparator.<Integer>comparingInt(i ->
            total * weights[i] % 9).reversed().thenComparingInt(i -> i)).toList();
    for (int i = 0; i < remaining; i++) {
      result[order.get(i)]++;
    }
    return result;
  }

  private static void addCoverage(List<SkillCategoryDTO> categories,
      Map<String, Integer> assigned, List<Draft> drafts) {
    for (SkillCategoryDTO category : categories) {
      add(category.key(), "ALWAYS_ONE".equals(category.priority())
          ? QuestionPurpose.MANDATORY : QuestionPurpose.BASELINE, assigned, drafts);
    }
  }

  private static int addBaseline(QuestionPlanningCommand command,
      List<SkillCategoryDTO> categories, int budget, int cap,
      Map<String, Integer> assigned, List<Draft> drafts) {
    int remaining = budget;
    while (remaining > 0) {
      var next = categories.stream().filter(category -> assigned.getOrDefault(category.key(), 0) < cap)
          .min(Comparator.comparingInt((SkillCategoryDTO category) ->
                  assigned.getOrDefault(category.key(), 0))
              .thenComparingInt(category -> command.recentCoverage().getOrDefault(category.key(), 0))
              .thenComparingInt(category -> QuestionCoveragePolicy.priority(category.priority()))
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
              .thenComparing(purpose == QuestionPurpose.WEAKNESS_REINFORCEMENT
                  ? WEAK_ORDER : STRONG_ORDER))
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

  private static boolean isWeak(AbilityLevel level) {
    return level == AbilityLevel.WEAK || level == AbilityLevel.DEVELOPING;
  }

  private static boolean isStrong(AbilityLevel level) {
    return level == AbilityLevel.STRONG || level == AbilityLevel.PROFICIENT;
  }

  private static String slotId(int index) {
    return "slot-%02d".formatted(index + 1);
  }

  private record Draft(String categoryKey, QuestionPurpose purpose) {
  }
}
