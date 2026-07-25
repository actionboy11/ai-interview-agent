package interview.guide.modules.knowledgebase.messaging;

import java.util.UUID;

public interface KnowledgeVectorizationTaskPublisher {

  PublishReceipt publish(Long knowledgeBaseId);

  record PublishReceipt(UUID messageId) {
  }
}
