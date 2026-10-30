package interview.guide.modules.interview.dynamic.model;

import java.time.Instant;
import java.util.List;

/** One formal main-question group, anchored to its published main answer. */
public record DynamicGroupEvidenceSource(String slotId, Long mainAnswerId,
    List<Long> supportingAnswerIds, String categoryKey, int score, String topicSummary,
    String evidenceSummary, String difficulty, Instant occurredAt) {
  public DynamicGroupEvidenceSource { supportingAnswerIds = List.copyOf(supportingAnswerIds); }
}
