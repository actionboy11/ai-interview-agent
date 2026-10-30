package interview.guide.modules.interview.dynamic.repository;

import interview.guide.modules.interview.dynamic.model.DynamicEvaluationOutboxEntity;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DynamicEvaluationOutboxRepository
    extends JpaRepository<DynamicEvaluationOutboxEntity, UUID> {
  interface OutboxReference {
    UUID getId();
    String getSessionId();
  }

  @Query("select o.id as id, o.session.sessionId as sessionId from DynamicEvaluationOutboxEntity o "
      + "where o.deliveryStatus = 'PENDING' and o.nextDeliveryAt <= :now "
      + "and (o.leaseUntil is null or o.leaseUntil <= :now) "
      + "order by o.nextDeliveryAt, o.id")
  List<OutboxReference> findDeliveryCandidates(@Param("now") Instant now, Pageable page);

  Optional<DynamicEvaluationOutboxEntity> findBySession_IdAndFrozenVersion(
      Long sessionId, long frozenVersion);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select o from DynamicEvaluationOutboxEntity o where o.id = :id")
  Optional<DynamicEvaluationOutboxEntity> findByIdForUpdate(@Param("id") UUID id);
}
