package interview.guide.modules.interview.dynamic.repository;

import interview.guide.modules.interview.dynamic.model.FollowUpDecisionEntity;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface FollowUpDecisionRepository extends JpaRepository<FollowUpDecisionEntity, UUID> {
  List<FollowUpDecisionEntity> findBySession_IdAndStatusIn(
      Long sessionId, List<FollowUpDecisionEntity.Status> statuses);
  Optional<FollowUpDecisionEntity> findByOwner_IdAndRequestId(UUID ownerId, UUID requestId);

  Optional<FollowUpDecisionEntity> findBySourceAnswer_Id(Long sourceAnswerId);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select d from FollowUpDecisionEntity d where d.id = :id")
  Optional<FollowUpDecisionEntity> findByIdForUpdate(@Param("id") UUID id);
}
