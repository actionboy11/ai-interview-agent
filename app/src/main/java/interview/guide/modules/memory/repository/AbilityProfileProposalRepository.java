package interview.guide.modules.memory.repository;

import interview.guide.modules.memory.model.AbilityProfileProposalEntity;
import interview.guide.modules.memory.model.AbilityProposalStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AbilityProfileProposalRepository
    extends JpaRepository<AbilityProfileProposalEntity, Long> {
  List<AbilityProfileProposalEntity> findByOwnerIdOrderByCreatedAtDescIdDesc(UUID ownerId);
  Optional<AbilityProfileProposalEntity> findByOwnerIdAndId(UUID ownerId, Long id);

  Optional<AbilityProfileProposalEntity> findByOwnerIdAndInterviewSessionIdAndCategoryKey(
      UUID ownerId, Long sessionId, String categoryKey);

  List<AbilityProfileProposalEntity> findByOwnerIdAndInterviewSessionId(
      UUID ownerId, Long sessionId);

  List<AbilityProfileProposalEntity> findByOwnerIdAndStatus(
      UUID ownerId, AbilityProposalStatus status);

  @Query("select coalesce(max(p.decisionOrder), 0) from AbilityProfileProposalEntity p "
      + "where p.owner.id = :ownerId and p.skillId = :skillId "
      + "and p.categoryKey = :categoryKey")
  long maxDecisionOrder(@Param("ownerId") UUID ownerId,
      @Param("skillId") String skillId, @Param("categoryKey") String categoryKey);
}
