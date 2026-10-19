package interview.guide.modules.auth.dto;

import interview.guide.modules.auth.model.UserRole;
import interview.guide.modules.auth.model.UserStatus;
import java.time.Instant;
import java.util.UUID;

public record AdminUserResponse(UUID id, String username, String displayName, UserRole role,
    UserStatus status, boolean mustChangePassword, Instant createdAt, Instant updatedAt,
    Instant lastLoginAt) {
}
