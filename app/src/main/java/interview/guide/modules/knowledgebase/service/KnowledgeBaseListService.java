package interview.guide.modules.knowledgebase.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.infrastructure.file.FileStorageService;
import interview.guide.infrastructure.mapper.KnowledgeBaseMapper;
import interview.guide.modules.auth.security.CurrentUserProvider;
import interview.guide.modules.knowledgebase.model.KnowledgeBaseEntity;
import interview.guide.modules.knowledgebase.model.KnowledgeBaseListItemDTO;
import interview.guide.modules.knowledgebase.model.KnowledgeBaseStatsDTO;
import interview.guide.modules.knowledgebase.model.RagChatMessageEntity.MessageType;
import interview.guide.modules.knowledgebase.model.VectorStatus;
import interview.guide.modules.knowledgebase.repository.KnowledgeBaseRepository;
import interview.guide.modules.knowledgebase.repository.RagChatMessageRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 知识库查询服务
 * 负责知识库列表和详情的查询
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeBaseListService {

    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final RagChatMessageRepository ragChatMessageRepository;
    private final KnowledgeBaseMapper knowledgeBaseMapper;
    private final FileStorageService fileStorageService;
    private final CurrentUserProvider currentUser;

    /**
     * 获取知识库列表（支持状态过滤和排序）
     * 
     * @param vectorStatus 向量化状态，null 表示不过滤
     * @param sortBy 排序字段，null 或 "time" 表示按时间排序
     * @return 知识库列表
     */
    public List<KnowledgeBaseListItemDTO> listKnowledgeBases(VectorStatus vectorStatus, String sortBy) {
        List<KnowledgeBaseEntity> entities;
        
        // 如果指定了状态，按状态过滤
        if (vectorStatus != null) {
            entities = knowledgeBaseRepository.findByOwnerIdAndVectorStatusOrderByUploadedAtDesc(
                currentUser.requireUserId(), vectorStatus);
        } else {
            // 否则获取所有知识库
            entities = knowledgeBaseRepository.findByOwnerIdOrderByUploadedAtDesc(
                currentUser.requireUserId());
        }
        
        // 如果指定了排序字段，在内存中排序
        if (sortBy != null && !sortBy.isBlank() && !sortBy.equalsIgnoreCase("time")) {
            entities = sortEntities(entities, sortBy);
        }
        
        return knowledgeBaseMapper.toListItemDTOList(entities);
    }

    /**
     * 获取所有知识库列表（保持向后兼容）
     */
    public List<KnowledgeBaseListItemDTO> listKnowledgeBases() {
        return listKnowledgeBases(null, null);
    }

    /**
     * 按向量化状态获取知识库列表（保持向后兼容）
     */
    public List<KnowledgeBaseListItemDTO> listKnowledgeBasesByStatus(VectorStatus vectorStatus) {
        return listKnowledgeBases(vectorStatus, null);
    }

    /**
     * 根据ID获取知识库详情
     */
    public Optional<KnowledgeBaseListItemDTO> getKnowledgeBase(Long id) {
        return knowledgeBaseRepository.findByOwnerIdAndId(currentUser.requireUserId(), id)
            .map(knowledgeBaseMapper::toListItemDTO);
    }

    /**
     * 根据ID获取知识库实体（用于删除等操作）
     */
    public Optional<KnowledgeBaseEntity> getKnowledgeBaseEntity(Long id) {
        return knowledgeBaseRepository.findByOwnerIdAndId(currentUser.requireUserId(), id);
    }

    /**
     * 根据ID列表获取知识库名称列表
     */
    public List<String> getKnowledgeBaseNames(List<Long> ids) {
        var owned = knowledgeBaseRepository.findAllByOwnerIdAndIdIn(currentUser.requireUserId(), ids);
        if (owned.size() != ids.stream().distinct().count()) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "知识库不存在");
        }
        Map<Long, String> namesById = owned.stream().collect(Collectors.toMap(
            KnowledgeBaseEntity::getId, KnowledgeBaseEntity::getName));
        return ids.stream().map(namesById::get).toList();
    }

    // ========== 分类管理 ==========

    /**
     * 获取所有分类
     */
    public List<String> getAllCategories() {
        return knowledgeBaseRepository.findAllCategoriesByOwnerId(currentUser.requireUserId());
    }

    /**
     * 根据分类获取知识库列表
     */
    public List<KnowledgeBaseListItemDTO> listByCategory(String category) {
        List<KnowledgeBaseEntity> entities;
        if (category == null || category.isBlank()) {
            entities = knowledgeBaseRepository.findByOwnerIdAndCategoryIsNullOrderByUploadedAtDesc(
                currentUser.requireUserId());
        } else {
            entities = knowledgeBaseRepository.findByOwnerIdAndCategoryOrderByUploadedAtDesc(
                currentUser.requireUserId(), category);
        }
        return knowledgeBaseMapper.toListItemDTOList(entities);
    }

    /**
     * 更新知识库分类
     */
    @Transactional
    public void updateCategory(Long id, String category) {
        KnowledgeBaseEntity entity = knowledgeBaseRepository.findByOwnerIdAndId(
                currentUser.requireUserId(), id)
            .orElseThrow(() -> new BusinessException(ErrorCode.KNOWLEDGE_BASE_NOT_FOUND, "知识库不存在"));
        entity.setCategory(category != null && !category.isBlank() ? category : null);
        knowledgeBaseRepository.save(entity);
        log.info("更新知识库分类: id={}, category={}", id, category);
    }

    // ========== 搜索功能 ==========

    /**
     * 按关键词搜索知识库
     */
    public List<KnowledgeBaseListItemDTO> search(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return listKnowledgeBases();
        }
        return knowledgeBaseMapper.toListItemDTOList(
            knowledgeBaseRepository.searchByKeywordForOwner(currentUser.requireUserId(), keyword.trim())
        );
    }

    // ========== 排序功能 ==========

    /**
     * 按指定字段排序获取知识库列表（保持向后兼容）
     */
    public List<KnowledgeBaseListItemDTO> listSorted(String sortBy) {
        return listKnowledgeBases(null, sortBy);
    }

    /**
     * 在内存中对实体列表排序
     */
    private List<KnowledgeBaseEntity> sortEntities(List<KnowledgeBaseEntity> entities, String sortBy) {
        return switch (sortBy.toLowerCase()) {
            case "size" -> entities.stream()
                .sorted((a, b) -> Long.compare(b.getFileSize(), a.getFileSize()))
                .toList();
            case "access" -> entities.stream()
                .sorted((a, b) -> Integer.compare(b.getAccessCount(), a.getAccessCount()))
                .toList();
            case "question" -> entities.stream()
                .sorted((a, b) -> Integer.compare(b.getQuestionCount(), a.getQuestionCount()))
                .toList();
            default -> entities; // time 已经在数据库层面排序了
        };
    }

    // ========== 统计功能 ==========

    /**
     * 获取知识库统计信息
     * 总提问次数从用户消息数统计，确保多知识库提问只算一次
     */
    public KnowledgeBaseStatsDTO getStatistics() {
        return new KnowledgeBaseStatsDTO(
            knowledgeBaseRepository.countByOwnerId(currentUser.requireUserId()),
            ragChatMessageRepository.countByTypeAndSessionOwnerId(
                MessageType.USER, currentUser.requireUserId()),
            knowledgeBaseRepository.sumAccessCountForOwner(currentUser.requireUserId()),
            knowledgeBaseRepository.countByOwnerIdAndVectorStatus(
                currentUser.requireUserId(), VectorStatus.COMPLETED),
            knowledgeBaseRepository.countByOwnerIdAndVectorStatus(
                currentUser.requireUserId(), VectorStatus.PROCESSING)
        );
    }

    // ========== 下载功能 ==========

    /**
     * 下载知识库文件
     */
    public byte[] downloadFile(Long id) {
        KnowledgeBaseEntity entity = knowledgeBaseRepository.findByOwnerIdAndId(
                currentUser.requireUserId(), id)
            .orElseThrow(() -> new BusinessException(ErrorCode.KNOWLEDGE_BASE_NOT_FOUND, "知识库不存在"));

        String storageKey = entity.getStorageKey();
        if (storageKey == null || storageKey.isBlank()) {
            throw new BusinessException(ErrorCode.STORAGE_DOWNLOAD_FAILED, "文件存储信息不存在");
        }

        log.info("下载知识库文件: id={}, filename={}", id, entity.getOriginalFilename());
        return fileStorageService.downloadFile(storageKey);
    }

    /**
     * 获取知识库文件信息（用于下载）
     */
    public KnowledgeBaseEntity getEntityForDownload(Long id) {
        return knowledgeBaseRepository.findByOwnerIdAndId(currentUser.requireUserId(), id)
            .orElseThrow(() -> new BusinessException(ErrorCode.KNOWLEDGE_BASE_NOT_FOUND, "知识库不存在"));
    }
}
