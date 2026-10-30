package interview.guide.modules.interview.dynamic.service;

import interview.guide.modules.interview.dynamic.model.DynamicGroupEndReason;
import interview.guide.modules.interview.dynamic.model.FollowUpContext;
import interview.guide.modules.interview.dynamic.model.FollowUpDecisionEntity.Action;
import interview.guide.modules.interview.dynamic.model.FollowUpDecisionEntity.Reason;
import interview.guide.modules.interview.dynamic.model.FollowUpModelDecision;
import interview.guide.modules.interview.dynamic.service.DynamicInterviewTransitionService.DecisionApplication;
import interview.guide.modules.interview.dynamic.service.DynamicInterviewTransitionService.DecisionContextSnapshot;
import java.time.Clock;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
@RequiredArgsConstructor
@Slf4j
public class DynamicDecisionWorker {
  private final DynamicDecisionStoreService store;
  private final FollowUpDecisionService model;
  private final DynamicInterviewTransitionService transitions;
  private final ObjectMapper mapper;
  private final Clock clock;

  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  public void process(UUID decisionId) {
    var claim = store.claimWork(decisionId, clock.instant()).orElse(null);
    if (claim == null) { return; }
    if (claim.attemptToken() == null || !clock.instant().isBefore(claim.deadline())) {
      transitions.expireDecision(claim.sessionId(), decisionId);
      return;
    }
    DecisionApplication application;
    try {
      var snapshot = mapper.readValue(claim.contextJson(), DecisionContextSnapshot.class);
      var proposal = model.decide(claim.provider(), new FollowUpContext(snapshot), claim.deadline());
      application = application(proposal);
    } catch (Exception exception) {
      log.warn("动态决策模型失败: decisionId={}", decisionId, exception);
      application = new DecisionApplication(null, null, null, null, null,
          DynamicGroupEndReason.SYSTEM_UNAVAILABLE, "MODEL_UNAVAILABLE");
    }
    if (!clock.instant().isBefore(claim.deadline())) {
      transitions.expireDecision(claim.sessionId(), decisionId);
    } else {
      transitions.applyDecision(claim.sessionId(), decisionId, claim.attemptToken(), application);
    }
  }

  private DecisionApplication application(FollowUpModelDecision proposal) {
    DynamicGroupEndReason end = proposal.action() == FollowUpModelDecision.Action.FOLLOW_UP
        ? null : switch (proposal.reasonCode()) {
          case SUFFICIENT -> DynamicGroupEndReason.SUFFICIENT;
          case NO_USEFUL_FOLLOW_UP -> DynamicGroupEndReason.NO_USEFUL_FOLLOW_UP;
          case USER_DONT_KNOW -> DynamicGroupEndReason.DONT_KNOW;
          case IMPORTANT_GAP -> throw new IllegalArgumentException("决策理由与动作不一致");
        };
    return new DecisionApplication(Action.valueOf(proposal.action().name()),
        Reason.valueOf(proposal.reasonCode().name()), proposal.reasonSummary(),
        mapper.writeValueAsString(proposal), proposal.followUpQuestion(), end, null);
  }
}
