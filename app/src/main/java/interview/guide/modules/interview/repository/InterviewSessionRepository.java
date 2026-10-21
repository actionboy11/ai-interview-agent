package interview.guide.modules.interview.repository;

import interview.guide.modules.interview.model.InterviewSessionEntity;
import interview.guide.modules.interview.model.InterviewSessionEntity.SessionStatus;
import interview.guide.modules.resume.model.ResumeEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import jakarta.persistence.LockModeType;

/**
 * 面试会话Repository
 */
@Repository
public interface InterviewSessionRepository extends JpaRepository<InterviewSessionEntity, Long> {

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  List<InterviewSessionEntity> findByOwnerIdAndIdInOrderById(UUID ownerId, List<Long> ids);

  Optional<InterviewSessionEntity> findByOwnerIdAndId(UUID ownerId, Long id);

  @EntityGraph(attributePaths = "resume")
  List<InterviewSessionEntity> findByOwnerIdOrderByCreatedAtDesc(UUID ownerId);

  @EntityGraph(attributePaths = {"resume", "answers"})
  Optional<InterviewSessionEntity> findByOwnerIdAndSessionId(UUID ownerId, String sessionId);

  @EntityGraph(attributePaths = {"resume", "resume.owner"})
  List<InterviewSessionEntity> findByOwnerIdAndResumeIdOrderByCreatedAtDesc(UUID ownerId, Long resumeId);

  @EntityGraph(attributePaths = {"resume", "resume.owner"})
  Optional<InterviewSessionEntity> findFirstByOwnerIdAndResumeIdAndStatusInOrderByCreatedAtDesc(
      UUID ownerId, Long resumeId, List<SessionStatus> statuses);

  @EntityGraph(attributePaths = {"resume", "resume.owner"})
  Optional<InterviewSessionEntity>
      findFirstByOwnerIdAndResumeIdAndStatusInAndQuestionsJsonIsNotNullOrderByCreatedAtDesc(
          UUID ownerId, Long resumeId, List<SessionStatus> statuses);

  @EntityGraph(attributePaths = "resume")
  List<InterviewSessionEntity> findTop10ByOwnerIdAndSkillIdOrderByCreatedAtDesc(UUID ownerId, String skillId);

  @EntityGraph(attributePaths = {"resume", "resume.owner"})
  List<InterviewSessionEntity> findTop10ByOwnerIdAndResumeIdAndSkillIdOrderByCreatedAtDesc(
      UUID ownerId, Long resumeId, String skillId);

    /**
     * 根据会话ID查找
     */
    Optional<InterviewSessionEntity> findBySessionId(String sessionId);

    boolean existsByEvaluationMessageId(String evaluationMessageId);

    /**
     * 根据会话ID查找（同时加载关联的简历）
     */
    @Query("SELECT s FROM InterviewSessionEntity s LEFT JOIN FETCH s.resume WHERE s.sessionId = :sessionId")
    Optional<InterviewSessionEntity> findBySessionIdWithResume(@Param("sessionId") String sessionId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM InterviewSessionEntity s JOIN FETCH s.owner "
        + "LEFT JOIN FETCH s.resume r LEFT JOIN FETCH r.owner "
        + "WHERE s.sessionId = :sessionId")
    Optional<InterviewSessionEntity> findBySessionIdForDerivation(
        @Param("sessionId") String sessionId);
    
    /**
     * 根据简历查找所有面试记录
     */
    List<InterviewSessionEntity> findByResumeOrderByCreatedAtDesc(ResumeEntity resume);
    
    /**
     * 根据简历ID查找所有面试记录
     */
    List<InterviewSessionEntity> findByResumeIdOrderByCreatedAtDesc(Long resumeId);

    /**
     * 根据简历ID查找最近的面试记录（用于历史题去重）
     */
    List<InterviewSessionEntity> findTop10ByResumeIdOrderByCreatedAtDesc(Long resumeId);
    
    /**
     * 查找简历的未完成面试（CREATED或IN_PROGRESS状态）
     */
    Optional<InterviewSessionEntity> findFirstByResumeIdAndStatusInOrderByCreatedAtDesc(
        Long resumeId, 
        List<SessionStatus> statuses
    );
    
    /**
     * 根据简历ID和状态查找会话
     */
    Optional<InterviewSessionEntity> findByResumeIdAndStatusIn(
        Long resumeId,
        List<SessionStatus> statuses
    );

    /**
     * 查找所有面试会话（按创建时间倒序）
     */
    List<InterviewSessionEntity> findAllByOrderByCreatedAtDesc();

    /**
     * 根据 skillId 查找最近的面试记录（用于通用模式历史题去重）
     */
    List<InterviewSessionEntity> findTop10BySkillIdOrderByCreatedAtDesc(String skillId);

    /**
     * 根据 resumeId + skillId 查找最近的面试记录（精确匹配）
     */
    List<InterviewSessionEntity> findTop10ByResumeIdAndSkillIdOrderByCreatedAtDesc(Long resumeId, String skillId);

    /**
     * 查找创建时间早于指定时刻、且处于给定状态的会话（用于超时未完成会话清理）
     */
    @EntityGraph(attributePaths = "resume")
    List<InterviewSessionEntity> findByStatusInAndCreatedAtBefore(
        List<SessionStatus> statuses,
        LocalDateTime before
    );
}
