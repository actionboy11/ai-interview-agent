package interview.guide.modules.agentevaluation.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "agent_evaluation_stages", uniqueConstraints = {
    @UniqueConstraint(
        name = "uk_agent_eval_stage_idempotency",
        columnNames = "idempotency_key"
    )
}, indexes = {
    @Index(name = "idx_agent_eval_stage_status", columnList = "status"),
    @Index(name = "idx_agent_eval_stage_case", columnList = "case_id"),
    @Index(name = "idx_agent_eval_stage_created", columnList = "created_at")
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AgentEvaluationStageEntity {

  @Id
  @Column(length = 36, nullable = false, updatable = false)
  private String id;

  @Column(name = "run_id", nullable = false, length = 36)
  private String runId;

  @Column(name = "case_id", nullable = false, length = 36)
  private String caseId;

  @Column(name = "case_key", nullable = false, length = 160)
  private String caseKey;

  @Enumerated(EnumType.STRING)
  @Column(name = "stage_type", nullable = false, length = 40)
  private AgentEvaluationStageType stageType;

  @Column(name = "input_hash", nullable = false, length = 64)
  private String inputHash;

  @Column(name = "prompt_version", nullable = false, length = 80)
  private String promptVersion;

  @Column(nullable = false, length = 64)
  private String provider;

  @Column(nullable = false, length = 120)
  private String model;

  @Column(name = "idempotency_key", nullable = false, length = 64)
  private String idempotencyKey;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 24)
  private AgentEvaluationStageStatus status;

  @Column(name = "attempt_count", nullable = false)
  private Integer attemptCount;

  @Column(name = "duration_ms")
  private Long durationMs;

  @Column(name = "prompt_tokens")
  private Integer promptTokens;

  @Column(name = "completion_tokens")
  private Integer completionTokens;

  @Column(name = "total_tokens")
  private Integer totalTokens;

  @Column(name = "artifact_key", length = 500)
  private String artifactKey;

  @Enumerated(EnumType.STRING)
  @Column(name = "error_type", length = 40)
  private AgentEvaluationErrorType errorType;

  @Column(name = "error_summary", length = 500)
  private String errorSummary;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "started_at")
  private Instant startedAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @Column(name = "completed_at")
  private Instant completedAt;

  @Column(name = "next_retry_at")
  private Instant nextRetryAt;

  @Version
  private Long version;

  public static AgentEvaluationStageEntity create(
      String runId,
      String caseId,
      String caseKey,
      AgentEvaluationStageType stageType,
      String inputHash,
      String promptVersion,
      String provider,
      String model,
      String idempotencyKey,
      Instant now
  ) {
    var entity = new AgentEvaluationStageEntity();
    entity.id = UUID.randomUUID().toString();
    entity.runId = runId;
    entity.caseId = caseId;
    entity.caseKey = caseKey;
    entity.stageType = stageType;
    entity.inputHash = inputHash;
    entity.promptVersion = promptVersion;
    entity.provider = provider;
    entity.model = model;
    entity.idempotencyKey = idempotencyKey;
    entity.status = AgentEvaluationStageStatus.PENDING;
    entity.attemptCount = 0;
    entity.createdAt = now;
    entity.updatedAt = now;
    return entity;
  }

  public void succeed(
      String artifactKey,
      long durationMs,
      Integer promptTokens,
      Integer completionTokens,
      Integer totalTokens,
      Instant completedAt
  ) {
    requireRunning();
    this.status = AgentEvaluationStageStatus.SUCCEEDED;
    this.artifactKey = artifactKey;
    this.durationMs = durationMs;
    this.promptTokens = promptTokens;
    this.completionTokens = completionTokens;
    this.totalTokens = totalTokens;
    this.errorType = null;
    this.errorSummary = null;
    this.nextRetryAt = null;
    this.completedAt = completedAt;
    this.updatedAt = completedAt;
  }

  public void waitForRetry(
      AgentEvaluationErrorType errorType,
      String errorSummary,
      Instant nextRetryAt,
      Instant now
  ) {
    requireRunning();
    this.status = AgentEvaluationStageStatus.RETRY_WAITING;
    this.errorType = errorType;
    this.errorSummary = errorSummary;
    this.nextRetryAt = nextRetryAt;
    this.updatedAt = now;
  }

  public void pauseForCredential(String errorSummary, Instant now) {
    requireRunning();
    this.status = AgentEvaluationStageStatus.PAUSED;
    this.errorType = AgentEvaluationErrorType.INVALID_CREDENTIAL;
    this.errorSummary = errorSummary;
    this.updatedAt = now;
  }

  public void resumeAfterCredentialRefresh(Instant now) {
    if (status != AgentEvaluationStageStatus.PAUSED
        || errorType != AgentEvaluationErrorType.INVALID_CREDENTIAL) {
      throw new IllegalStateException(
          "Stage must be PAUSED for INVALID_CREDENTIAL but was " + status + "/" + errorType
      );
    }
    this.status = AgentEvaluationStageStatus.RETRY_WAITING;
    this.errorType = null;
    this.errorSummary = null;
    this.nextRetryAt = now;
    this.updatedAt = now;
  }

  public void fail(
      AgentEvaluationErrorType errorType,
      String errorSummary,
      Instant now
  ) {
    requireRunning();
    this.status = AgentEvaluationStageStatus.FAILED;
    this.errorType = errorType;
    this.errorSummary = errorSummary;
    this.completedAt = now;
    this.updatedAt = now;
  }

  private void requireRunning() {
    if (status != AgentEvaluationStageStatus.RUNNING) {
      throw new IllegalStateException("Stage must be RUNNING but was " + status);
    }
  }
}
