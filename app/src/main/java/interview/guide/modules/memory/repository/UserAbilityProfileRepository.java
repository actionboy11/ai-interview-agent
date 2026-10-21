package interview.guide.modules.memory.repository;

import interview.guide.modules.memory.model.AbilityProfileStatus;
import interview.guide.modules.memory.model.UserAbilityProfileEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserAbilityProfileRepository extends JpaRepository<UserAbilityProfileEntity, Long> {
  Optional<UserAbilityProfileEntity> findByOwnerIdAndId(UUID ownerId, Long id);

  Optional<UserAbilityProfileEntity> findByOwnerIdAndSkillIdAndCategoryKey(
      UUID ownerId, String skillId, String categoryKey);

  List<UserAbilityProfileEntity> findByOwnerIdAndSkillIdAndStatus(
      UUID ownerId, String skillId, AbilityProfileStatus status);
}
