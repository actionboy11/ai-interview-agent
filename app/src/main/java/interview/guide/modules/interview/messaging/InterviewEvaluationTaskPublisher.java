package interview.guide.modules.interview.messaging;

import java.util.UUID;

public interface InterviewEvaluationTaskPublisher {

  PublishReceipt publish(String sessionId);

  default PublishReceipt publish(String sessionId, UUID messageId) {
    throw new UnsupportedOperationException("Stable message identity is not supported");
  }

  record PublishReceipt(UUID messageId) {
  }
}
