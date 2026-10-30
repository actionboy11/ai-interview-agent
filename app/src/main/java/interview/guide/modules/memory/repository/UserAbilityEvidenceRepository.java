package interview.guide.modules.memory.repository;

import interview.guide.modules.memory.model.UserAbilityEvidenceEntity;
import interview.guide.modules.memory.model.AbilityProposalStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserAbilityEvidenceRepository extends JpaRepository<UserAbilityEvidenceEntity, Long> {
  interface ConfirmedSessionCount {
    String getCategoryKey();
    long getSessionCount();
  }

  @EntityGraph(attributePaths = "sources")
  Optional<UserAbilityEvidenceEntity> findByOwnerIdAndInterviewAnswerId(UUID ownerId, Long answerId);

  @EntityGraph(attributePaths = "sources")
  List<UserAbilityEvidenceEntity> findByOwnerIdAndProposalId(UUID ownerId, Long proposalId);

  @EntityGraph(attributePaths = "sources")
  List<UserAbilityEvidenceEntity> findByOwnerIdAndInterviewSessionId(UUID ownerId, Long sessionId);

  @EntityGraph(attributePaths = "sources")
  List<UserAbilityEvidenceEntity> findByOwnerIdAndSkillIdAndCategoryKey(
      UUID ownerId, String skillId, String categoryKey);

  @EntityGraph(attributePaths = "sources")
  List<UserAbilityEvidenceEntity> findByOwnerIdAndSkillIdAndCategoryKeyIn(
      UUID ownerId, String skillId, List<String> categoryKeys);

  @Query("select count(distinct e.interviewSession.id) from UserAbilityEvidenceEntity e "
      + "where e.owner.id = :ownerId and e.skillId = :skillId and e.categoryKey = :categoryKey "
      + "and e.proposal.status in :statuses")
  long countDistinctAcceptedSessions(@Param("ownerId") UUID ownerId,
      @Param("skillId") String skillId, @Param("categoryKey") String categoryKey,
      @Param("statuses") List<AbilityProposalStatus> statuses);

  @Query("select e.categoryKey as categoryKey, count(distinct e.interviewSession.id) "
      + "as sessionCount from UserAbilityEvidenceEntity e "
      + "where e.owner.id = :ownerId and e.skillId = :skillId "
      + "and e.proposal.status in :statuses group by e.categoryKey")
  List<ConfirmedSessionCount> countDistinctAcceptedSessionsByCategory(
      @Param("ownerId") UUID ownerId, @Param("skillId") String skillId,
      @Param("statuses") List<AbilityProposalStatus> statuses);
}
