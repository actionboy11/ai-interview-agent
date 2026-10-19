package interview.guide.modules.auth.dto;

import interview.guide.modules.auth.model.UserRole;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record AdminCreateUserRequest(@NotBlank String username, @NotBlank String password,
    @NotBlank String displayName, @NotNull UserRole role) {
  @Override
  public String toString() {
    return "AdminCreateUserRequest[credentials=REDACTED]";
  }
}
