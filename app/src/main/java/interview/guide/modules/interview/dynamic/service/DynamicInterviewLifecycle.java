package interview.guide.modules.interview.dynamic.service;

import interview.guide.modules.interview.dynamic.repository.DynamicInterviewRuntimeRepository;
import interview.guide.modules.interview.dynamic.repository.FollowUpDecisionRepository;
import interview.guide.modules.interview.dynamic.repository.DynamicEvaluationOutboxRepository;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Called inside the actual deletion transaction, before locking/deleting sessions or answers. */
@Service
@RequiredArgsConstructor
public class DynamicInterviewLifecycle {
  private final DynamicInterviewRuntimeRepository runtimes;
  private final FollowUpDecisionRepository decisions;
  private final DynamicEvaluationOutboxRepository outboxes;

  @Transactional(propagation = Propagation.MANDATORY)
  public void prepareDeletion(UUID ownerId, List<Long> sessionIds) {
    if (sessionIds.isEmpty()) { return; }
    var locked = runtimes.lockForDeletion(ownerId, sessionIds);
    if (locked.isEmpty()) { return; }
    var dynamicIds = locked.stream().map(runtime -> runtime.getSession().getId()).toList();
    var tasks = decisions.lockForDeletion(dynamicIds);
    var evaluations = outboxes.lockForDeletion(dynamicIds);
    decisions.deleteAll(tasks);
    outboxes.deleteAll(evaluations);
    decisions.flush();
    outboxes.flush();
    runtimes.deleteAll(locked);
    runtimes.flush();
  }
}
