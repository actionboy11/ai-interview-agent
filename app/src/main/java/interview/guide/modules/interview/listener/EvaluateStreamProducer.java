package interview.guide.modules.interview.listener;

import interview.guide.common.async.AbstractStreamProducer;
import interview.guide.common.constant.AsyncTaskStreamConstants;
import interview.guide.common.model.AsyncTaskStatus;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.common.transaction.TransactionalExecutor;
import interview.guide.infrastructure.redis.RedisService;
import interview.guide.modules.interview.service.InterviewPersistenceService;
import interview.guide.modules.interview.messaging.InterviewEvaluationTaskPublisher;
import java.util.UUID;
import java.util.Objects;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 面试评估任务生产者
 * 负责发送评估任务到 Redis Stream
 */
@Slf4j
@Component
@ConditionalOnProperty(
    name = "app.interview-evaluation.messaging.provider",
    havingValue = "redis-stream"
)
public class EvaluateStreamProducer
    extends AbstractStreamProducer<EvaluateStreamProducer.EvaluationPayload>
    implements InterviewEvaluationTaskPublisher {

    private final InterviewPersistenceService persistenceService;
    private final TransactionalExecutor transactionalExecutor;

    public EvaluateStreamProducer(
        RedisService redisService,
        InterviewPersistenceService persistenceService,
        TransactionalExecutor transactionalExecutor
    ) {
        super(redisService);
        this.persistenceService = persistenceService;
        this.transactionalExecutor = transactionalExecutor;
    }

    /**
     * 发送评估任务到 Redis Stream
     *
     * @param sessionId 面试会话ID
     */
    public void sendEvaluateTask(String sessionId) {
        sendTask(new EvaluationPayload(sessionId, null));
    }

    @Override
    public PublishReceipt publish(String sessionId) {
        sendEvaluateTask(sessionId);
        return new PublishReceipt(UUID.randomUUID());
    }

    @Override
    public PublishReceipt publish(String sessionId, UUID messageId) {
        sendTask(new EvaluationPayload(sessionId, Objects.requireNonNull(messageId, "messageId")));
        return new PublishReceipt(messageId);
    }

    record EvaluationPayload(String sessionId, UUID messageId) {}

    @Override
    protected String taskDisplayName() {
        return "评估";
    }

    @Override
    protected String streamKey() {
        return AsyncTaskStreamConstants.INTERVIEW_EVALUATE_STREAM_KEY;
    }

    @Override
    protected Map<String, String> buildMessage(EvaluationPayload payload) {
        if (payload.messageId() != null) {
            return Map.of(
                AsyncTaskStreamConstants.FIELD_SESSION_ID, payload.sessionId(),
                AsyncTaskStreamConstants.FIELD_MESSAGE_ID, payload.messageId().toString(),
                AsyncTaskStreamConstants.FIELD_RETRY_COUNT, "0"
            );
        }
        return Map.of(
            AsyncTaskStreamConstants.FIELD_SESSION_ID, payload.sessionId(),
            AsyncTaskStreamConstants.FIELD_RETRY_COUNT, "0"
        );
    }

    @Override
    protected String payloadIdentifier(EvaluationPayload payload) {
        return "sessionId=" + payload.sessionId();
    }

    @Override
    protected void onSendFailed(EvaluationPayload payload, String error) {
        if (payload.messageId() != null) {
            throw new BusinessException(ErrorCode.INTERVIEW_EVALUATION_FAILED, "动态评价入队失败");
        }
        transactionalExecutor.runRequiresNew(
            () -> updateEvaluateStatus(payload.sessionId(), AsyncTaskStatus.FAILED, truncateError(error)));
    }

    /**
     * 更新评估状态
     */
    private void updateEvaluateStatus(String sessionId, AsyncTaskStatus status, String error) {
        persistenceService.updateEvaluateStatusInternal(sessionId, status, error);
    }
}
