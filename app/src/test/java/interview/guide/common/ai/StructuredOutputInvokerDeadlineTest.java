package interview.guide.common.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import interview.guide.common.ai.telemetry.AiCallObservation;
import interview.guide.common.ai.telemetry.AiCallTelemetryRecorder;
import interview.guide.common.ai.telemetry.AiParseOutcome;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.converter.BeanOutputConverter;

@DisplayName("结构化调用的绝对deadline")
class StructuredOutputInvokerDeadlineTest {
  @Test
  @DisplayName("即使全局开启schema校验，新deadline路径也仅使用统一两次尝试")
  void schemaEnabledCannotIntroduceNestedRetries() {
    var calls = new AtomicInteger();
    ChatModel invalid = prompt -> { calls.incrementAndGet(); return response("invalid-json"); };
    try (var fixture = fixture(Clock.systemUTC(), true)) {
      assertThatThrownBy(() -> invoke(fixture.invoker, invalid, Instant.now().plusSeconds(3)))
          .isInstanceOf(BusinessException.class);
      assertThat(calls.get()).isEqualTo(2);
      assertThat(fixture.observations).hasSize(2).allSatisfy(observation ->
          assertThat(observation.parseOutcome()).isEqualTo(AiParseOutcome.PARSE_FAILURE));
    }
  }

  @Test
  @DisplayName("调用方取消后，模型吞掉中断也不能重试或记录成功")
  void cancellationSurvivesProviderClearingInterrupt() throws Exception {
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var observed = new CountDownLatch(1);
    var calls = new AtomicInteger();
    var props = new StructuredOutputProperties();
    props.setStructuredSchemaValidationEnabled(false);
    List<AiCallObservation> observations = new CopyOnWriteArrayList<>();
    try (var executor = new StructuredOutputDeadlineExecutor(props, Clock.systemUTC())) {
      var invoker = new StructuredOutputInvoker(props, new AiCallTelemetryRecorder(props, null,
          List.of(observation -> { observations.add(observation); observed.countDown(); })), executor);
      ChatModel model = prompt -> {
        calls.incrementAndGet(); entered.countDown();
        boolean released = false;
        while (!released) {
          try { released = release.await(2, TimeUnit.SECONDS); }
          catch (InterruptedException ignored) { /* Simulate transport clearing interruption. */ }
        }
        return response("{\"value\":\"late\"}");
      };
      var caller = new FutureTask<Payload>(() -> invoke(invoker, model, Instant.now().plusSeconds(5)));
      Thread thread = Thread.ofPlatform().start(caller);
      try {
        assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
        thread.interrupt();
        thread.join(1000);
        assertThat(thread.isAlive()).isFalse();
        assertThatThrownBy(caller::get).hasCauseInstanceOf(BusinessException.class);
      } finally { release.countDown(); }
      assertThat(observed.await(2, TimeUnit.SECONDS)).isTrue();
      assertThat(calls.get()).isEqualTo(1);
      assertThat(observations).allSatisfy(observation ->
          assertThat(observation.parseOutcome()).isEqualTo(AiParseOutcome.CALL_FAILURE));
    }
  }

  @Test
  @DisplayName("不可中断模型不会阻塞调用方到deadline之后，迟到成功不记录为成功")
  void releasesCallerEvenWhenProviderIgnoresInterruption() throws Exception {
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var finished = new CountDownLatch(1);
    var calls = new AtomicInteger();
    ChatModel model = prompt -> {
      calls.incrementAndGet();
      entered.countDown();
      boolean released = false;
      long stop = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
      while (!released && System.nanoTime() < stop) {
        try { released = release.await(50, TimeUnit.MILLISECONDS); }
        catch (InterruptedException ignored) { /* Provider fixture intentionally ignores cancel. */ }
      }
      finished.countDown();
      return response("{\"value\":\"late\"}");
    };
    try (var fixture = fixture(Clock.systemUTC())) {
      long start = System.nanoTime();
      try {
        assertThatThrownBy(() -> invoke(fixture.invoker, model, Instant.now().plusMillis(200)))
            .isInstanceOf(BusinessException.class).hasMessageStartingWith("bounded: ");
        assertThat(entered.getCount()).isZero();
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)).isLessThan(1500);
        assertThat(finished.getCount()).isEqualTo(1);
        assertThat(calls.get()).isEqualTo(1);
      } finally { release.countDown(); }
      assertThat(finished.await(2, TimeUnit.SECONDS)).isTrue();
    }
  }

  @Test
  @DisplayName("修复重试复用绝对deadline，耗时后不得开启第二次模型调用")
  void retriesCannotResetDeadline() {
    var clock = new MutableClock();
    var calls = new AtomicInteger();
    ChatModel model = prompt -> {
      calls.incrementAndGet();
      clock.now.set(clock.instant().plusSeconds(31));
      return response("invalid-json");
    };
    try (var fixture = fixture(clock)) {
      assertThatThrownBy(() -> invoke(fixture.invoker, model, clock.instant().plusSeconds(30)))
          .isInstanceOf(BusinessException.class).hasMessageStartingWith("bounded: ");
      assertThat(calls.get()).isEqualTo(1);
      assertThat(fixture.observations).noneSatisfy(observation ->
          assertThat(observation.parseOutcome()).isEqualTo(AiParseOutcome.DIRECT_SUCCESS));
    }
  }

  @Test
  @DisplayName("模型在deadline后返回合法结果也不能成功")
  void validLateResponseIsRejected() {
    var clock = new MutableClock();
    ChatModel model = prompt -> {
      clock.now.set(clock.instant().plusSeconds(31));
      return response("{\"value\":\"late\"}");
    };
    try (var fixture = fixture(clock)) {
      assertThatThrownBy(() -> invoke(fixture.invoker, model, clock.instant().plusSeconds(30)))
          .isInstanceOf(BusinessException.class);
      assertThat(fixture.observations).noneSatisfy(observation ->
          assertThat(observation.parseOutcome()).isEqualTo(AiParseOutcome.DIRECT_SUCCESS));
    }
  }

  @Test
  @DisplayName("排队也消耗原deadline，重新投递同一deadline不会访问模型")
  void queuedWorkExpiresAndRedeliveryDoesNotResetDeadline() throws Exception {
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    try (var fixture = fixture(Clock.systemUTC())) {
      var firstError = new AtomicReference<Throwable>();
      Thread first = Thread.ofPlatform().start(() -> {
        try { fixture.executor.execute(() -> { entered.countDown(); release.await(); return "ok"; },
            Instant.now().plusSeconds(5), ErrorCode.AI_SERVICE_ERROR, "first: "); }
        catch (Throwable error) { firstError.set(error); }
      });
      try {
        assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
        var calls = new AtomicInteger();
        ChatModel queued = prompt -> { calls.incrementAndGet(); return response("{\"value\":\"ok\"}"); };
        Instant deadline = Instant.now().plusMillis(150);
        assertThatThrownBy(() -> invoke(fixture.invoker, queued, deadline))
            .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> invoke(fixture.invoker, queued, deadline))
            .isInstanceOf(BusinessException.class);
        assertThat(calls.get()).isZero();
      } finally { release.countDown(); first.join(2000); }
      assertThat(firstError.get()).isNull();
    }
  }

  @Test
  @DisplayName("有界线程池饱和时快速失败且保留调用方错误契约")
  void rejectsSaturationWithCallerErrorContract() throws Exception {
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    try (var fixture = fixture(Clock.systemUTC())) {
      Thread active = Thread.ofPlatform().start(() -> fixture.executor.execute(() -> {
        entered.countDown(); release.await(); return "first";
      }, Instant.now().plusSeconds(5), ErrorCode.AI_SERVICE_ERROR, "first: "));
      Thread queued = null;
      try {
        assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
        queued = Thread.ofPlatform().start(() -> fixture.executor.execute(() -> "second",
            Instant.now().plusSeconds(5), ErrorCode.AI_SERVICE_ERROR, "second: "));
        long limit = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (queued.getState() != Thread.State.TIMED_WAITING && System.nanoTime() < limit) {
          Thread.onSpinWait();
        }
        assertThat(queued.getState()).isEqualTo(Thread.State.TIMED_WAITING);
        assertThatThrownBy(() -> fixture.executor.execute(() -> "third", Instant.now().plusSeconds(5),
            ErrorCode.INTERVIEW_QUESTION_GENERATION_FAILED, "custom: "))
            .isInstanceOfSatisfying(BusinessException.class, exception -> {
              assertThat(exception.getCode()).isEqualTo(3006);
              assertThat(exception.getMessage()).startsWith("custom: ");
            });
      } finally {
        release.countDown(); active.join(2000);
        if (queued != null) { queued.join(2000); }
      }
    }
  }

  @Test
  @DisplayName("旧双参构造器保持同步兼容，但不能静默绕过deadline")
  void legacyConstructorCannotBypassDeadline() {
    var props = new StructuredOutputProperties();
    var invoker = new StructuredOutputInvoker(props,
        new AiCallTelemetryRecorder(props, null, List.of()));
    assertThatThrownBy(() -> invoke(invoker, prompt -> response("{\"value\":\"ok\"}"),
        Instant.now().plusSeconds(3))).isInstanceOf(BusinessException.class);
  }

  static Payload invoke(StructuredOutputInvoker invoker, ChatModel model, Instant deadline) {
    return invoker.invoke(ChatClient.create(model), "system", "user",
        new BeanOutputConverter<>(Payload.class), ErrorCode.AI_SERVICE_ERROR, "bounded: ",
        "deadline-test", "fixture", "fixture", value -> {}, deadline,
        LoggerFactory.getLogger(StructuredOutputInvokerDeadlineTest.class));
  }

  static Fixture fixture(Clock clock) {
    return fixture(clock, false);
  }

  static Fixture fixture(Clock clock, boolean schemaEnabled) {
    var properties = new StructuredOutputProperties();
    properties.setStructuredSchemaValidationEnabled(schemaEnabled);
    properties.setStructuredDeadlineThreads(1);
    properties.setStructuredDeadlineQueueCapacity(1);
    var executor = new StructuredOutputDeadlineExecutor(properties, clock);
    List<AiCallObservation> observations = new CopyOnWriteArrayList<>();
    return new Fixture(executor, new StructuredOutputInvoker(properties,
        new AiCallTelemetryRecorder(properties, null, List.of(observations::add)), executor), observations);
  }

  static ChatResponse response(String value) {
    return new ChatResponse(List.of(new Generation(new AssistantMessage(value))));
  }
  record Payload(String value) {}
  record Fixture(StructuredOutputDeadlineExecutor executor, StructuredOutputInvoker invoker,
      List<AiCallObservation> observations) implements AutoCloseable {
    @Override public void close() { executor.close(); }
  }
  static class MutableClock extends Clock {
    final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-10-29T00:00:00Z"));
    @Override public ZoneId getZone() { return ZoneOffset.UTC; }
    @Override public Clock withZone(ZoneId zone) { return this; }
    @Override public Instant instant() { return now.get(); }
  }
}
