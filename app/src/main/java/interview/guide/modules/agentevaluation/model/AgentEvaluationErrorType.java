package interview.guide.modules.agentevaluation.model;

public enum AgentEvaluationErrorType {
  INVALID_CREDENTIAL,
  RATE_LIMITED,
  TRANSIENT_PROVIDER_ERROR,
  STRUCTURED_OUTPUT_ERROR,
  INPUT_TOO_LARGE,
  CONTENT_FILTERED,
  INFRASTRUCTURE_ERROR,
  UNKNOWN
}
