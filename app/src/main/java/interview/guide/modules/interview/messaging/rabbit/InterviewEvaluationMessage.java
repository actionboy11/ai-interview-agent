package interview.guide.modules.interview.messaging.rabbit;

import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;

public record InterviewEvaluationMessage(
    UUID messageId,
    String sessionId,
    int retryCount,
    OffsetDateTime createdAt,
    UUID originalMessageId
) {
  public InterviewEvaluationMessage {
    Objects.requireNonNull(messageId, "messageId must not be null");
    Objects.requireNonNull(createdAt, "createdAt must not be null");
    if (sessionId == null || sessionId.isBlank()) {
      throw new IllegalArgumentException("sessionId must not be blank");
    }
    if (retryCount < 0) {
      throw new IllegalArgumentException("retryCount must not be negative");
    }
    if (originalMessageId == null) {
      originalMessageId = messageId;
    }
  }
}
