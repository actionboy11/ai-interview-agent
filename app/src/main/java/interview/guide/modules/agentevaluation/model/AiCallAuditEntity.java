package interview.guide.modules.agentevaluation.model;

import interview.guide.common.ai.telemetry.AiParseOutcome;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "ai_call_audits", indexes = {
    @Index(name = "idx_ai_call_audit_stage", columnList = "stage_id"),
    @Index(name = "idx_ai_call_audit_observed", columnList = "observed_at")
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AiCallAuditEntity {

  @Id
  @Column(length = 36, nullable = false, updatable = false)
  private String id;

  @Column(name = "run_id", nullable = false, length = 36)
  private String runId;

  @Column(name = "case_id", nullable = false, length = 36)
  private String caseId;

  @Column(name = "stage_id", nullable = false, length = 36)
  private String stageId;

  @Column(nullable = false, length = 64)
  private String context;

  @Column(nullable = false, length = 64)
  private String provider;

  @Column(nullable = false, length = 120)
  private String model;

  @Column(nullable = false)
  private Integer attempt;

  @Enumerated(EnumType.STRING)
  @Column(name = "parse_outcome", nullable = false, length = 32)
  private AiParseOutcome parseOutcome;

  @Column(name = "latency_nanos", nullable = false)
  private Long latencyNanos;

  @Column(name = "prompt_tokens")
  private Integer promptTokens;

  @Column(name = "completion_tokens")
  private Integer completionTokens;

  @Column(name = "total_tokens")
  private Integer totalTokens;

  @Column(name = "artifact_key", length = 500)
  private String artifactKey;

  @Column(name = "error_type", length = 160)
  private String errorType;

  @Column(name = "error_summary", length = 500)
  private String errorSummary;

  @Column(name = "observed_at", nullable = false)
  private Instant observedAt;

  public static AiCallAuditEntity create(
      String runId,
      String caseId,
      String stageId,
      String context,
      String provider,
      String model,
      int attempt,
      AiParseOutcome parseOutcome,
      long latencyNanos,
      Integer promptTokens,
      Integer completionTokens,
      Integer totalTokens,
      String artifactKey,
      String errorType,
      String errorSummary,
      Instant observedAt
  ) {
    var entity = new AiCallAuditEntity();
    entity.id = UUID.randomUUID().toString();
    entity.runId = runId;
    entity.caseId = caseId;
    entity.stageId = stageId;
    entity.context = context;
    entity.provider = provider;
    entity.model = model;
    entity.attempt = attempt;
    entity.parseOutcome = parseOutcome;
    entity.latencyNanos = latencyNanos;
    entity.promptTokens = promptTokens;
    entity.completionTokens = completionTokens;
    entity.totalTokens = totalTokens;
    entity.artifactKey = artifactKey;
    entity.errorType = errorType;
    entity.errorSummary = errorSummary;
    entity.observedAt = observedAt;
    return entity;
  }
}
