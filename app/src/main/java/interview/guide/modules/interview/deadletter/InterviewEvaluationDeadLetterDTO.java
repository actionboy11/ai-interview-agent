package interview.guide.modules.interview.deadletter;

import java.time.OffsetDateTime;

public record InterviewEvaluationDeadLetterDTO(
    Long id,
    String originalMessageId,
    String sessionId,
    int retryCount,
    String failureReason,
    InterviewEvaluationDeadLetterStatus status,
    OffsetDateTime failedAt,
    OffsetDateTime replayedAt,
    String replayMessageId
) {
    static InterviewEvaluationDeadLetterDTO from(InterviewEvaluationDeadLetterEntity entity) {
        return new InterviewEvaluationDeadLetterDTO(
            entity.getId(),
            entity.getOriginalMessageId(),
            entity.getSessionId(),
            entity.getRetryCount(),
            entity.getFailureReason(),
            entity.getStatus(),
            entity.getFailedAt(),
            entity.getReplayedAt(),
            entity.getReplayMessageId()
        );
    }
}

