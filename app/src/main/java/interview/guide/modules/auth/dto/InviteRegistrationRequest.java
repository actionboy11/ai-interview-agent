package interview.guide.modules.auth.dto;

import jakarta.validation.constraints.NotBlank;

public record InviteRegistrationRequest(@NotBlank String inviteCode, @NotBlank String username,
    @NotBlank String password, @NotBlank String displayName) {
  @Override
  public String toString() {
    return "InviteRegistrationRequest[credentials=REDACTED]";
  }
}
