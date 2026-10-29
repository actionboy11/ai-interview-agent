package interview.guide.modules.interview.dynamic.repository;

import interview.guide.modules.interview.dynamic.model.DynamicInterviewRuntimeEntity;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DynamicInterviewRuntimeRepository
    extends JpaRepository<DynamicInterviewRuntimeEntity, Long> {
  Optional<DynamicInterviewRuntimeEntity> findBySession_SessionId(String sessionId);

  Optional<DynamicInterviewRuntimeEntity> findByOwner_IdAndSession_SessionId(
      UUID ownerId, String sessionId);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select r from DynamicInterviewRuntimeEntity r where r.session.sessionId = :sessionId")
  Optional<DynamicInterviewRuntimeEntity> findBySessionIdForUpdate(
      @Param("sessionId") String sessionId);
}
