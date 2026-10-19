package interview.guide.modules.auth.dto;

import java.time.Instant;
import java.util.UUID;

public record IssuedInviteResponse(UUID id, String rawCode, Instant expiresAt) {
  @Override
  public String toString() {
    return "IssuedInviteResponse[id=" + id + ", expiresAt=" + expiresAt + ", rawCode=REDACTED]";
  }
}
