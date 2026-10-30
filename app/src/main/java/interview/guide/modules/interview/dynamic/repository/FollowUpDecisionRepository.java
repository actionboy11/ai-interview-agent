package interview.guide.modules.interview.dynamic.repository;

import interview.guide.modules.interview.dynamic.model.FollowUpDecisionEntity;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.List;
import java.util.UUID;
import java.time.Instant;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface FollowUpDecisionRepository extends JpaRepository<FollowUpDecisionEntity, UUID> {
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select d from FollowUpDecisionEntity d where d.session.id in :ids order by d.id")
  List<FollowUpDecisionEntity> lockForDeletion(@Param("ids") List<Long> ids);
  interface DecisionReference {
    UUID getId();
    String getSessionId();
  }

  @Query("select d.id as id, d.session.sessionId as sessionId from FollowUpDecisionEntity d "
      + "where d.id = :id")
  Optional<DecisionReference> findReferenceById(@Param("id") UUID id);

  @Query("select d.id as id, d.session.sessionId as sessionId from FollowUpDecisionEntity d "
      + "where d.status = 'PENDING' and d.deliveryStatus = 'PENDING' "
      + "and d.nextDeliveryAt <= :now and d.deadline > :now order by d.nextDeliveryAt, d.id")
  List<DecisionReference> findDeliveryCandidates(@Param("now") Instant now, Pageable page);

  @Query("select d.id as id, d.session.sessionId as sessionId from FollowUpDecisionEntity d "
      + "where d.status in ('PENDING', 'PROCESSING') and (d.deadline <= :now "
      + "or (d.status = 'PROCESSING' and (d.leaseUntil is null or d.leaseUntil <= :now))) "
      + "order by d.deadline, d.id")
  List<DecisionReference> findRecoveryCandidates(@Param("now") Instant now, Pageable page);

  List<FollowUpDecisionEntity> findBySession_IdAndStatusIn(
      Long sessionId, List<FollowUpDecisionEntity.Status> statuses);
  Optional<FollowUpDecisionEntity> findByOwner_IdAndRequestId(UUID ownerId, UUID requestId);

  Optional<FollowUpDecisionEntity> findBySourceAnswer_Id(Long sourceAnswerId);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select d from FollowUpDecisionEntity d where d.id = :id")
  Optional<FollowUpDecisionEntity> findByIdForUpdate(@Param("id") UUID id);
}
