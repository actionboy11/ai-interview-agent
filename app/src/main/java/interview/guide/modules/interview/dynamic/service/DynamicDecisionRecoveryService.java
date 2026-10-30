package interview.guide.modules.interview.dynamic.service;

import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class DynamicDecisionRecoveryService {
  private final DynamicDecisionStoreService store;
  private final DynamicInterviewTransitionService transitions;

  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  public void recover(Instant now) {
    for (var candidate : store.recoveryCandidates(now)) {
      try {
        if (store.recover(candidate, now)) {
          transitions.expireDecision(candidate.getSessionId(), candidate.getId());
        }
      } catch (Exception exception) {
        log.warn("动态决策恢复失败，等待下轮扫描: decisionId={}", candidate.getId(), exception);
      }
    }
  }
}
