package interview.guide.modules.interview.dynamic.messaging;

import interview.guide.common.async.AbstractStreamProducer;
import interview.guide.common.constant.AsyncTaskStreamConstants;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.infrastructure.redis.RedisService;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class FollowUpDecisionProducer extends AbstractStreamProducer<FollowUpDecisionMessage> {
  public FollowUpDecisionProducer(RedisService redis) { super(redis); }

  public void publish(UUID decisionId) { publish(decisionId, 0); }

  public void publish(UUID decisionId, int retryCount) {
    sendTask(new FollowUpDecisionMessage(decisionId, retryCount));
  }

  @Override protected String taskDisplayName() { return "动态追问决策"; }
  @Override protected String streamKey() {
    return AsyncTaskStreamConstants.FOLLOW_UP_DECISION_STREAM_KEY;
  }
  @Override protected String payloadIdentifier(FollowUpDecisionMessage payload) {
    return payload.decisionId().toString();
  }
  @Override protected Map<String, String> buildMessage(FollowUpDecisionMessage payload) {
    return Map.of(AsyncTaskStreamConstants.FIELD_DECISION_ID, payload.decisionId().toString(),
        AsyncTaskStreamConstants.FIELD_RETRY_COUNT, Integer.toString(payload.retryCount()));
  }
  @Override protected void onSendFailed(FollowUpDecisionMessage payload, String error) {
    // The shared producer catches sends: this hook must propagate failure to the durable dispatcher.
    throw new BusinessException(ErrorCode.INTERNAL_ERROR, "动态决策入队失败");
  }
}
