package interview.guide.modules.knowledgebase.listener;

import interview.guide.common.async.AbstractStreamProducer;
import interview.guide.common.constant.AsyncTaskStreamConstants;
import interview.guide.infrastructure.redis.RedisService;
import interview.guide.modules.knowledgebase.messaging.KnowledgeVectorizationTaskPublisher;
import interview.guide.modules.knowledgebase.model.VectorStatus;
import interview.guide.modules.knowledgebase.repository.KnowledgeBaseRepository;
import interview.guide.modules.knowledgebase.service.KnowledgeBaseParseService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 向量化任务生产者
 * 负责发送向量化任务到 Redis Stream
 */
@Slf4j
@Component
@ConditionalOnProperty(
    name = "app.knowledge-vectorization.messaging.provider",
    havingValue = "redis-stream"
)
public class VectorizeStreamProducer
    extends AbstractStreamProducer<VectorizeStreamProducer.VectorizeTaskPayload>
    implements KnowledgeVectorizationTaskPublisher {

    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final KnowledgeBaseParseService parseService;

    record VectorizeTaskPayload(Long kbId, String content) {}

    public VectorizeStreamProducer(
        RedisService redisService,
        KnowledgeBaseRepository knowledgeBaseRepository,
        KnowledgeBaseParseService parseService
    ) {
        super(redisService);
        this.knowledgeBaseRepository = knowledgeBaseRepository;
        this.parseService = parseService;
    }

    @Override
    public PublishReceipt publish(Long knowledgeBaseId) {
        var knowledgeBase = knowledgeBaseRepository.findById(knowledgeBaseId)
            .orElseThrow(() -> new interview.guide.common.exception.BusinessException(
                interview.guide.common.exception.ErrorCode.NOT_FOUND,
                "知识库不存在"
            ));
        String content = parseService.downloadAndParseContent(
            knowledgeBase.getStorageKey(),
            knowledgeBase.getOriginalFilename()
        );
        sendVectorizeTask(knowledgeBaseId, content);
        return new PublishReceipt(java.util.UUID.randomUUID());
    }

    /**
     * 发送向量化任务到 Redis Stream
     *
     * @param kbId    知识库ID
     * @param content 文档内容
     */
    public void sendVectorizeTask(Long kbId, String content) {
        sendTask(new VectorizeTaskPayload(kbId, content));
    }

    @Override
    protected String taskDisplayName() {
        return "向量化";
    }

    @Override
    protected String streamKey() {
        return AsyncTaskStreamConstants.KB_VECTORIZE_STREAM_KEY;
    }

    @Override
    protected Map<String, String> buildMessage(VectorizeTaskPayload payload) {
        return Map.of(
            AsyncTaskStreamConstants.FIELD_KB_ID, payload.kbId().toString(),
            AsyncTaskStreamConstants.FIELD_CONTENT, payload.content(),
            AsyncTaskStreamConstants.FIELD_RETRY_COUNT, "0"
        );
    }

    @Override
    protected String payloadIdentifier(VectorizeTaskPayload payload) {
        return "kbId=" + payload.kbId();
    }

    @Override
    protected void onSendFailed(VectorizeTaskPayload payload, String error) {
        updateVectorStatus(payload.kbId(), VectorStatus.FAILED, truncateError(error));
    }

    /**
     * 更新向量化状态
     */
    private void updateVectorStatus(Long kbId, VectorStatus status, String error) {
        knowledgeBaseRepository.findById(kbId).ifPresent(kb -> {
            kb.setVectorStatus(status);
            if (error != null) {
                kb.setVectorError(error.length() > 500 ? error.substring(0, 500) : error);
            }
            knowledgeBaseRepository.save(kb);
        });
    }
}
