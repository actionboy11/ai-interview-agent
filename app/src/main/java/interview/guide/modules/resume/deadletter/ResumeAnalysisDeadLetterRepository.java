package interview.guide.modules.resume.deadletter;

import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ResumeAnalysisDeadLetterRepository
    extends JpaRepository<ResumeAnalysisDeadLetterEntity, Long> {

    boolean existsByOriginalMessageId(String originalMessageId);

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
