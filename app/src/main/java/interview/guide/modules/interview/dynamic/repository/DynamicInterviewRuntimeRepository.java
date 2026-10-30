package interview.guide.modules.interview.dynamic.repository;

import interview.guide.modules.interview.dynamic.model.DynamicInterviewRuntimeEntity;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DynamicInterviewRuntimeRepository
    extends JpaRepository<DynamicInterviewRuntimeEntity, Long> {
  interface RuntimeMetadata {
    String getSessionId();
    Integer getPlannedMainQuestionCount();
  }

  @Query("select r.session.sessionId as sessionId, r.plannedMainQuestionCount as plannedMainQuestionCount "
      + "from DynamicInterviewRuntimeEntity r where r.session.sessionId in :ids")
  List<RuntimeMetadata> findMetadataBySessionIds(@Param("ids") List<String> ids);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  // Scalar columns keep this a runtime-only lock; joining session could lock it too early on PG.
  @Query("select r from DynamicInterviewRuntimeEntity r where r.ownerUserId = :ownerId "
      + "and r.sessionDbId in :ids order by r.sessionDbId")
  List<DynamicInterviewRuntimeEntity> lockForDeletion(@Param("ownerId") UUID ownerId,
      @Param("ids") List<Long> ids);
  Optional<DynamicInterviewRuntimeEntity> findBySession_SessionId(String sessionId);

  Optional<DynamicInterviewRuntimeEntity> findByOwner_IdAndSession_SessionId(
      UUID ownerId, String sessionId);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select r from DynamicInterviewRuntimeEntity r where r.session.sessionId = :sessionId")
  Optional<DynamicInterviewRuntimeEntity> findBySessionIdForUpdate(
      @Param("sessionId") String sessionId);
}
