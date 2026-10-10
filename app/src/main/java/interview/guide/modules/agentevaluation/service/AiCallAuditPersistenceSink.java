package interview.guide.modules.agentevaluation.service;

import interview.guide.common.ai.telemetry.AiCallObservation;
import interview.guide.common.ai.telemetry.AiCallObservationSink;
import interview.guide.modules.agentevaluation.artifact.BenchmarkArtifactStore;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "app.agent-evaluation", name = "enabled", havingValue = "true")
public class AiCallAuditPersistenceSink implements AiCallObservationSink {

  private static final String RAW_RESPONSE_ARTIFACT = "raw-response";

  private final AgentEvaluationExecutionContext executionContext;
  private final BenchmarkArtifactStore artifactStore;
  private final AiCallAuditWriter auditWriter;

  @Override
  public void record(AiCallObservation observation) {
    executionContext.current().ifPresent(stageContext -> {
      String artifactKey = storeRawResponse(stageContext, observation.rawResponse());
      auditWriter.save(stageContext, observation, artifactKey);
    });
  }

  private String storeRawResponse(StageContext context, String rawResponse) {
    if (rawResponse == null) {
      return null;
    }
    return artifactStore.put(
        context.runId(),
        context.caseId(),
        context.stageId(),
        RAW_RESPONSE_ARTIFACT,
        rawResponse.getBytes(StandardCharsets.UTF_8)
    );
  }
}
