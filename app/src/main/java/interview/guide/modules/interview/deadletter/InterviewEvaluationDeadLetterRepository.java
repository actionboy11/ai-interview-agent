package interview.guide.modules.interview.deadletter;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.Optional;
import java.util.UUID;

public interface InterviewEvaluationDeadLetterRepository
    extends JpaRepository<InterviewEvaluationDeadLetterEntity, Long> {

    boolean existsByOriginalMessageId(String originalMessageId);

    @Query("""
        select d from InterviewEvaluationDeadLetterEntity d
        where exists (select s.id from InterviewSessionEntity s left join s.resume r
          where s.sessionId = d.sessionId and s.owner.id = :ownerId
            and (r is null or r.owner.id = :ownerId))
          and (:status is null or d.status = :status)
        """)
    Page<InterviewEvaluationDeadLetterEntity> findOwned(@Param("ownerId") UUID ownerId,
        @Param("status") InterviewEvaluationDeadLetterStatus status, Pageable pageable);

    @Query("""
        select d from InterviewEvaluationDeadLetterEntity d where d.id = :id
        and exists (select s.id from InterviewSessionEntity s left join s.resume r
          where s.sessionId = d.sessionId and s.owner.id = :ownerId
            and (r is null or r.owner.id = :ownerId))
        """)
    Optional<InterviewEvaluationDeadLetterEntity> findOwnedById(@Param("ownerId") UUID ownerId, @Param("id") Long id);

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

