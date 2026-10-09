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
import java.util.Optional;

@Repository
public interface AgentEvaluationStageRepository extends JpaRepository<AgentEvaluationStageEntity, String> {

  Optional<AgentEvaluationStageEntity> findByIdempotencyKey(String idempotencyKey);

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
}
