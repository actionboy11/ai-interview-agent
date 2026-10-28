package interview.guide.modules.interview.planning;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.interview.planning.model.QuestionPlanningCommand;
import interview.guide.modules.interview.skill.InterviewSkillService.SkillCategoryDTO;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/** Shared deterministic coverage requirements for newly generated plans. */
@Component
public class QuestionCoveragePolicy {
  public List<SkillCategoryDTO> requiredCategories(QuestionPlanningCommand command) {
    requireValid(command);
    long mandatory = command.categories().stream()
        .filter(category -> "ALWAYS_ONE".equals(category.priority())).count();
    if (mandatory > command.totalQuestions()) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "必考分类数量超过主问题数量");
    }
    Comparator<SkillCategoryDTO> order = Comparator.comparingInt(category ->
        priority(category.priority()));
    if (command.totalQuestions() < command.categories().size()) {
      order = order.thenComparingInt(category ->
          command.recentCoverage().getOrDefault(category.key(), 0));
    }
    return command.categories().stream().sorted(order.thenComparing(SkillCategoryDTO::key))
        .limit(command.totalQuestions()).toList();
  }

  public int categoryCap(QuestionPlanningCommand command) {
    requireValid(command);
    return Math.max((int) Math.ceil(command.totalQuestions() * .3),
        (int) Math.ceil((double) command.totalQuestions() / command.categories().size()));
  }

  static int priority(String value) {
    return switch (value == null ? "" : value) {
      case "ALWAYS_ONE" -> 0;
      case "CORE" -> 1;
      case "NORMAL" -> 2;
      default -> 3;
    };
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
}
