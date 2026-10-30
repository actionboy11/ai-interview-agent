package interview.guide.modules.interview.dynamic.messaging;

import java.util.Objects;
import java.util.UUID;

/** Only the durable decision identity and the shared stream retry metadata cross the queue. */
public record FollowUpDecisionMessage(UUID decisionId, int retryCount) {
  public FollowUpDecisionMessage {
    Objects.requireNonNull(decisionId, "decisionId");
  }
}
