package interview.guide.modules.auth.security;

import interview.guide.modules.auth.model.UserRole;
import java.io.Serializable;
import java.security.Principal;
import java.util.UUID;

public record CurrentUser(UUID id, String username, String displayName, UserRole role,
    boolean mustChangePassword) implements Principal, Serializable {
  @Override
  public String getName() {
    return id.toString();
  }
}
