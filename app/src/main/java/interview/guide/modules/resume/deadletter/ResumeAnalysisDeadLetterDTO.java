package interview.guide.modules.resume.deadletter;

import java.time.OffsetDateTime;

public record ResumeAnalysisDeadLetterDTO(
    Long id,
    String originalMessageId,
    Long resumeId,
    int retryCount,
    String failureReason,
    ResumeAnalysisDeadLetterStatus status,
    OffsetDateTime failedAt,
    OffsetDateTime replayedAt,
    String replayMessageId
) {
    static ResumeAnalysisDeadLetterDTO from(ResumeAnalysisDeadLetterEntity entity) {
        return new ResumeAnalysisDeadLetterDTO(
            entity.getId(), entity.getOriginalMessageId(), entity.getResumeId(),
            entity.getRetryCount(), entity.getFailureReason(), entity.getStatus(),
            entity.getFailedAt(), entity.getReplayedAt(), entity.getReplayMessageId()
        );
    }
}
