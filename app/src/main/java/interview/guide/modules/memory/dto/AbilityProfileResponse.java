package interview.guide.modules.memory.dto;

import interview.guide.modules.memory.model.AbilityLevel;
import interview.guide.modules.memory.model.AbilityProfileStatus;
import java.time.Instant;

public record AbilityProfileResponse(Long id, String skillId, String categoryKey,
    AbilityLevel level, int score, double confidence, String summary,
    int confirmedEvidenceCount, AbilityProfileStatus status, boolean manuallyEdited,
    Instant lastEvaluatedAt, Instant lastConfirmedAt, Long version) {
}
