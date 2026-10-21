package interview.guide.modules.memory.repository;

import interview.guide.modules.memory.model.UserAbilityEvidenceEntity;
import interview.guide.modules.memory.model.AbilityProposalStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserAbilityEvidenceRepository extends JpaRepository<UserAbilityEvidenceEntity, Long> {
  Optional<UserAbilityEvidenceEntity> findByOwnerIdAndInterviewAnswerId(UUID ownerId, Long answerId);

  List<UserAbilityEvidenceEntity> findByOwnerIdAndProposalId(UUID ownerId, Long proposalId);

  List<UserAbilityEvidenceEntity> findByOwnerIdAndInterviewSessionId(UUID ownerId, Long sessionId);

  List<UserAbilityEvidenceEntity> findByOwnerIdAndSkillIdAndCategoryKey(
      UUID ownerId, String skillId, String categoryKey);

  List<UserAbilityEvidenceEntity> findByOwnerIdAndSkillIdAndCategoryKeyIn(
      UUID ownerId, String skillId, List<String> categoryKeys);

  @Query("select count(distinct e.interviewSession.id) from UserAbilityEvidenceEntity e "
      + "where e.owner.id = :ownerId and e.skillId = :skillId and e.categoryKey = :categoryKey "
      + "and e.proposal.status in :statuses")
  long countDistinctAcceptedSessions(@Param("ownerId") UUID ownerId,
      @Param("skillId") String skillId, @Param("categoryKey") String categoryKey,
      @Param("statuses") List<AbilityProposalStatus> statuses);
}
