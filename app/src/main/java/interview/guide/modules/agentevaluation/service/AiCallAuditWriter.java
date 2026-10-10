package interview.guide.modules.agentevaluation.service;

import interview.guide.common.ai.telemetry.AiCallObservation;
import interview.guide.modules.agentevaluation.model.AiCallAuditEntity;
import interview.guide.modules.agentevaluation.repository.AiCallAuditRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AiCallAuditWriter {

  private final AiCallAuditRepository auditRepository;

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void save(
      StageContext stageContext,
      AiCallObservation observation,
      String artifactKey
  ) {
    auditRepository.save(AiCallAuditEntity.create(
        stageContext.runId(),
        stageContext.caseId(),
        stageContext.stageId(),
        observation.context(),
        observation.provider(),
        observation.model(),
        observation.attempt(),
        observation.parseOutcome(),
        observation.attemptLatencyNanos(),
        observation.promptTokens(),
        observation.completionTokens(),
        observation.totalTokens(),
        artifactKey,
        observation.errorType(),
        observation.errorMessage(),
        observation.observedAt()
    ));
  }
}
