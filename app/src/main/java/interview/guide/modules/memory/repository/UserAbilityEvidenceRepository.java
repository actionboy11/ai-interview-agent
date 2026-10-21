package interview.guide.modules.memory.repository;

import interview.guide.modules.memory.model.UserAbilityEvidenceEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserAbilityEvidenceRepository extends JpaRepository<UserAbilityEvidenceEntity, Long> {
  Optional<UserAbilityEvidenceEntity> findByOwnerIdAndInterviewAnswerId(UUID ownerId, Long answerId);

  List<UserAbilityEvidenceEntity> findByOwnerIdAndProposalId(UUID ownerId, Long proposalId);

  List<UserAbilityEvidenceEntity> findByOwnerIdAndInterviewSessionId(UUID ownerId, Long sessionId);

  List<UserAbilityEvidenceEntity> findByOwnerIdAndSkillIdAndCategoryKey(
      UUID ownerId, String skillId, String categoryKey);
}
