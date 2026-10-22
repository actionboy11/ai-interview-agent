package interview.guide.modules.resume.repository;

import interview.guide.modules.resume.model.ResumeEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;
import java.util.List;

/**
 * 简历Repository
 */
@Repository
public interface ResumeRepository extends JpaRepository<ResumeEntity, Long> {

  @Query("select r from ResumeEntity r join fetch r.owner where r.id = :id")
  Optional<ResumeEntity> findByIdForWorker(@Param("id") Long id);

  Optional<ResumeEntity> findByOwnerIdAndId(UUID ownerId, Long id);

  List<ResumeEntity> findByOwnerIdOrderByUploadedAtDesc(UUID ownerId);

  Optional<ResumeEntity> findByOwnerIdAndFileHash(UUID ownerId, String fileHash);

  boolean existsByOwnerIdAndFileHash(UUID ownerId, String fileHash);
    
    /**
     * 根据文件哈希查找简历（用于去重）
     */
    Optional<ResumeEntity> findByFileHash(String fileHash);
    
    /**
     * 检查文件哈希是否存在
     */
    boolean existsByFileHash(String fileHash);
}
