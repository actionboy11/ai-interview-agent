package interview.guide.modules.interview.dynamic.service;

import interview.guide.modules.interview.service.InterviewPersistenceService;
import interview.guide.modules.interview.service.InterviewPersistenceService.CompletionResult;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Broker-neutral orchestration; model calls must remain outside persistence transactions. */
@Service
@RequiredArgsConstructor
public class DynamicEvaluationOrchestrationService {
  private final InterviewPersistenceService persistence;
  private final DynamicInterviewEvaluationService evaluation;

  public CompletionResult evaluate(String sessionId, UUID originalMessageId) {
    var work = persistence.loadDynamicEvaluation(sessionId, originalMessageId).orElse(null);
    if (work == null) { return CompletionResult.SESSION_MISSING; }
    if (work.completedReport() != null) { return CompletionResult.ALREADY_COMPLETED; }
    var report = evaluation.evaluate(work.provider(), work.snapshot());
    return persistence.completeDynamicReport(sessionId, originalMessageId,
        work.snapshot().frozenVersion(), report);
  }
}
