package interview.guide.modules.resume.deadletter;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.OffsetDateTime;

@Entity
@Table(
    name = "resume_analysis_dead_letters",
    uniqueConstraints = @UniqueConstraint(
        name = "uk_resume_dead_original_message",
        columnNames = "original_message_id"
    )
)
public class ResumeAnalysisDeadLetterEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "original_message_id", nullable = false, length = 36)
    private String originalMessageId;
    @Column(nullable = false)
    private Long resumeId;
    @Column(nullable = false)
    private Integer retryCount;
    @Column(columnDefinition = "TEXT")
    private String failureReason;
    @Column(columnDefinition = "TEXT")
    private String payloadJson;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20)
    private ResumeAnalysisDeadLetterStatus status = ResumeAnalysisDeadLetterStatus.PENDING;
    @Column(nullable = false)
    private OffsetDateTime failedAt;
    private OffsetDateTime replayedAt;
    @Column(length = 36)
    private String replayMessageId;

    public Long getId() { return id; }
    public String getOriginalMessageId() { return originalMessageId; }
    public void setOriginalMessageId(String value) { this.originalMessageId = value; }
    public Long getResumeId() { return resumeId; }
    public void setResumeId(Long value) { this.resumeId = value; }
    public Integer getRetryCount() { return retryCount; }
    public void setRetryCount(Integer value) { this.retryCount = value; }
    public String getFailureReason() { return failureReason; }
    public void setFailureReason(String value) { this.failureReason = value; }
    public String getPayloadJson() { return payloadJson; }
    public void setPayloadJson(String value) { this.payloadJson = value; }
    public ResumeAnalysisDeadLetterStatus getStatus() { return status; }
    public void setStatus(ResumeAnalysisDeadLetterStatus value) { this.status = value; }
    public OffsetDateTime getFailedAt() { return failedAt; }
    public void setFailedAt(OffsetDateTime value) { this.failedAt = value; }
    public OffsetDateTime getReplayedAt() { return replayedAt; }
    public void setReplayedAt(OffsetDateTime value) { this.replayedAt = value; }
    public String getReplayMessageId() { return replayMessageId; }
    public void setReplayMessageId(String value) { this.replayMessageId = value; }
}
