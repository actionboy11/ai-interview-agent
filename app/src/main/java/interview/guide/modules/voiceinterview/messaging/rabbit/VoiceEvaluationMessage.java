package interview.guide.modules.voiceinterview.messaging.rabbit;

import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;

public record VoiceEvaluationMessage(
    UUID messageId,
    Long sessionId,
    int retryCount,
    OffsetDateTime createdAt,
    UUID originalMessageId
) {
    public VoiceEvaluationMessage {
        Objects.requireNonNull(messageId, "messageId must not be null");
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        if (sessionId <= 0) {
            throw new IllegalArgumentException("sessionId must be positive");
        }
        if (retryCount < 0) {
            throw new IllegalArgumentException("retryCount must not be negative");
        }
        if (originalMessageId == null) {
            originalMessageId = messageId;
        }
    }
}
