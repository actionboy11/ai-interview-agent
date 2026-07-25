package interview.guide.modules.interview.messaging;

import java.util.UUID;

public interface InterviewEvaluationTaskPublisher {

  PublishReceipt publish(String sessionId);

  record PublishReceipt(UUID messageId) {
  }
}
