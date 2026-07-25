package interview.guide.modules.knowledgebase.deadletter;

import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface KnowledgeVectorizationDeadLetterRepository
    extends JpaRepository<KnowledgeVectorizationDeadLetterEntity, Long> {

    boolean existsByOriginalMessageId(String originalMessageId);

    Optional<KnowledgeVectorizationDeadLetterEntity> findByOriginalMessageId(String originalMessageId);

    Page<KnowledgeVectorizationDeadLetterEntity> findByStatus(
        KnowledgeVectorizationDeadLetterStatus status,
        Pageable pageable
    );

    @Modifying
    @Query("""
        update KnowledgeVectorizationDeadLetterEntity d
           set d.status = interview.guide.modules.knowledgebase.deadletter.KnowledgeVectorizationDeadLetterStatus.REPLAYING
         where d.id = :id
           and d.status = interview.guide.modules.knowledgebase.deadletter.KnowledgeVectorizationDeadLetterStatus.PENDING
        """)
    int claimForReplay(@Param("id") Long id);
}
