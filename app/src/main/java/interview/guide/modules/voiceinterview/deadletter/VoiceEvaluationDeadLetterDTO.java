package interview.guide.modules.voiceinterview.deadletter;

import java.time.OffsetDateTime;

public record VoiceEvaluationDeadLetterDTO(
    Long id,
    String originalMessageId,
    Long sessionId,
    int retryCount,
    String failureReason,
    VoiceEvaluationDeadLetterStatus status,
    OffsetDateTime failedAt,
    OffsetDateTime replayedAt,
    String replayMessageId
) {
    static VoiceEvaluationDeadLetterDTO from(VoiceEvaluationDeadLetterEntity entity) {
        return new VoiceEvaluationDeadLetterDTO(
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
