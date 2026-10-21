package interview.guide.modules.interview.service;

import interview.guide.modules.memory.messaging.AbilityMemoryDerivationTaskPublisher;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Publishes only after the report transaction has committed. */
@Service
@RequiredArgsConstructor
public class MemoryDerivationPublicationService {
  private final InterviewPersistenceService persistence;
  private final AbilityMemoryDerivationTaskPublisher publisher;

  public void publishIfPending(String sessionId) {
    if (!persistence.isMemoryDerivationPendingInternal(sessionId)) {
      return;
    }
    try {
      publisher.publish(sessionId);
    } catch (RuntimeException failure) {
      persistence.recordMemoryPublicationFailure(sessionId, failure.getMessage());
      throw failure;
    }
  }
}
