package interview.guide.modules.interview.dynamic.service;

import interview.guide.modules.interview.dynamic.model.DynamicPhase;
import interview.guide.modules.interview.dynamic.model.FollowUpDecisionEntity.DeliveryStatus;
import interview.guide.modules.interview.dynamic.repository.DynamicEvaluationOutboxRepository;
import interview.guide.modules.interview.dynamic.repository.DynamicEvaluationOutboxRepository.OutboxReference;
import interview.guide.modules.interview.dynamic.repository.DynamicInterviewRuntimeRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class DynamicEvaluationOutboxStoreService {
  private final DynamicInterviewRuntimeRepository runtimes;
  private final DynamicEvaluationOutboxRepository outboxes;
  private final DynamicInterviewProperties properties;

  public record Claim(UUID outboxId, String sessionId, UUID messageId, UUID token) {}

  @Transactional(readOnly = true)
  public List<OutboxReference> candidates(Instant now) {
    return outboxes.findDeliveryCandidates(now, PageRequest.of(0, properties.getDeliveryBatchSize()));
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public Optional<Claim> claim(OutboxReference reference, Instant now) {
    var runtime = runtimes.findBySessionIdForUpdate(reference.getSessionId()).orElse(null);
    if (runtime == null) { return Optional.empty(); }
    var outbox = outboxes.findByIdForUpdate(reference.getId()).orElse(null);
    if (outbox == null || outbox.getDeliveryStatus() != DeliveryStatus.PENDING
        || outbox.getNextDeliveryAt().isAfter(now)
        || (outbox.getLeaseUntil() != null && now.isBefore(outbox.getLeaseUntil()))) {
      return Optional.empty();
    }
    if (!DynamicInterviewQueryService.ownerActive(runtime)
        || runtime.getPhase() != DynamicPhase.COMPLETED
        || runtime.getFrozenVersion() == null
        || runtime.getFrozenVersion() != outbox.getFrozenVersion()
        || !runtime.getSession().getId().equals(outbox.getSession().getId())
        || !runtime.getOwner().getId().equals(outbox.getOwner().getId())
        || !outbox.getMessageId().toString().equals(runtime.getSession().getEvaluationMessageId())) {
      // Invalid scope is never published; defer so it cannot starve later candidates.
      outbox.setNextDeliveryAt(now.plus(properties.getDeliveryRetryDelay()));
      return Optional.empty();
    }
    UUID token = UUID.randomUUID();
    outbox.setAttemptToken(token);
    outbox.setLeaseUntil(now.plus(properties.getDeliveryLease()));
    outbox.setDeliveryAttempts(outbox.getDeliveryAttempts() + 1);
    return Optional.of(new Claim(outbox.getId(), reference.getSessionId(), outbox.getMessageId(), token));
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void finish(Claim claim, boolean published, Instant now) {
    var runtime = runtimes.findBySessionIdForUpdate(claim.sessionId()).orElse(null);
    if (runtime == null) { return; }
    var outbox = outboxes.findByIdForUpdate(claim.outboxId()).orElse(null);
    if (outbox == null || outbox.getDeliveryStatus() != DeliveryStatus.PENDING
        || !claim.token().equals(outbox.getAttemptToken())) { return; }
    outbox.setAttemptToken(null);
    outbox.setLeaseUntil(null);
    if (published) {
      outbox.setDeliveryStatus(DeliveryStatus.PUBLISHED);
      outbox.setPublishedAt(now);
    } else {
      outbox.setNextDeliveryAt(now.plus(properties.getDeliveryRetryDelay()));
    }
  }
}
