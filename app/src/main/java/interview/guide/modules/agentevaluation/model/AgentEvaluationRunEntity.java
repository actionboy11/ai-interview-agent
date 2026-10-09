package interview.guide.modules.agentevaluation.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "agent_evaluation_runs", indexes = {
    @Index(name = "idx_agent_eval_run_status", columnList = "status"),
    @Index(name = "idx_agent_eval_run_created", columnList = "created_at")
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AgentEvaluationRunEntity {

  @Id
  @Column(length = 36, nullable = false, updatable = false)
  private String id;

  @Column(nullable = false, length = 120)
  private String name;

  @Column(name = "dataset_version", nullable = false, length = 80)
  private String datasetVersion;

  @Column(name = "prompt_version", nullable = false, length = 80)
  private String promptVersion;

  @Column(name = "git_commit", nullable = false, length = 64)
  private String gitCommit;

  @Column(nullable = false, length = 64)
  private String provider;

  @Column(nullable = false, length = 120)
  private String model;

  @Column(name = "planned_cases", nullable = false)
  private Integer plannedCases;

  @Column(name = "completed_cases", nullable = false)
  private Integer completedCases;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 20)
  private AgentEvaluationRunStatus status;

  @Column(name = "pause_reason", length = 500)
  private String pauseReason;

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

  public static AgentEvaluationRunEntity create(
      String name,
      String datasetVersion,
      String promptVersion,
      String gitCommit,
      String provider,
      String model,
      int plannedCases,
      Instant now
  ) {
    var entity = new AgentEvaluationRunEntity();
    entity.id = UUID.randomUUID().toString();
    entity.name = name;
    entity.datasetVersion = datasetVersion;
    entity.promptVersion = promptVersion;
    entity.gitCommit = gitCommit;
    entity.provider = provider;
    entity.model = model;
    entity.plannedCases = plannedCases;
    entity.completedCases = 0;
    entity.status = AgentEvaluationRunStatus.CREATED;
    entity.createdAt = now;
    entity.updatedAt = now;
    return entity;
  }

  public void pause(String reason, Instant now) {
    this.status = AgentEvaluationRunStatus.PAUSED;
    this.pauseReason = reason;
    this.updatedAt = now;
  }
}
