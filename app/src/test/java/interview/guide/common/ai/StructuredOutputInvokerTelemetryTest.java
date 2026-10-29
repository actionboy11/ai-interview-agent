package interview.guide.common.ai;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import interview.guide.common.ai.telemetry.AiCallObservation;
import interview.guide.common.ai.telemetry.AiCallObservationSink;
import interview.guide.common.ai.telemetry.AiCallTelemetryRecorder;
import interview.guide.common.ai.telemetry.AiParseOutcome;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ResponseEntity;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.converter.BeanOutputConverter;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("结构化输出调用观测")
class StructuredOutputInvokerTelemetryTest {

  private static final BeanOutputConverter<Payload> CONVERTER =
      new BeanOutputConverter<>(Payload.class);

  @Nested
  @DisplayName("调用成功")
  class SuccessfulInvocation {

    @Test
    @DisplayName("直接解析成功时记录同一次响应中的真实 Token")
    void shouldRecordExactUsageForDirectSuccess() {
      var fixture = fixture(false, List.of());
      when(fixture.callSpec().chatResponse())
          .thenReturn(response("{\"value\":\"ok\"}", 120, 30, 150));

      Payload result = invoke(fixture.invoker(), fixture.client());

      assertThat(result.value()).isEqualTo("ok");
      assertThat(fixture.observations()).singleElement().satisfies(observation -> {
        assertThat(observation.attempt()).isEqualTo(1);
        assertThat(observation.parseOutcome()).isEqualTo(AiParseOutcome.DIRECT_SUCCESS);
        assertThat(observation.promptTokens()).isEqualTo(120);
        assertThat(observation.completionTokens()).isEqualTo(30);
        assertThat(observation.totalTokens()).isEqualTo(150);
        assertThat(observation.rawResponse()).isEqualTo("{\"value\":\"ok\"}");
      });
    }

    @Test
    @DisplayName("Provider 未返回 Usage 时 Token 保持为空而不是估算为零")
    void shouldKeepTokensNullWhenProviderOmitsUsage() {
      var fixture = fixture(false, List.of());
      when(fixture.callSpec().chatResponse()).thenReturn(responseWithoutUsage("{\"value\":\"ok\"}"));

      invoke(fixture.invoker(), fixture.client());

      assertThat(fixture.observations()).singleElement().satisfies(observation -> {
        assertThat(observation.promptTokens()).isNull();
        assertThat(observation.completionTokens()).isNull();
        assertThat(observation.totalTokens()).isNull();
      });
    }

    @Test
    @DisplayName("本地修复未转义引号后成功时不能计为首次直接解析成功")
    void shouldDistinguishLocalRepairFromDirectSuccess() {
      var fixture = fixture(false, List.of());
      String malformed = "{\"value\":\"He said \"hello\"\"}";
      when(fixture.callSpec().chatResponse()).thenReturn(response(malformed, 80, 20, 100));

      Payload result = invoke(fixture.invoker(), fixture.client());

      assertThat(result.value()).isEqualTo("He said \"hello\"");
      assertThat(fixture.observations()).singleElement().satisfies(observation ->
          assertThat(observation.parseOutcome()).isEqualTo(AiParseOutcome.LOCAL_REPAIR_SUCCESS));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    @Test
    @DisplayName("启用 Schema 校验时从 ResponseEntity 同时取得实体、原始响应和 Usage")
    void shouldRecordValidatedResponseEntityUsage() {
      var fixture = fixture(true, List.of());
      Payload payload = new Payload("validated");
      ChatResponse response = response("{\"value\":\"validated\"}", 40, 10, 50);
      when(fixture.callSpec().responseEntity(eq(CONVERTER), any(Consumer.class)))
          .thenReturn(new ResponseEntity<>(response, payload));

      Payload result = invoke(fixture.invoker(), fixture.client());

      assertThat(result).isEqualTo(payload);
      assertThat(fixture.observations()).singleElement().satisfies(observation -> {
        assertThat(observation.parseOutcome()).isEqualTo(AiParseOutcome.DIRECT_SUCCESS);
        assertThat(observation.totalTokens()).isEqualTo(50);
        assertThat(observation.rawResponse()).isEqualTo("{\"value\":\"validated\"}");
      });
    }
  }

  @Nested
  @DisplayName("失败与重试")
  class RetryAndFailure {

    @Test
    @DisplayName("语义校验复用同一尝试预算并保留失败响应及真实 Token")
    void semanticValidationRetainsResponseAndUsage() {
      var fixture = fixture(false, List.of());
      when(fixture.callSpec().chatResponse())
          .thenReturn(response("{\"value\":\"invalid\"}", 10, 4, 14))
          .thenReturn(response("{\"value\":\"valid\"}", 20, 5, 25));

      Payload result = fixture.invoker().invoke(fixture.client(), "system", "user", CONVERTER,
          ErrorCode.AI_SERVICE_ERROR, "结构化失败: ", "semantic", "test", "test-model",
          value -> {
            if (!"valid".equals(value.value())) {
              throw new IllegalArgumentException("value must be valid");
            }
          }, LoggerFactory.getLogger(StructuredOutputInvokerTelemetryTest.class));

      assertThat(result.value()).isEqualTo("valid");
      assertThat(fixture.observations()).hasSize(2);
      var rejected = fixture.observations().getFirst();
      assertThat(rejected.parseOutcome()).isEqualTo(AiParseOutcome.PARSE_FAILURE);
      assertThat(rejected.rawResponse()).isEqualTo("{\"value\":\"invalid\"}");
      assertThat(rejected.promptTokens()).isEqualTo(10);
      assertThat(rejected.completionTokens()).isEqualTo(4);
      assertThat(rejected.totalTokens()).isEqualTo(14);
      assertThat(fixture.observations().getLast().parseOutcome())
          .isEqualTo(AiParseOutcome.DIRECT_SUCCESS);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    @Test
    @DisplayName("Schema 校验路径也必须通过语义校验且耗尽后不记录成功")
    void schemaValidationAlsoRunsSemanticValidation() {
      var fixture = fixture(true, List.of());
      var raw = response("{\"value\":\"invalid\"}", 12, 3, 15);
      when(fixture.callSpec().responseEntity(eq(CONVERTER), any(Consumer.class)))
          .thenReturn(new ResponseEntity<>(raw, new Payload("invalid")));

      assertThatThrownBy(() -> fixture.invoker().invoke(fixture.client(), "system", "user",
          CONVERTER, ErrorCode.AI_SERVICE_ERROR, "结构化失败: ", "semantic", value -> {
            throw new IllegalArgumentException("invalid payload");
          }, LoggerFactory.getLogger(StructuredOutputInvokerTelemetryTest.class)))
          .isInstanceOf(BusinessException.class);
      assertThat(fixture.observations()).hasSize(2).allSatisfy(observation -> {
        assertThat(observation.parseOutcome()).isEqualTo(AiParseOutcome.PARSE_FAILURE);
        assertThat(observation.rawResponse()).isEqualTo("{\"value\":\"invalid\"}");
        assertThat(observation.totalTokens()).isEqualTo(15);
      });
    }

    @Test
    @DisplayName("第一次调用失败后第二次成功时分别记录两个尝试")
    void shouldRecordFailedAttemptThenRecoveredAttempt() {
      var fixture = fixture(false, List.of());
      when(fixture.callSpec().chatResponse())
          .thenThrow(new IllegalStateException("temporary outage"))
          .thenReturn(response("{\"value\":\"recovered\"}", 90, 20, 110));

      Payload result = invoke(fixture.invoker(), fixture.client());

      assertThat(result.value()).isEqualTo("recovered");
      assertThat(fixture.observations()).extracting(
              AiCallObservation::attempt,
              AiCallObservation::parseOutcome)
          .containsExactly(
              org.assertj.core.groups.Tuple.tuple(1, AiParseOutcome.CALL_FAILURE),
              org.assertj.core.groups.Tuple.tuple(2, AiParseOutcome.DIRECT_SUCCESS));
    }

    @Test
    @DisplayName("重试耗尽时保留原有业务异常并记录每次失败")
    void shouldRecordEveryFailureWhenRetriesExhausted() {
      var fixture = fixture(false, List.of());
      when(fixture.callSpec().chatResponse()).thenThrow(new IllegalStateException("offline"));

      assertThatThrownBy(() -> invoke(fixture.invoker(), fixture.client()))
          .isInstanceOf(BusinessException.class)
          .hasMessageContaining("结构化失败");

      assertThat(fixture.observations()).hasSize(2);
      assertThat(fixture.observations()).extracting(AiCallObservation::parseOutcome)
          .containsOnly(AiParseOutcome.CALL_FAILURE);
    }

    @Test
    @DisplayName("审计 Sink 故障不能影响已经成功的业务结果")
    void shouldIsolateFailingAuditSink() {
      AiCallObservationSink throwingSink = observation -> {
        throw new IllegalStateException("audit unavailable");
      };
      var fixture = fixture(false, List.of(throwingSink));
      when(fixture.callSpec().chatResponse())
          .thenReturn(response("{\"value\":\"ok\"}", 12, 3, 15));

      Payload result = invoke(fixture.invoker(), fixture.client());

      assertThat(result.value()).isEqualTo("ok");
      assertThat(fixture.observations()).hasSize(1);
    }
  }

  private Payload invoke(StructuredOutputInvoker invoker, ChatClient client) {
    return invoker.invoke(
        client,
        "system",
        "user",
        CONVERTER,
        ErrorCode.AI_SERVICE_ERROR,
        "结构化失败: ",
        "benchmark-question-generation",
        "dashscope",
        "qwen3.5-flash",
        LoggerFactory.getLogger(StructuredOutputInvokerTelemetryTest.class)
    );
  }

  private Fixture fixture(boolean schemaValidation, List<AiCallObservationSink> extraSinks) {
    var properties = new StructuredOutputProperties();
    properties.setStructuredMaxAttempts(2);
    properties.setStructuredSchemaValidationEnabled(schemaValidation);
    properties.setStructuredMetricsEnabled(true);

    List<AiCallObservation> observations = new ArrayList<>();
    List<AiCallObservationSink> sinks = new ArrayList<>(extraSinks);
    sinks.add(observations::add);
    var recorder = new AiCallTelemetryRecorder(
        properties,
        new SimpleMeterRegistry(),
        sinks
    );

    ChatClient client = mock(ChatClient.class);
    ChatClient.ChatClientRequestSpec requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
    ChatClient.CallResponseSpec callSpec = mock(ChatClient.CallResponseSpec.class);
    when(client.prompt()).thenReturn(requestSpec);
    when(requestSpec.system(anyString())).thenReturn(requestSpec);
    when(requestSpec.user(anyString())).thenReturn(requestSpec);
    when(requestSpec.call()).thenReturn(callSpec);

    return new Fixture(
        new StructuredOutputInvoker(properties, recorder),
        client,
        callSpec,
        observations
    );
  }

  private ChatResponse response(String content, int prompt, int completion, int total) {
    var metadata = ChatResponseMetadata.builder()
        .model("qwen3.5-flash")
        .usage(new DefaultUsage(prompt, completion, total))
        .build();
    return new ChatResponse(List.of(new Generation(new AssistantMessage(content))), metadata);
  }

  private ChatResponse responseWithoutUsage(String content) {
    var metadata = ChatResponseMetadata.builder().model("qwen3.5-flash").build();
    return new ChatResponse(List.of(new Generation(new AssistantMessage(content))), metadata);
  }

  private record Payload(String value) {
  }

  private record Fixture(
      StructuredOutputInvoker invoker,
      ChatClient client,
      ChatClient.CallResponseSpec callSpec,
      List<AiCallObservation> observations
  ) {
  }
}
