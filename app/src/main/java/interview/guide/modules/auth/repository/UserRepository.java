package interview.guide.modules.auth.repository;

import interview.guide.modules.auth.model.UserEntity;
import java.util.Optional;
import java.util.UUID;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<UserEntity, UUID> {
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select u from UserEntity u where u.id = :id")
  Optional<UserEntity> findByIdForAbilityDecision(@Param("id") UUID id);
  Optional<UserEntity> findByNormalizedUsername(String normalizedUsername);
}
