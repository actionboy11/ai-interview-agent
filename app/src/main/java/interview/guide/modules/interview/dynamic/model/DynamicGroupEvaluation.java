package interview.guide.modules.interview.dynamic.model;

import java.util.List;

/** One formal observation for a published main-question group. */
public record DynamicGroupEvaluation(String slotId, Integer score, String evidenceSummary,
    List<Integer> supportingQuestionIndexes, List<String> observedTargetIds) {}
