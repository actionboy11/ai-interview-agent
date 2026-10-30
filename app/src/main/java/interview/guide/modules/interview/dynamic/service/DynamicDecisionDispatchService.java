package interview.guide.modules.interview.dynamic.service;

import interview.guide.modules.interview.dynamic.messaging.FollowUpDecisionProducer;
import java.time.Clock;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class DynamicDecisionDispatchService {
  private final DynamicDecisionStoreService store;
  private final FollowUpDecisionProducer producer;
  private final Clock clock;

  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  public void dispatch(Instant now) {
    for (var candidate : store.deliveryCandidates(now)) {
      try {
        var claim = store.claimDelivery(candidate, clock.instant()).orElse(null);
        if (claim == null) { continue; }
        boolean published = false;
        try {
          producer.publish(claim.decisionId());
          published = true;
        } catch (Exception exception) {
          log.warn("动态决策入队失败，等待补投: decisionId={}", claim.decisionId(), exception);
        }
        store.finishDelivery(claim, published, clock.instant());
      } catch (Exception exception) {
        log.warn("动态决策投递事务失败，等待恢复: decisionId={}", candidate.getId(), exception);
      }
    }
  }
}
