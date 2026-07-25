package interview.guide.modules.knowledgebase.deadletter;

import java.time.OffsetDateTime;

public record KnowledgeVectorizationDeadLetterDTO(
    Long id,
    String originalMessageId,
    Long knowledgeBaseId,
    int retryCount,
    String failureReason,
    KnowledgeVectorizationDeadLetterStatus status,
    OffsetDateTime failedAt,
    OffsetDateTime replayedAt,
    String replayMessageId
) {
    static KnowledgeVectorizationDeadLetterDTO from(KnowledgeVectorizationDeadLetterEntity entity) {
        return new KnowledgeVectorizationDeadLetterDTO(
            entity.getId(), entity.getOriginalMessageId(), entity.getKnowledgeBaseId(),
            entity.getRetryCount(), entity.getFailureReason(), entity.getStatus(),
            entity.getFailedAt(), entity.getReplayedAt(), entity.getReplayMessageId()
        );
    }
}
