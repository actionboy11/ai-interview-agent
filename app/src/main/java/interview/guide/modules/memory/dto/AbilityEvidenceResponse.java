package interview.guide.modules.memory.dto;

import interview.guide.modules.memory.model.AbilityProposalStatus;
import java.time.Instant;
import java.util.List;

public record AbilityEvidenceResponse(Long id, String interviewSessionId, Long proposalId,
    AbilityProposalStatus proposalStatus, String skillId, String categoryKey,
    String topicSummary, int questionScore, String difficulty, String feedbackSummary,
    Instant occurredAt, Long interviewAnswerId, String slotId, List<Long> supportingAnswerIds) {
  public AbilityEvidenceResponse(Long id, String interviewSessionId, Long proposalId,
      AbilityProposalStatus proposalStatus, String skillId, String categoryKey,
      String topicSummary, int questionScore, String difficulty, String feedbackSummary,
      Instant occurredAt) {
    this(id, interviewSessionId, proposalId, proposalStatus, skillId, categoryKey,
        topicSummary, questionScore, difficulty, feedbackSummary, occurredAt, null, null, List.of());
  }

  public AbilityEvidenceResponse { supportingAnswerIds = List.copyOf(supportingAnswerIds); }
}
