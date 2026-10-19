package interview.guide.modules.auth.service;

import interview.guide.modules.auth.model.UserRole;

public record CreateAdminUserCommand(String username, String password, String displayName,
    UserRole role) {
  @Override
  public String toString() {
    return "CreateAdminUserCommand[credentials=REDACTED]";
  }
}
