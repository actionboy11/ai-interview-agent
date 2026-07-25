package interview.guide.modules.interview.deadletter;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface InterviewEvaluationDeadLetterRepository
    extends JpaRepository<InterviewEvaluationDeadLetterEntity, Long> {

    boolean existsByOriginalMessageId(String originalMessageId);

    Page<InterviewEvaluationDeadLetterEntity> findByStatus(
        InterviewEvaluationDeadLetterStatus status,
        Pageable pageable
    );

    @Modifying
    @Query("""
        update InterviewEvaluationDeadLetterEntity d
           set d.status = interview.guide.modules.interview.deadletter.InterviewEvaluationDeadLetterStatus.REPLAYING
         where d.id = :id
           and d.status = interview.guide.modules.interview.deadletter.InterviewEvaluationDeadLetterStatus.PENDING
        """)
    int claimForReplay(@Param("id") Long id);
}

