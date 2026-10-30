package interview.guide.modules.interview.dynamic.service;

import interview.guide.modules.interview.dynamic.model.DynamicInterviewRuntimeEntity;
import interview.guide.modules.interview.dynamic.model.DynamicPhase;
import interview.guide.modules.interview.dynamic.model.FollowUpDecisionEntity;
import interview.guide.modules.interview.dynamic.model.FollowUpDecisionEntity.DeliveryStatus;
import interview.guide.modules.interview.dynamic.model.FollowUpDecisionEntity.Status;
import interview.guide.modules.interview.dynamic.repository.DynamicInterviewRuntimeRepository;
import interview.guide.modules.interview.dynamic.repository.FollowUpDecisionRepository;
import interview.guide.modules.interview.dynamic.repository.FollowUpDecisionRepository.DecisionReference;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Short database-only transactions. The runtime is always locked before the decision. */
@Service
@RequiredArgsConstructor
public class DynamicDecisionStoreService {
  private final DynamicInterviewRuntimeRepository runtimes;
  private final FollowUpDecisionRepository decisions;
  private final DynamicInterviewProperties properties;

  /** A null token signals an expired decision; the caller delegates expiry to transitions. */
  public record WorkClaim(UUID decisionId, String sessionId, String provider, String contextJson,
      Instant deadline, UUID attemptToken) {}
  public record DeliveryClaim(UUID decisionId, String sessionId, int generation) {}

  @Transactional(readOnly = true)
  public List<DecisionReference> deliveryCandidates(Instant now) {
    return decisions.findDeliveryCandidates(now, PageRequest.of(0, properties.getDeliveryBatchSize()));
  }

  @Transactional(readOnly = true)
  public List<DecisionReference> recoveryCandidates(Instant now) {
    return decisions.findRecoveryCandidates(now, PageRequest.of(0, properties.getDeliveryBatchSize()));
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public Optional<WorkClaim> claimWork(UUID id, Instant now) {
    var reference = decisions.findReferenceById(id).orElse(null);
    if (reference == null) { return Optional.empty(); }
    var locked = lock(reference.getSessionId(), id);
    if (!current(locked)) { return Optional.empty(); }
    var decision = locked.decision();
    if (!now.isBefore(decision.getDeadline())) {
      return Optional.of(work(locked, null));
    }
    if (decision.getStatus() == Status.PROCESSING && decision.getLeaseUntil() != null
        && now.isBefore(decision.getLeaseUntil())) { return Optional.empty(); }
    UUID token = UUID.randomUUID();
    decision.setStatus(Status.PROCESSING);
    decision.setAttemptToken(token);
    decision.setLeaseUntil(earlier(now.plus(properties.getWorkLease()), decision.getDeadline()));
    return Optional.of(work(locked, token));
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public Optional<DeliveryClaim> claimDelivery(DecisionReference reference, Instant now) {
    var locked = lock(reference.getSessionId(), reference.getId());
    if (!current(locked)) { return Optional.empty(); }
    var decision = locked.decision();
    if (decision.getStatus() != Status.PENDING || decision.getDeliveryStatus() != DeliveryStatus.PENDING
        || decision.getNextDeliveryAt().isAfter(now) || !now.isBefore(decision.getDeadline())) {
      return Optional.empty();
    }
    decision.setDeliveryAttempts(decision.getDeliveryAttempts() + 1);
    decision.setNextDeliveryAt(now.plus(properties.getDeliveryLease()));
    return Optional.of(new DeliveryClaim(decision.getId(), reference.getSessionId(),
        decision.getDeliveryAttempts()));
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void finishDelivery(DeliveryClaim claim, boolean published, Instant now) {
    var locked = lock(claim.sessionId(), claim.decisionId());
    if (!current(locked)) { return; }
    var decision = locked.decision();
    if (decision.getDeliveryAttempts() != claim.generation()
        || decision.getDeliveryStatus() != DeliveryStatus.PENDING) { return; }
    if (published) {
      decision.setDeliveryStatus(DeliveryStatus.PUBLISHED);
      decision.setPublishedAt(now);
    } else {
      decision.setNextDeliveryAt(now.plus(properties.getDeliveryRetryDelay()));
    }
  }

  /** Returns an expired reference for the shared transition service; never calls the model. */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public boolean recover(DecisionReference reference, Instant now) {
    var locked = lock(reference.getSessionId(), reference.getId());
    if (!current(locked)) { return false; }
    var decision = locked.decision();
    if (!now.isBefore(decision.getDeadline())) { return true; }
    if (decision.getStatus() == Status.PROCESSING && (decision.getLeaseUntil() == null
        || !now.isBefore(decision.getLeaseUntil()))) {
      decision.setStatus(Status.PENDING);
      decision.setAttemptToken(null);
      decision.setLeaseUntil(null);
      decision.setDeliveryStatus(DeliveryStatus.PENDING);
      decision.setNextDeliveryAt(now);
      // Fence a late send completion from the preceding delivery attempt as well.
      decision.setDeliveryAttempts(decision.getDeliveryAttempts() + 1);
    }
    return false;
  }

  private Locked lock(String sessionId, UUID id) {
    var runtime = runtimes.findBySessionIdForUpdate(sessionId).orElse(null);
    if (runtime == null) { return null; }
    var decision = decisions.findByIdForUpdate(id).orElse(null);
    return decision == null ? null : new Locked(runtime, decision);
  }

  private boolean current(Locked locked) {
    if (locked == null) { return false; }
    var runtime = locked.runtime();
    var decision = locked.decision();
    if (decision.getStatus() != Status.PENDING && decision.getStatus() != Status.PROCESSING) {
      return false;
    }
    boolean valid = DynamicInterviewQueryService.ownerActive(runtime)
        && runtime.getPhase() == DynamicPhase.DECISION_PENDING
        && decision.getId().equals(runtime.getPendingDecisionId())
        && decision.getSession().getId().equals(runtime.getSession().getId())
        && decision.getOwner().getId().equals(runtime.getOwner().getId())
        && decision.getExpectedRuntimeVersion() == runtime.getVersion();
    if (!valid) {
      decision.setStatus(Status.CANCELLED);
      decision.setAttemptToken(null);
      decision.setLeaseUntil(null);
    }
    return valid;
  }

  private WorkClaim work(Locked locked, UUID token) {
    var decision = locked.decision();
    var session = locked.runtime().getSession();
    return new WorkClaim(decision.getId(), session.getSessionId(), session.getLlmProvider(),
        decision.getContextJson(), decision.getDeadline(), token);
  }

  private Instant earlier(Instant first, Instant second) {
    return first.isBefore(second) ? first : second;
  }

  private record Locked(DynamicInterviewRuntimeEntity runtime, FollowUpDecisionEntity decision) {}
}
