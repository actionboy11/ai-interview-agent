package interview.guide.modules.auth.service;

import java.time.Instant;
import java.util.UUID;

public record IssuedInvite(UUID id, String rawCode, Instant expiresAt) {
  @Override
  public String toString() {
    return "IssuedInvite[id=" + id + ", expiresAt=" + expiresAt + ", rawCode=REDACTED]";
  }
}
