package interview.guide.modules.resume.deadletter;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ResumeAnalysisDeadLetterRepository
    extends JpaRepository<ResumeAnalysisDeadLetterEntity, Long> {

    boolean existsByOriginalMessageId(String originalMessageId);

    @Query("""
        select d from ResumeAnalysisDeadLetterEntity d
        where exists (select r.id from ResumeEntity r where r.id = d.resumeId and r.owner.id = :ownerId)
          and (:status is null or d.status = :status)
        """)
    Page<ResumeAnalysisDeadLetterEntity> findOwned(@Param("ownerId") UUID ownerId,
        @Param("status") ResumeAnalysisDeadLetterStatus status, Pageable pageable);

    @Query("""
        select d from ResumeAnalysisDeadLetterEntity d where d.id = :id
        and exists (select r.id from ResumeEntity r where r.id = d.resumeId and r.owner.id = :ownerId)
        """)
    Optional<ResumeAnalysisDeadLetterEntity> findOwnedById(@Param("ownerId") UUID ownerId, @Param("id") Long id);

    Optional<ResumeAnalysisDeadLetterEntity> findByOriginalMessageId(String originalMessageId);

    Page<ResumeAnalysisDeadLetterEntity> findByStatus(
        ResumeAnalysisDeadLetterStatus status,
        Pageable pageable
    );

    @Modifying
    @Query("""
        update ResumeAnalysisDeadLetterEntity d
           set d.status = interview.guide.modules.resume.deadletter.ResumeAnalysisDeadLetterStatus.REPLAYING
         where d.id = :id
           and d.status = interview.guide.modules.resume.deadletter.ResumeAnalysisDeadLetterStatus.PENDING
        """)
    int claimForReplay(@Param("id") Long id);
}
