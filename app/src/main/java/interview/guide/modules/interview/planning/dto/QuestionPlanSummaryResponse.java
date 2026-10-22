package interview.guide.modules.interview.planning.dto;

import interview.guide.modules.interview.planning.model.PlanningMode;
import interview.guide.modules.interview.planning.model.QuestionPurpose;
import java.util.List;
import java.util.Map;

/** Safe, historical explanation of the allocation actually selected for an interview. */
public record QuestionPlanSummaryResponse(boolean available, PlanningMode mode,
    Map<QuestionPurpose, Integer> purposeCounts, List<QuestionReasonResponse> questions) {
  public QuestionPlanSummaryResponse {
    purposeCounts = Map.copyOf(purposeCounts);
    questions = List.copyOf(questions);
  }

  public static QuestionPlanSummaryResponse unavailable() {
    return new QuestionPlanSummaryResponse(false, null, Map.of(), List.of());
  }

  public record QuestionReasonResponse(int questionIndex, String slotId, String categoryKey,
      QuestionPurpose purpose, String reason) {
  }
}
