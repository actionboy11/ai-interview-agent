package interview.guide.modules.voiceinterview.deadletter;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface VoiceEvaluationDeadLetterRepository
    extends JpaRepository<VoiceEvaluationDeadLetterEntity, Long> {

    boolean existsByOriginalMessageId(String originalMessageId);

    Page<VoiceEvaluationDeadLetterEntity> findByStatus(
        VoiceEvaluationDeadLetterStatus status,
        Pageable pageable
    );

    @Modifying
    @Query("""
        update VoiceEvaluationDeadLetterEntity d
           set d.status = interview.guide.modules.voiceinterview.deadletter.VoiceEvaluationDeadLetterStatus.REPLAYING
         where d.id = :id
           and d.status = interview.guide.modules.voiceinterview.deadletter.VoiceEvaluationDeadLetterStatus.PENDING
        """)
    int claimForReplay(@Param("id") Long id);
}
