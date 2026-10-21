package interview.guide.modules.memory.dto;

import interview.guide.modules.memory.model.AbilityProposalStatus;
import java.time.Instant;

public record AbilityEvidenceResponse(Long id, String interviewSessionId, Long proposalId,
    AbilityProposalStatus proposalStatus, String skillId, String categoryKey,
    String topicSummary, int questionScore, String difficulty, String feedbackSummary,
    Instant occurredAt) {
}
