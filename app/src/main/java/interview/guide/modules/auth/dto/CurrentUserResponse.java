package interview.guide.modules.auth.dto;

import interview.guide.modules.auth.model.UserRole;
import java.util.UUID;

public record CurrentUserResponse(UUID id, String username, String displayName, UserRole role,
    boolean mustChangePassword) {
}
