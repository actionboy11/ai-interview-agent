package interview.guide.modules.auth.dto;

import interview.guide.modules.auth.model.RegistrationInviteStatus;
import java.time.Instant;
import java.util.UUID;

public record InviteMetadataResponse(UUID id, RegistrationInviteStatus status, Instant expiresAt,
    UUID createdById, Instant createdAt, Instant usedAt, UUID usedById) {
}
