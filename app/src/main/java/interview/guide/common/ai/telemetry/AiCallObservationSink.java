package interview.guide.common.ai.telemetry;

@FunctionalInterface
public interface AiCallObservationSink {

  void record(AiCallObservation observation);
}
