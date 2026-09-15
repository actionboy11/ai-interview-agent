package interview.guide.modules.resume.listener;

import interview.guide.common.async.AbstractStreamProducer;
import interview.guide.common.constant.AsyncTaskStreamConstants;
import interview.guide.common.model.AsyncTaskStatus;
import interview.guide.common.transaction.TransactionalExecutor;
import interview.guide.infrastructure.redis.RedisService;
import interview.guide.modules.resume.messaging.ResumeAnalysisTaskPublisher;
import interview.guide.modules.resume.repository.ResumeRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import java.util.Map;
import java.util.UUID;

/**
 * 简历分析任务生产者
 * 负责发送分析任务到 Redis Stream
 */
@Slf4j
@Component
@ConditionalOnProperty(
    name = "app.resume.messaging.provider",
    havingValue = "redis-stream",
    matchIfMissing = true
)
public class AnalyzeStreamProducer
    extends AbstractStreamProducer<AnalyzeStreamProducer.AnalyzeTaskPayload>
    implements ResumeAnalysisTaskPublisher {
// AnalyzeStreamProducer 继承自 AbstractStreamProducer，专门用于发送简历分析任务到 Redis Stream。
// <AnalyzeTaskPayload> 表示任务的负载类型为 AnalyzeTaskPayload。

    private final ResumeRepository resumeRepository;
    private final TransactionalExecutor transactionalExecutor;

    //record关键字用于定义一个不可变的数据类，自动生成构造函数、getter、equals、hashCode和toString方法
    record AnalyzeTaskPayload(Long resumeId, String content) {}

    public AnalyzeStreamProducer(
        RedisService redisService,
        ResumeRepository resumeRepository,
        TransactionalExecutor transactionalExecutor
    ) {
        super(redisService);
        this.resumeRepository = resumeRepository;
        this.transactionalExecutor = transactionalExecutor;
    }

    /**
     * 发送分析任务到 Redis Stream
     *
     * @param resumeId 简历ID
     * @param content  简历内容
     */
    public void sendAnalyzeTask(Long resumeId, String content) {
        // 发送任务到 Redis Stream，封装为 AnalyzeTaskPayload 对象
        sendTask(new AnalyzeTaskPayload(resumeId, content));
    }

    @Override
    public PublishReceipt publish(Long resumeId) {
        String content = resumeRepository.findById(resumeId)
            .map(resume -> resume.getResumeText())
            .orElseThrow(() -> new IllegalArgumentException("Resume not found: " + resumeId));
        sendAnalyzeTask(resumeId, content);
        return new PublishReceipt(UUID.randomUUID());
    }

    @Override
    protected String taskDisplayName() {
        return "分析";
    }

    @Override
    protected String streamKey() {
        return AsyncTaskStreamConstants.RESUME_ANALYZE_STREAM_KEY;
    }

    @Override
    protected Map<String, String> buildMessage(AnalyzeTaskPayload payload) {
        return Map.of(
            AsyncTaskStreamConstants.FIELD_RESUME_ID, payload.resumeId().toString(),
            AsyncTaskStreamConstants.FIELD_CONTENT, payload.content(),
            AsyncTaskStreamConstants.FIELD_RETRY_COUNT, "0"
        );
    }

    @Override
    protected String payloadIdentifier(AnalyzeTaskPayload payload) {
        return "resumeId=" + payload.resumeId();
    }

    @Override
    protected void onSendFailed(AnalyzeTaskPayload payload, String error) {
        transactionalExecutor.runRequiresNew(
            () -> updateAnalyzeStatus(payload.resumeId(), AsyncTaskStatus.FAILED, truncateError(error)));
    }

    /**
     * 更新分析状态
     */
    private void updateAnalyzeStatus(Long resumeId, AsyncTaskStatus status, String error) {
        resumeRepository.findById(resumeId).ifPresent(resume -> {
            resume.setAnalyzeStatus(status);
            if (error != null) {
                resume.setAnalyzeError(error.length() > 500 ? error.substring(0, 500) : error);
            }
            resumeRepository.save(resume);
        });
    }
}
