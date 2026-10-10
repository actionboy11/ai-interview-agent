package interview.guide.modules.agentevaluation.repository;

import interview.guide.modules.agentevaluation.model.AgentEvaluationStageEntity;
import interview.guide.modules.agentevaluation.model.AgentEvaluationStageStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface AgentEvaluationStageRepository extends JpaRepository<AgentEvaluationStageEntity, String> {

  Optional<AgentEvaluationStageEntity> findByIdempotencyKey(String idempotencyKey);

  List<AgentEvaluationStageEntity> findByRunIdAndStatus(
      String runId,
      AgentEvaluationStageStatus status
  );

  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Transactional
  @Query("""
      update AgentEvaluationStageEntity stage
         set stage.status = interview.guide.modules.agentevaluation.model.AgentEvaluationStageStatus.RUNNING,
             stage.startedAt = :startedAt,
             stage.updatedAt = :startedAt,
             stage.attemptCount = stage.attemptCount + 1,
             stage.version = stage.version + 1
       where stage.id = :stageId
         and stage.status in :expectedStatuses
      """)
  int claim(
      @Param("stageId") String stageId,
      @Param("expectedStatuses") Collection<AgentEvaluationStageStatus> expectedStatuses,
      @Param("startedAt") Instant startedAt
  );

  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Transactional
  @Query("""
      update AgentEvaluationStageEntity stage
         set stage.status = interview.guide.modules.agentevaluation.model.AgentEvaluationStageStatus.RETRY_WAITING,
             stage.errorType = interview.guide.modules.agentevaluation.model.AgentEvaluationErrorType.INFRASTRUCTURE_ERROR,
             stage.errorSummary = 'RUNNING 阶段超时，已回收等待重试',
             stage.nextRetryAt = :recoveredAt,
             stage.updatedAt = :recoveredAt,
             stage.version = stage.version + 1
       where stage.status = interview.guide.modules.agentevaluation.model.AgentEvaluationStageStatus.RUNNING
         and stage.updatedAt < :staleBefore
      """)
  int recoverOrphanedRunningStages(
      @Param("staleBefore") Instant staleBefore,
      @Param("recoveredAt") Instant recoveredAt
  );
}
