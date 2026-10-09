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
@Table(name = "agent_evaluation_cases", uniqueConstraints = {
    @UniqueConstraint(
        name = "uk_agent_eval_case_run_key",
        columnNames = {"run_id", "case_key"}
    )
}, indexes = {
    @Index(name = "idx_agent_eval_case_status", columnList = "status"),
    @Index(name = "idx_agent_eval_case_run", columnList = "run_id")
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AgentEvaluationCaseEntity {

  @Id
  @Column(length = 36, nullable = false, updatable = false)
  private String id;

  @Column(name = "run_id", nullable = false, length = 36)
  private String runId;

  @Column(name = "case_key", nullable = false, length = 160)
  private String caseKey;

  @Column(name = "input_hash", nullable = false, length = 64)
  private String inputHash;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 20)
  private AgentEvaluationCaseStatus status;

  @Enumerated(EnumType.STRING)
  @Column(name = "current_stage", length = 40)
  private AgentEvaluationStageType currentStage;

  @Column(name = "resume_id")
  private Long resumeId;

  @Column(name = "session_id", length = 36)
  private String sessionId;

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

  @Version
  private Long version;

  public static AgentEvaluationCaseEntity create(
      String runId,
      String caseKey,
      String inputHash,
      Instant now
  ) {
    var entity = new AgentEvaluationCaseEntity();
    entity.id = UUID.randomUUID().toString();
    entity.runId = runId;
    entity.caseKey = caseKey;
    entity.inputHash = inputHash;
    entity.status = AgentEvaluationCaseStatus.PENDING;
    entity.createdAt = now;
    entity.updatedAt = now;
    return entity;
  }
}
