package interview.guide.modules.resume.service;

import interview.guide.common.config.AppConfigProperties;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.common.model.AsyncTaskStatus;
import interview.guide.common.transaction.TransactionalExecutor;
import interview.guide.infrastructure.file.FileStorageService;
import interview.guide.infrastructure.file.FileValidationService;
import interview.guide.modules.interview.model.ResumeAnalysisResponse;
import interview.guide.modules.resume.messaging.ResumeAnalysisTaskPublisher;
import interview.guide.modules.resume.model.ResumeEntity;
import interview.guide.modules.resume.repository.ResumeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Optional;

/**
 * 简历上传服务
 * 处理简历上传、解析的业务逻辑
 * AI 分析改为异步处理，通过 Redis Stream 实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ResumeUploadService {

    // ResumeUploadService 依赖多个服务来完成简历上传和分析的流程：
    private final ResumeParseService parseService;
    private final FileStorageService storageService;
    private final ResumePersistenceService persistenceService;
    private final AppConfigProperties appConfig;
    private final FileValidationService fileValidationService;
    private final ResumeAnalysisTaskPublisher analysisTaskPublisher;
    private final ResumeRepository resumeRepository;
    private final TransactionalExecutor transactionalExecutor;

    private static final long MAX_FILE_SIZE = 10 * 1024 * 1024; // 10MB

    /**
     * 上传并分析简历（异步）
     *
     * @param file 简历文件
     * @return 上传结果（分析将异步进行）
     */
    public Map<String, Object> uploadAndAnalyze(org.springframework.web.multipart.MultipartFile file) {
        long startTime = System.currentTimeMillis();

        // 1. 验证文件是否存在和大小
        fileValidationService.validateFile(file, MAX_FILE_SIZE, "简历");

        String fileName = file.getOriginalFilename();
        long fileSize = file.getSize();
        log.info("收到简历上传请求: {}, 大小: {} bytes ({}), 上传开始处理",
            fileName, fileSize, formatFileSize(fileSize));

        // 2. 验证文件类型
        //得到文件的MIME类型，确保上传的文件是允许的类型（如PDF、DOCX等）。
        // 底层使用 Apache Tika 进行内容检测，比仅依赖文件扩展名更可靠。
        String contentType = parseService.detectContentType(file);
        // 通过配置的允许类型列表进行验证，如果不符合要求则抛出异常
        validateContentType(contentType);

        // 3. 检查简历是否已存在（去重）
        //Optional是Java 8引入的一个容器类，用于表示一个值可能存在也可能不存在。
        // 它提供了一种优雅的方式来处理可能为null的值，避免了直接使用null导致的空指针异常。
        Optional<ResumeEntity> existingResume = persistenceService.findExistingResume(file);
        if (existingResume.isPresent()) {
            log.info("简历上传处理完成（重复）: {} - 耗时: {}ms",
                fileName, System.currentTimeMillis() - startTime);
            // 如果检测到重复简历，直接返回历史分析结果
            return handleDuplicateResume(existingResume.get());
        }

        // 4. 解析简历文本
        long parseStart = System.currentTimeMillis();
        // 解析简历文本内容，提取纯文本（resumeText）用于后续分析 （如AI分析）
        String resumeText = parseService.parseResume(file);
        if (resumeText == null || resumeText.trim().isEmpty()) {
            throw new BusinessException(ErrorCode.RESUME_PARSE_FAILED, "无法从文件中提取文本内容，请确保文件不是扫描版PDF");
        }
        log.info("简历文本解析完成: {} - 解析耗时: {}ms, 文本长度: {} 字符",
            fileName, System.currentTimeMillis() - parseStart, resumeText.length());

        // 5. 为什么保存简历到RustFS ？
        // 存储简历文件到RustFS，用于后续的简历下载和知识库构建。
        long storageStart = System.currentTimeMillis();
        // 保存简历文件到RustFS，并获取存储的文件Key和URL
        // 这里的fileKey是RustFS中存储文件的唯一标识符，fileUrl是可以通过HTTP访问该文件的URL
        String fileKey = storageService.uploadResume(file);
        String fileUrl = storageService.getFileUrl(fileKey);
        log.info("简历已存储到RustFS: {} - 存储耗时: {}ms",
            fileKey, System.currentTimeMillis() - storageStart);

        // 6. 保存简历到数据库（状态为 PENDING）
        ResumeEntity savedResume = persistenceService.saveResume(file, resumeText, fileKey, fileUrl);

        // 7. 发送分析任务到 Redis Stream（异步处理）
        analysisTaskPublisher.publish(savedResume.getId());

        long totalTime = System.currentTimeMillis() - startTime;
        log.info("简历上传处理完成: {}, resumeId={} - 总耗时: {}ms (解析+存储+入库)",
            fileName, savedResume.getId(), totalTime);

        // 8. 返回结果（状态为 PENDING，前端可轮询获取最新状态）
        return Map.of(
            "resume", Map.of(
                "id", savedResume.getId(),
                "filename", savedResume.getOriginalFilename(),
                "analyzeStatus", AsyncTaskStatus.PENDING.name()
            ),
            "storage", Map.of(
                "fileKey", fileKey,
                "fileUrl", fileUrl,
                "resumeId", savedResume.getId()
            ),
            "duplicate", false
        );
    }

    /**
     * 格式化文件大小为可读字符串
     */
    private String formatFileSize(long bytes) {
        if (bytes < 1024) return bytes + "B";
        if (bytes < 1024 * 1024) return String.format("%.1fKB", bytes / 1024.0);
        return String.format("%.1fMB", bytes / (1024.0 * 1024.0));
    }

    /**
     * 验证文件类型
     */
    private void validateContentType(String contentType) {
        fileValidationService.validateContentTypeByList(
            contentType,
            appConfig.getAllowedTypes(),
            "不支持的文件类型: " + contentType
        );
    }

    /**
     * 处理重复简历
     */
    private Map<String, Object> handleDuplicateResume(ResumeEntity resume) {
        log.info("检测到重复简历，返回历史分析结果: resumeId={}", resume.getId());

        // 获取历史分析结果
        Optional<ResumeAnalysisResponse> analysisOpt = persistenceService.getLatestAnalysisAsDTO(resume.getId());

        // 已有分析结果，直接返回
        // 没有分析结果（可能之前分析失败），返回当前状态
        return analysisOpt.map(resumeAnalysisResponse -> Map.of(
                "analysis", resumeAnalysisResponse,
                "storage", Map.of(
                        "fileKey", resume.getStorageKey() != null ? resume.getStorageKey() : "",
                        "fileUrl", resume.getStorageUrl() != null ? resume.getStorageUrl() : "",
                        "resumeId", resume.getId()
                ),
                "duplicate", true
        )).orElseGet(() -> Map.of(
                "resume", Map.of(
                        "id", resume.getId(),
                        "filename", resume.getOriginalFilename(),
                        "analyzeStatus", resume.getAnalyzeStatus() != null ? resume.getAnalyzeStatus().name() : AsyncTaskStatus.PENDING.name()
                ),
                "storage", Map.of(
                        "fileKey", resume.getStorageKey() != null ? resume.getStorageKey() : "",
                        "fileUrl", resume.getStorageUrl() != null ? resume.getStorageUrl() : "",
                        "resumeId", resume.getId()
                ),
                "duplicate", true
        ));
    }

    /**
     * 重新分析简历（手动重试）
     * 从数据库获取简历文本并发送分析任务
     *
     * @param resumeId 简历ID
     */
    public void reanalyze(Long resumeId) {
        ResumeReanalyzeSource source = loadReanalyzeSource(resumeId);

        log.info("开始重新分析简历: resumeId={}, filename={}", resumeId, source.originalFilename());

        String resumeText = source.resumeText();
        boolean shouldCacheResumeText = !hasText(resumeText);
        if (shouldCacheResumeText) {
            // 如果没有缓存的文本，尝试重新解析
            resumeText = parseService.downloadAndParseContent(
                source.storageKey(), source.originalFilename());
            if (!hasText(resumeText)) {
                throw new BusinessException(ErrorCode.RESUME_PARSE_FAILED, "无法获取简历文本内容");
            }
        }

        String taskContent = resumeText;
        transactionalExecutor.run(
            () -> updateResumeForReanalysis(resumeId, taskContent, shouldCacheResumeText));

        // 事务提交后再发送分析任务到 Stream
        analysisTaskPublisher.publish(resumeId);

        log.info("重新分析任务已发送: resumeId={}", resumeId);
    }

    private ResumeReanalyzeSource loadReanalyzeSource(Long resumeId) {
        ResumeEntity resume = resumeRepository.findById(resumeId)
            .orElseThrow(() -> new BusinessException(ErrorCode.RESUME_NOT_FOUND, "简历不存在"));
        return new ResumeReanalyzeSource(
            resume.getOriginalFilename(),
            resume.getStorageKey(),
            resume.getResumeText()
        );
    }

    private void updateResumeForReanalysis(
        Long resumeId,
        String resumeText,
        boolean shouldCacheResumeText
    ) {
        ResumeEntity resume = resumeRepository.findById(resumeId)
            .orElseThrow(() -> new BusinessException(ErrorCode.RESUME_NOT_FOUND, "简历不存在"));

        if (shouldCacheResumeText || !hasText(resume.getResumeText())) {
            resume.setResumeText(resumeText);
        }
        resume.setAnalyzeStatus(AsyncTaskStatus.PENDING);
        resume.setAnalyzeError(null);
        resumeRepository.save(resume);
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private record ResumeReanalyzeSource(
        String originalFilename,
        String storageKey,
        String resumeText
    ) {
    }
}
