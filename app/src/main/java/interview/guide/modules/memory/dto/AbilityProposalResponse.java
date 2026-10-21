package interview.guide.modules.memory.dto;

import interview.guide.modules.memory.model.AbilityLevel;
import interview.guide.modules.memory.model.AbilityProposalStatus;
import java.time.Instant;

public record AbilityProposalResponse(Long id, String interviewSessionId, String skillId,
    String categoryKey, AbilityLevel previousLevel, Integer previousScore,
    AbilityLevel proposedLevel, int proposedScore, String proposedSummary,
    AbilityLevel finalLevel, Integer finalScore, String finalSummary,
    AbilityProposalStatus status, Instant createdAt, Instant decidedAt, Long version) {
}
