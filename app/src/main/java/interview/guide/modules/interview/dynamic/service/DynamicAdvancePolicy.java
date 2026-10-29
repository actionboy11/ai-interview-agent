package interview.guide.modules.interview.dynamic.service;

import interview.guide.modules.interview.dynamic.model.DynamicGroupEndReason;
import interview.guide.modules.interview.dynamic.model.SubmissionIntent;
import org.springframework.stereotype.Service;

@Service
public class DynamicAdvancePolicy {
  public DynamicGroupEndReason closeReason(SubmissionIntent intent, int groupUsed,
      int totalUsed, int groupLimit, int totalLimit) {
    if (intent == SubmissionIntent.DONT_KNOW) { return DynamicGroupEndReason.DONT_KNOW; }
    if (intent == SubmissionIntent.SKIP) { return DynamicGroupEndReason.SKIP; }
    return groupUsed >= groupLimit || totalUsed >= totalLimit
        ? DynamicGroupEndReason.BUDGET_EXHAUSTED : null;
  }
}
