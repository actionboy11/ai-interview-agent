package interview.guide.modules.memory.messaging.rabbit;

import java.time.OffsetDateTime;
import java.util.UUID;

public record AbilityMemoryDerivationMessage(
    UUID messageId,
    String sessionId,
    int retryCount,
    OffsetDateTime createdAt,
    UUID originalMessageId
) {
}
