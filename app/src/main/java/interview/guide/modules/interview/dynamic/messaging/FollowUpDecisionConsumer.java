package interview.guide.modules.interview.dynamic.messaging;

import interview.guide.common.async.AbstractStreamConsumer;
import interview.guide.common.constant.AsyncTaskStreamConstants;
import interview.guide.infrastructure.redis.RedisService;
import interview.guide.modules.interview.dynamic.service.DynamicDecisionWorker;
import java.util.Map;
import java.util.UUID;
import org.redisson.api.stream.StreamMessageId;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.interview.dynamic.enabled", havingValue = "true")
public class FollowUpDecisionConsumer extends AbstractStreamConsumer<FollowUpDecisionMessage> {
  private final DynamicDecisionWorker worker;
  private final FollowUpDecisionProducer producer;

  public FollowUpDecisionConsumer(RedisService redis, DynamicDecisionWorker worker,
      FollowUpDecisionProducer producer) {
    super(redis);
    this.worker = worker;
    this.producer = producer;
  }

  @Override protected String taskDisplayName() { return "动态追问决策"; }
  @Override protected String streamKey() {
    return AsyncTaskStreamConstants.FOLLOW_UP_DECISION_STREAM_KEY;
  }
  @Override protected String groupName() {
    return AsyncTaskStreamConstants.FOLLOW_UP_DECISION_GROUP_NAME;
  }
  @Override protected String consumerPrefix() {
    return AsyncTaskStreamConstants.FOLLOW_UP_DECISION_CONSUMER_PREFIX;
  }
  @Override protected String threadName() { return "follow-up-decision-consumer"; }
  @Override protected FollowUpDecisionMessage parsePayload(StreamMessageId id,
      Map<String, String> data) {
    return new FollowUpDecisionMessage(UUID.fromString(
        data.get(AsyncTaskStreamConstants.FIELD_DECISION_ID)), parseRetryCount(data));
  }
  @Override protected String payloadIdentifier(FollowUpDecisionMessage payload) {
    return payload.decisionId().toString();
  }
  @Override protected void processBusiness(FollowUpDecisionMessage payload) {
    worker.process(payload.decisionId());
  }
  @Override protected void retryMessage(FollowUpDecisionMessage payload, int retryCount) {
    producer.publish(payload.decisionId(), retryCount);
  }

  // The worker owns transactional state changes; message lifecycle is not runtime lifecycle.
  @Override protected void markProcessing(FollowUpDecisionMessage payload) {}
  @Override protected void markCompleted(FollowUpDecisionMessage payload) {}
  // Exhausting stream retries must leave the original DB deadline recoverable.
  @Override protected void markFailed(FollowUpDecisionMessage payload, String error) {}
}
