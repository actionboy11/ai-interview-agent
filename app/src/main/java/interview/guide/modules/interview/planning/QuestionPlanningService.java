package interview.guide.modules.interview.planning;

import interview.guide.modules.auth.security.CurrentUserProvider;
import interview.guide.modules.interview.planning.model.QuestionPlan;
import interview.guide.modules.interview.planning.model.QuestionPlanningCommand;
import interview.guide.modules.memory.service.AbilityProfileQueryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class QuestionPlanningService {
  private final CurrentUserProvider currentUser;
  private final AbilityProfileQueryService memory;
  private final AdaptiveAllocationPolicy policy;
  private final QuestionPlanValidator validator;

  public QuestionPlan plan(QuestionPlanningCommand command) {
    // Authentication is outside the fallback boundary: a missing identity is not a memory outage.
    var ownerId = currentUser.requireUserId();
    try {
      var profiles = memory.profiles(ownerId, command.skillId());
      var sessionCounts = memory.confirmedSessionCounts(ownerId, command.skillId());
      var plan = policy.plan(command, profiles, sessionCounts);
      validator.validate(command, plan);
      return plan;
    } catch (RuntimeException failure) {
      log.warn("能力记忆出题规划失败，回退固定分配: skillId={}", command.skillId(), failure);
      var fallback = policy.fallback(command, "MEMORY_OR_PLANNING_FAILURE");
      validator.validate(command, fallback);
      return fallback;
    }
  }
}
