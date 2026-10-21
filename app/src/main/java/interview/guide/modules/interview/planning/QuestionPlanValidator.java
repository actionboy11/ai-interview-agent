package interview.guide.modules.interview.planning;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.interview.planning.model.QuestionPlan;
import interview.guide.modules.interview.planning.model.QuestionPlanningCommand;
import interview.guide.modules.interview.planning.model.QuestionSourceMode;
import interview.guide.modules.interview.skill.InterviewSkillService.SkillCategoryDTO;
import java.util.HashSet;
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
    for (var slot : plan.slots()) {
      if (slot == null || slot.slotId() == null || !slotIds.add(slot.slotId())
          || !categories.contains(slot.categoryKey()) || slot.purpose() == null
          || slot.targetDifficulty() == null || slot.sourceMode() == null
          || slot.focusTopics() == null || slot.avoidedTopics() == null
          || (!command.hasResume() && slot.sourceMode() == QuestionSourceMode.RESUME)) {
        throw invalid();
      }
    }
  }

  private BusinessException invalid() {
    return new BusinessException(ErrorCode.INTERVIEW_QUESTION_GENERATION_FAILED,
        "面试出题计划不完整");
  }
}
