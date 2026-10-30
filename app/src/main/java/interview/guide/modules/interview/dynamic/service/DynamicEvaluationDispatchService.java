package interview.guide.modules.interview.dynamic.service;

import interview.guide.modules.interview.messaging.InterviewEvaluationTaskPublisher;
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
public class DynamicEvaluationDispatchService {
  private final DynamicEvaluationOutboxStoreService store;
  private final InterviewEvaluationTaskPublisher publisher;
  private final Clock clock;

  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  public void dispatch(Instant now) {
    for (var candidate : store.candidates(now)) {
      try {
        var claim = store.claim(candidate, clock.instant()).orElse(null);
        if (claim == null) { continue; }
        boolean published = false;
        try {
          var receipt = publisher.publish(claim.sessionId(), claim.messageId());
          published = receipt != null && claim.messageId().equals(receipt.messageId());
          if (!published) {
            log.warn("动态评价发布回执标识不匹配: outboxId={}", claim.outboxId());
          }
        } catch (Exception exception) {
          log.warn("动态评价发布失败，等待补投: outboxId={}", claim.outboxId(), exception);
        }
        store.finish(claim, published, clock.instant());
      } catch (Exception exception) {
        log.warn("动态评价投递事务失败，等待恢复: outboxId={}", candidate.getId(), exception);
      }
    }
  }
}
