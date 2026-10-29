package interview.guide.modules.interview.dynamic.repository;

import interview.guide.modules.interview.dynamic.model.DynamicEvaluationOutboxEntity;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DynamicEvaluationOutboxRepository
    extends JpaRepository<DynamicEvaluationOutboxEntity, UUID> {
  Optional<DynamicEvaluationOutboxEntity> findBySession_IdAndFrozenVersion(
      Long sessionId, long frozenVersion);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select o from DynamicEvaluationOutboxEntity o where o.id = :id")
  Optional<DynamicEvaluationOutboxEntity> findByIdForUpdate(@Param("id") UUID id);
}
