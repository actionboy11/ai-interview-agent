package interview.guide.modules.memory.repository;

import interview.guide.modules.memory.model.AbilityProfileProposalEntity;
import interview.guide.modules.memory.model.AbilityProposalStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

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
}
