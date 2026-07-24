package interview.guide.modules.resume.messaging.rabbit;

import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;

public record ResumeAnalysisMessage(
    UUID messageId,
    Long resumeId,
    int retryCount,
    OffsetDateTime createdAt,
    UUID originalMessageId
) {

    public ResumeAnalysisMessage {
        Objects.requireNonNull(messageId, "messageId must not be null");
        Objects.requireNonNull(resumeId, "resumeId must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        if (resumeId <= 0) {
            throw new IllegalArgumentException("resumeId must be positive");
        }
        if (retryCount < 0) {
            throw new IllegalArgumentException("retryCount must not be negative");
        }
        if (originalMessageId == null) {
            originalMessageId = messageId;
        }
    }
}
