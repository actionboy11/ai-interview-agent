package interview.guide.modules.auth.repository;

import interview.guide.modules.auth.model.RegistrationInviteEntity;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RegistrationInviteRepository extends JpaRepository<RegistrationInviteEntity, UUID> {
  Page<RegistrationInviteEntity> findAllByOrderByCreatedAtDesc(Pageable pageable);

  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(value = "update registration_invites set status = 'REVOKED', updated_at = :now, "
      + "version = version + 1 where id = :id and status = 'ACTIVE'", nativeQuery = true)
  int revokeActiveInvite(@Param("id") UUID id, @Param("now") Instant now);
  /**
   * Consumes an unexpired invite atomically. The caller must supply the transaction containing
   * user creation and treat a zero row count as an invalid invite.
   */
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(value = """
      update registration_invites
         set status = 'USED', used_by = :userId, used_at = :now,
             updated_at = :now, version = version + 1
       where invite_hash = :inviteHash and status = 'ACTIVE' and expires_at > :now
      """, nativeQuery = true)
  int consumeActiveInvite(@Param("inviteHash") String inviteHash, @Param("userId") UUID userId,
      @Param("now") Instant now);
}
