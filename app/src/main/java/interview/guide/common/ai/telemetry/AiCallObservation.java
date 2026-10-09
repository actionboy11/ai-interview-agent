package interview.guide.common.ai.telemetry;

import java.time.Instant;

public record AiCallObservation(
    String context,
    String provider,
    String model,
    int attempt,
    AiParseOutcome parseOutcome,
    long attemptLatencyNanos,
    long invocationLatencyNanos,
    boolean terminal,
    Integer promptTokens,
    Integer completionTokens,
    Integer totalTokens,
    String rawResponse,
    String errorType,
    String errorMessage,
    Instant observedAt
) {

  public boolean successful() {
    return parseOutcome == AiParseOutcome.DIRECT_SUCCESS
        || parseOutcome == AiParseOutcome.LOCAL_REPAIR_SUCCESS;
  }
}
