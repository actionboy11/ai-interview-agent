package interview.guide.modules.interview.dynamic.model;

import java.util.List;

public record FollowUpModelDecision(Action action, Reason reasonCode, String reasonSummary,
    List<String> coveredTargetIds, List<String> missingTargetIds, String followUpQuestion) {
  public enum Action { FOLLOW_UP, NEXT_MAIN }
  public enum Reason { IMPORTANT_GAP, SUFFICIENT, NO_USEFUL_FOLLOW_UP, USER_DONT_KNOW }
}
