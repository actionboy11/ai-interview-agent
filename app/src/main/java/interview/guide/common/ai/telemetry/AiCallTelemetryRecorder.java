package interview.guide.common.ai.telemetry;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import interview.guide.common.ai.StructuredOutputProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

@Component
public class AiCallTelemetryRecorder {

  private static final Logger log = LoggerFactory.getLogger(AiCallTelemetryRecorder.class);
  private static final String METRIC_INVOCATIONS = "app.ai.structured_output.invocations";
  private static final String METRIC_ATTEMPTS = "app.ai.structured_output.attempts";
  private static final String METRIC_ATTEMPT_LATENCY = "app.ai.structured_output.attempt.latency";
  private static final String METRIC_INVOCATION_LATENCY = "app.ai.structured_output.latency";
  private static final int MAX_TAG_LENGTH = 48;
  private static final Pattern NON_ALNUM_PATTERN = Pattern.compile("[^a-z0-9_.-]+");
  private static final Pattern MULTI_UNDERSCORE = Pattern.compile("_+");

  private final boolean metricsEnabled;
  private final MeterRegistry meterRegistry;
  private final List<AiCallObservationSink> sinks;

  @Autowired
  public AiCallTelemetryRecorder(
      StructuredOutputProperties properties,
      ObjectProvider<MeterRegistry> meterRegistryProvider,
      ObjectProvider<AiCallObservationSink> sinkProvider
  ) {
    this(
        properties,
        meterRegistryProvider.getIfAvailable(),
        sinkProvider.orderedStream().toList()
    );
  }

  public AiCallTelemetryRecorder(
      StructuredOutputProperties properties,
      MeterRegistry meterRegistry,
      List<AiCallObservationSink> sinks
  ) {
    this.metricsEnabled = properties.isStructuredMetricsEnabled();
    this.meterRegistry = meterRegistry;
    this.sinks = List.copyOf(sinks);
  }

  public void record(AiCallObservation observation) {
    recordMetrics(observation);
    for (AiCallObservationSink sink : sinks) {
      try {
        sink.record(observation);
      } catch (Exception e) {
        log.error("AI 调用审计记录失败: sink={}, error={}",
            sink.getClass().getName(), e.getMessage(), e);
      }
    }
  }

  private void recordMetrics(AiCallObservation observation) {
    if (!metricsEnabled || meterRegistry == null) {
      return;
    }
    String status = observation.successful() ? "success" : "failure";
    Tags tags = Tags.of(
        "context", normalizeTag(observation.context()),
        "provider", normalizeTag(observation.provider()),
        "model", normalizeTag(observation.model()),
        "status", status,
        "parseOutcome", observation.parseOutcome().name().toLowerCase(Locale.ROOT)
    );
    meterRegistry.counter(METRIC_ATTEMPTS, tags).increment();
    meterRegistry.timer(METRIC_ATTEMPT_LATENCY, tags)
        .record(observation.attemptLatencyNanos(), TimeUnit.NANOSECONDS);

    if (observation.terminal()) {
      meterRegistry.counter(METRIC_INVOCATIONS, tags).increment();
      meterRegistry.timer(METRIC_INVOCATION_LATENCY, tags)
          .record(observation.invocationLatencyNanos(), TimeUnit.NANOSECONDS);
    }
  }

  private String normalizeTag(String raw) {
    String source = raw == null || raw.isBlank() ? "unknown" : raw;
    String normalized = source.toLowerCase(Locale.ROOT).trim().replace(' ', '_');
    normalized = NON_ALNUM_PATTERN.matcher(normalized).replaceAll("_");
    normalized = MULTI_UNDERSCORE.matcher(normalized).replaceAll("_");
    normalized = normalized.replaceAll("^_+|_+$", "");
    if (normalized.isBlank()) {
      return "unknown";
    }
    return normalized.length() > MAX_TAG_LENGTH
        ? normalized.substring(0, MAX_TAG_LENGTH)
        : normalized;
  }
}
