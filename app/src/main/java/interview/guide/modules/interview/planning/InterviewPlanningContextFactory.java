package interview.guide.modules.interview.planning;

import interview.guide.modules.interview.model.HistoricalQuestion;
import interview.guide.modules.interview.planning.model.QuestionPlanningCommand;
import interview.guide.modules.interview.skill.InterviewSkillService.SkillDTO;
import java.util.List;
import java.util.stream.Collectors;

/** Shared pure input assembly; allocation and owner-scoped profile lookup stay in the planner. */
public final class InterviewPlanningContextFactory {
  private InterviewPlanningContextFactory() {}

  public static QuestionPlanningCommand create(String skillId, SkillDTO skill, int questionCount,
      String difficulty, String resumeText, List<HistoricalQuestion> historicalQuestions) {
    var grounded = new ResumeFactGrounding().grounded(resumeText, skill.categories());
    var recentCoverage = historicalQuestions.stream().filter(question -> question.type() != null)
        .collect(Collectors.toMap(HistoricalQuestion::type, question -> 1, Integer::sum));
    return new QuestionPlanningCommand(skillId, skill.categories(), questionCount, difficulty,
        resumeText != null && !resumeText.isBlank(), recentCoverage, historicalQuestions, grounded);
  }
}
