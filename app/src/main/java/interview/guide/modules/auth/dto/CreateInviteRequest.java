package interview.guide.modules.auth.dto;

import jakarta.validation.constraints.Positive;

public record CreateInviteRequest(@Positive long ttlSeconds) {
}
