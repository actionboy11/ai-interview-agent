package interview.guide.modules.interview.planning.model;

import interview.guide.modules.interview.model.HistoricalQuestion;
import interview.guide.modules.interview.skill.InterviewSkillService.SkillCategoryDTO;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** All user-owned history must be loaded by the caller under the current owner. */
public record QuestionPlanningCommand(String skillId, List<SkillCategoryDTO> categories,
    int totalQuestions, String baseDifficulty, boolean hasResume,
    Map<String, Integer> recentCoverage, List<HistoricalQuestion> historicalQuestions,
    Set<String> groundedResumeCategoryKeys) {
  public QuestionPlanningCommand {
    categories = categories == null ? List.of() : List.copyOf(categories);
    recentCoverage = recentCoverage == null ? Map.of() : Map.copyOf(recentCoverage);
    historicalQuestions = historicalQuestions == null ? List.of()
        : List.copyOf(historicalQuestions);
    groundedResumeCategoryKeys = groundedResumeCategoryKeys == null ? Set.of()
        : Set.copyOf(groundedResumeCategoryKeys);
  }
}
