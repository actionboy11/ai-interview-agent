package interview.guide.modules.resume.repository;

import interview.guide.modules.resume.model.ResumeEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;
import java.util.List;

/**
 * 简历Repository
 */
@Repository
public interface ResumeRepository extends JpaRepository<ResumeEntity, Long> {

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
