package interview.guide.modules.auth.repository;

import interview.guide.modules.auth.model.UserEntity;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<UserEntity, UUID> {
  Optional<UserEntity> findByNormalizedUsername(String normalizedUsername);
}
