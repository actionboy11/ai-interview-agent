package interview.guide.modules.knowledgebase.messaging.rabbit;

import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;

public record KnowledgeVectorizationMessage(
    UUID messageId,
    Long knowledgeBaseId,
    int retryCount,
    OffsetDateTime createdAt,
    UUID originalMessageId
) {
  public KnowledgeVectorizationMessage {
    Objects.requireNonNull(messageId, "messageId must not be null");
    Objects.requireNonNull(createdAt, "createdAt must not be null");
    if (knowledgeBaseId == null || knowledgeBaseId <= 0) {
      throw new IllegalArgumentException("knowledgeBaseId must be positive");
    }
    if (retryCount < 0) {
      throw new IllegalArgumentException("retryCount must not be negative");
    }
    if (originalMessageId == null) {
      originalMessageId = messageId;
    }
  }
}
