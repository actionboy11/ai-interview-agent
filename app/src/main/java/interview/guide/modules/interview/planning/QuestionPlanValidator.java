package interview.guide.modules.interview.planning;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.interview.planning.model.PlanningMode;
import interview.guide.modules.interview.planning.model.QuestionPlan;
import interview.guide.modules.interview.planning.model.QuestionPlanningCommand;
import interview.guide.modules.interview.planning.model.QuestionSourceMode;
import interview.guide.modules.interview.skill.InterviewSkillService.SkillCategoryDTO;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class QuestionPlanValidator {
  public void validate(QuestionPlanningCommand command, QuestionPlan plan) {
    if (plan == null || plan.slots() == null || plan.slots().size() != command.totalQuestions()) {
      throw invalid();
    }
    Set<String> categories = new HashSet<>();
    command.categories().stream().map(SkillCategoryDTO::key).forEach(categories::add);
    Set<String> slotIds = new HashSet<>();
    Map<String, Integer> categoryCounts = new HashMap<>();
    int adaptiveCap = Math.max((int) Math.ceil(command.totalQuestions() * .3),
        (int) Math.ceil((double) command.totalQuestions() / categories.size()));
    for (var slot : plan.slots()) {
      if (slot == null || slot.slotId() == null || !slotIds.add(slot.slotId())
          || !categories.contains(slot.categoryKey()) || slot.purpose() == null
          || slot.targetDifficulty() == null || slot.sourceMode() == null
          || slot.focusTopics() == null || slot.avoidedTopics() == null
          || (slot.sourceMode() == QuestionSourceMode.RESUME
              && (!command.hasResume()
                  || !command.groundedResumeCategoryKeys().contains(slot.categoryKey())))) {
        throw invalid();
      }
      int count = categoryCounts.merge(slot.categoryKey(), 1, Integer::sum);
      if (plan.mode() == PlanningMode.ADAPTIVE
          && count > adaptiveCap) {
        throw invalid();
      }
    }
  }

  private BusinessException invalid() {
    return new BusinessException(ErrorCode.INTERVIEW_QUESTION_GENERATION_FAILED,
        "面试出题计划不完整");
  }
}
