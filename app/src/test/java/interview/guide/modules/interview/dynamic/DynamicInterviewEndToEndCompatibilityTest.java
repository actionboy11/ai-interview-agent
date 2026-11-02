package interview.guide.modules.interview.dynamic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

import com.rabbitmq.client.Channel;
import interview.guide.common.ai.LlmProviderRegistry;
import interview.guide.common.constant.AsyncTaskStreamConstants;
import interview.guide.common.model.AsyncTaskStatus;
import interview.guide.common.transaction.TransactionalExecutor;
import interview.guide.infrastructure.redis.InterviewSessionCache;
import interview.guide.infrastructure.redis.RedisService;
import interview.guide.modules.interview.dynamic.messaging.FollowUpDecisionConsumer;
import interview.guide.modules.interview.dynamic.messaging.FollowUpDecisionProducer;
import interview.guide.modules.interview.dynamic.model.FollowUpDecisionEntity.DeliveryStatus;
import interview.guide.modules.interview.dynamic.model.FollowUpDecisionEntity.Status;
import interview.guide.modules.interview.dynamic.service.DynamicDecisionDispatchService;
import interview.guide.modules.interview.dynamic.service.DynamicDecisionWorker;
import interview.guide.modules.interview.dynamic.service.DynamicEvaluationDispatchService;
import interview.guide.modules.interview.dynamic.service.DynamicEvaluationOrchestrationService;
import interview.guide.modules.interview.listener.EvaluateStreamConsumer;
import interview.guide.modules.interview.listener.EvaluateStreamProducer;
import interview.guide.modules.interview.messaging.rabbit.InterviewEvaluationMessage;
import interview.guide.modules.interview.messaging.rabbit.InterviewEvaluationRabbitConsumer;
import interview.guide.modules.interview.messaging.rabbit.InterviewEvaluationRabbitProducer;
import interview.guide.modules.interview.messaging.rabbit.InterviewEvaluationRabbitProperties;
import interview.guide.modules.interview.messaging.rabbit.InterviewEvaluationRetryPolicy;
import interview.guide.modules.interview.model.InterviewReportDTO;
import interview.guide.modules.interview.model.InterviewSessionEntity;
import interview.guide.modules.interview.service.AnswerEvaluationService;
import interview.guide.modules.interview.service.MemoryDerivationPublicationService;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.api.stream.StreamMessageId;
import org.redisson.client.codec.StringCodec;
import org.redisson.config.Config;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import jakarta.servlet.http.Cookie;
import interview.guide.modules.interview.dynamic.dto.DynamicSubmitRequest;
import interview.guide.modules.interview.dynamic.model.SubmissionIntent;

@DisplayName("真实PG与Redis端到端恢复及旧协议兼容")
class DynamicInterviewEndToEndCompatibilityTest extends DynamicPostgresTestSupport {
  @Autowired ApplicationContext applicationContext;
  private RedissonClient client;
  private RedisService realRedis;
  private String key;
  private String group;

  @BeforeEach
  void connect() {
    jdbc().update("update interview_sessions set llm_provider = 'test' where id = ?", session.getId());
    key = "task10:" + UUID.randomUUID() + ":stream";
    group = "task10-" + UUID.randomUUID();
    reconnect();
    realRedis.createStreamGroup(key, group);
  }

  private void reconnect() {
    var config = new Config();
    config.useSingleServer().setAddress("redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379))
        .setConnectionMinimumIdleSize(1).setConnectionPoolSize(4).setTimeout(1500);
    client = Redisson.create(config);
    realRedis = new RedisService(client) {
      @Override public String streamAdd(String stream, Map<String, String> payload, int max) {
        // Production retry uses a constant; remap only that constant inside this disposable fixture.
        return super.streamAdd(stream.equals(AsyncTaskStreamConstants.INTERVIEW_EVALUATE_STREAM_KEY)
            ? key : stream, payload, max);
      }
    };
  }

  @AfterEach
  void closeClient() { if (client != null) { client.shutdown(); } }

  private FollowUpDecisionProducer producer() {
    return new FollowUpDecisionProducer(realRedis) {
      @Override protected String streamKey() { return key; }
    };
  }

  private FollowUpDecisionConsumer consumer(RedisService redis, DynamicDecisionWorker worker) {
    return new FollowUpDecisionConsumer(redis, worker, producer()) {
      @Override protected String streamKey() { return key; }
      @Override protected String groupName() { return group; }
    };
  }

  private void consumeOnce(Object consumer) {
    var messages = realRedis.streamReadGroup(key, group, "acceptance", 10);
    messages.forEach((id, data) -> ReflectionTestUtils.invokeMethod(consumer, "processMessage", id, data));
  }

  private long pending() {
    return client.<String, String>getStream(key, StringCodec.INSTANCE).getPendingInfo(group).getTotal();
  }

  @Test
  @DisplayName("数据库提交后Redis连接失效可重投，真实PEL丢ACK恢复只追加一次")
  void disconnectedPublisherAndLostAckRecoverOnce() {
    var submitted = submitAnswer();
    var disconnected = producer();
    client.shutdown();
    new DynamicDecisionDispatchService(store, disconnected, clock).dispatch(NOW);
    assertThat(decisions.findById(submitted.decisionId()).orElseThrow().getDeliveryStatus())
        .isEqualTo(DeliveryStatus.PENDING);
    assertThat(answers.findBySessionIdOrderByQuestionIndex(session.getId())).hasSize(1);
    reconnect();
    clock.now = NOW.plusSeconds(2);
    new DynamicDecisionDispatchService(store, producer(), clock).dispatch(clock.instant());
    var modelCalls = new AtomicInteger();
    when(model.decide(any(), any(), any())).thenAnswer(call -> { modelCalls.incrementAndGet(); return followUp(); });
    var acknowledgements = new AtomicInteger();
    var lostAck = new RedisService(client) {
      @Override public void streamAck(String stream, String name, StreamMessageId... ids) {
        if (acknowledgements.incrementAndGet() == 1) { throw new IllegalStateException("lost ACK"); }
        super.streamAck(stream, name, ids);
      }
    };
    var consumer = consumer(lostAck, worker);
    consumeOnce(consumer);
    assertThat(pending()).isOne();
    assertThat(runtime().getUsedFollowUps()).isOne();
    await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
      realRedis.streamConsumeMessages(key, group, "restarted-worker", 10, 10, 1, 10,
          (id, data) -> ReflectionTestUtils.invokeMethod(consumer, "processMessage", id, data));
      assertThat(pending()).isZero();
    });
    assertThat(modelCalls.get()).isOne();
    assertThat(runtime().getUsedFollowUps()).isOne();
    assertThat(queries.get(owner.getId(), session.getSessionId()).totalQuestions()).isEqualTo(2);
    assertThat(decisions.findById(submitted.decisionId()).orElseThrow().getDeadline())
        .isEqualTo(NOW.plusSeconds(30));
  }

  @Test
  @DisplayName("真实Redis已ACK但未执行的工作仍由PG原始deadline收敛")
  void acknowledgedMissingWorkExpires() {
    var submitted = submitAnswer();
    new DynamicDecisionDispatchService(store, producer(), clock).dispatch(NOW);
    var messages = realRedis.streamReadGroup(key, group, "lost-worker", 1);
    assertThat(messages).hasSize(1);
    messages.keySet().forEach(id -> realRedis.streamAck(key, group, id));
    assertThat(pending()).isZero();
    clock.now = NOW.plusSeconds(30);
    recovery.recover(clock.instant());
    assertThat(runtime().getCurrentMainOrdinal()).isOne();
    assertThat(runtime().getUsedFollowUps()).isZero();
    assertThat(decisions.findById(submitted.decisionId()).orElseThrow().getStatus()).isEqualTo(Status.FAILED);
  }

  @Test
  @DisplayName("Redis模板只重试三次，PG deadline不会因四次投递延长")
  void retryThreeTimesRetainsDeadline() {
    var submitted = submitAnswer();
    var failing = mock(DynamicDecisionWorker.class);
    var calls = new AtomicInteger();
    doAnswer(call -> {
      clock.now = NOW.plusSeconds(8L * calls.incrementAndGet());
      throw new IllegalStateException("synthetic worker interruption");
    }).when(failing).process(submitted.decisionId());
    producer().publish(submitted.decisionId());
    var consumer = consumer(realRedis, failing);
    for (int attempt = 0; attempt < 4; attempt++) { consumeOnce(consumer); }
    assertThat(calls.get()).isEqualTo(4);
    assertThat(realRedis.streamLen(key)).isEqualTo(4);
    assertThat(pending()).isZero();
    assertThat(decisions.findById(submitted.decisionId()).orElseThrow().getDeadline())
        .isEqualTo(NOW.plusSeconds(30));
    recovery.recover(clock.instant());
    assertThat(runtime().getNoticeCode()).isEqualTo("DECISION_TIMEOUT");
    assertThat(runtime().getUsedFollowUps()).isZero();
  }

  @Test
  @DisplayName("Redis冻结任务发布回执丢失及评价重试仍完成同一报告和每组一份记忆")
  void redisEvaluationStableIdentityThroughRetry() throws Exception {
    submitAnswer();
    var id = freeze();
    var scope = persistence.loadDynamicEvaluation(session.getSessionId(), id).orElseThrow().snapshot();
    var attempts = new AtomicInteger();
    var scripted = new DynamicInterviewEvaluationTest.ScriptedModel(evaluationOutput(scope)) {
      @Override public org.springframework.ai.chat.model.ChatResponse call(org.springframework.ai.chat.prompt.Prompt prompt) {
        if (attempts.incrementAndGet() == 1) { throw new IllegalStateException("first evaluation unavailable"); }
        return super.call(prompt);
      }
    };
    var orchestration = new DynamicEvaluationOrchestrationService(persistence,
        DynamicInterviewEvaluationTest.evaluator(scripted));
    var publication = new MemoryDerivationPublicationService(persistence,
        sessionId -> derivation.derive(sessionId, UUID.randomUUID()));
    var cache = new InterviewSessionCache(realRedis, mapper, currentUser);
    var consumer = new EvaluateStreamConsumer(realRedis, sessions, mock(AnswerEvaluationService.class),
        persistence, mapper, mock(LlmProviderRegistry.class), cache, publication, orchestration) {
      @Override protected String streamKey() { return key; }
      @Override protected String groupName() { return group; }
    };
    var sends = new AtomicInteger();
    var producer = new EvaluateStreamProducer(realRedis, persistence, mock(TransactionalExecutor.class)) {
      @Override protected String streamKey() { return key; }
      @Override public PublishReceipt publish(String sessionId, UUID messageId) {
        var receipt = super.publish(sessionId, messageId);
        if (sends.incrementAndGet() == 1) { throw new IllegalStateException("lost publish receipt"); }
        return receipt;
      }
    };
    var dispatch = new DynamicEvaluationDispatchService(evaluationStore, producer, clock);
    dispatch.dispatch(NOW);
    clock.now = NOW.plusSeconds(2);
    dispatch.dispatch(clock.instant());
    consumeOnce(consumer);
    consumeOnce(consumer);
    assertThat(attempts.get()).isEqualTo(2);
    assertThat(pending()).isZero();
    assertThat(realRedis.streamLen(key)).isEqualTo(3);
    var entries = client.<String, String>getStream(key, StringCodec.INSTANCE)
        .range(StreamMessageId.MIN, StreamMessageId.MAX);
    assertThat(entries.values()).allSatisfy(payload -> assertThat(payload).containsEntry("messageId", id.toString()));
    assertThat(entries.values()).anySatisfy(payload -> assertThat(payload).containsEntry("retryCount", "1"));
    assertThat(sessions.findById(session.getId()).orElseThrow().getEvaluationMessageId()).isEqualTo(id.toString());
    assertThat(sessions.findById(session.getId()).orElseThrow().getMemoryDerivationStatus())
        .isEqualTo(AsyncTaskStatus.COMPLETED);
    assertThat(evidence.findByOwnerIdAndInterviewSessionId(owner.getId(), session.getId())).hasSize(1);
    String reportJson = sessions.findById(session.getId()).orElseThrow().getReportJson();
    producer.publish(session.getSessionId(), id);
    consumeOnce(consumer);
    assertThat(attempts.get()).isEqualTo(2);
    assertThat(sessions.findById(session.getId()).orElseThrow().getReportJson()).isEqualTo(reportJson);
  }

  @Test
  @DisplayName("Rabbit适配器从冻结发布到重试消费保持原始ID且首次预分配ID不会误ACK")
  void rabbitAdapterCompletesStableOriginalIdentity() throws Exception {
    submitAnswer();
    var id = freeze();
    var sent = new ArrayList<InterviewEvaluationMessage>();
    var broker = mock(RabbitTemplate.class);
    doAnswer(call -> {
      InterviewEvaluationMessage task = call.getArgument(2);
      CorrelationData correlation = call.getArgument(4);
      assertThat(correlation.getId()).isEqualTo(task.messageId().toString());
      sent.add(task);
      correlation.getFuture().complete(new CorrelationData.Confirm(true, null));
      return null;
    }).when(broker).convertAndSend(anyString(), anyString(), any(), any(), any(CorrelationData.class));
    var settings = new InterviewEvaluationRabbitProperties("rabbitmq", "test.exchange", "test.queue",
        "test.routing", "test.dead", "test.dead.queue", "test.dead.routing", List.of(Duration.ofSeconds(1)));
    var producer = new InterviewEvaluationRabbitProducer(broker, settings);
    new DynamicEvaluationDispatchService(evaluationStore, producer, clock).dispatch(NOW);
    assertThat(sent).hasSize(1);
    var scope = persistence.loadDynamicEvaluation(session.getSessionId(), id).orElseThrow().snapshot();
    var calls = new AtomicInteger();
    var scripted = new DynamicInterviewEvaluationTest.ScriptedModel(evaluationOutput(scope)) {
      @Override public org.springframework.ai.chat.model.ChatResponse call(org.springframework.ai.chat.prompt.Prompt prompt) {
        if (calls.incrementAndGet() == 1) { throw new IllegalStateException("first attempt failed"); }
        return super.call(prompt);
      }
    };
    var consumer = new InterviewEvaluationRabbitConsumer(sessions, mock(AnswerEvaluationService.class),
        persistence, mapper, mock(LlmProviderRegistry.class), producer, new InterviewEvaluationRetryPolicy(settings),
        new InterviewSessionCache(realRedis, mapper, currentUser), new MemoryDerivationPublicationService(persistence,
            sessionId -> derivation.derive(sessionId, UUID.randomUUID())),
        new DynamicEvaluationOrchestrationService(persistence, DynamicInterviewEvaluationTest.evaluator(scripted)));
    var channel = mock(Channel.class);
    var envelope = new Message(new byte[0], new MessageProperties());
    consumer.consume(sent.getFirst(), envelope, channel);
    assertThat(sent).hasSize(2);
    assertThat(sent.getLast().messageId()).isNotEqualTo(id);
    assertThat(sent).allSatisfy(task -> assertThat(task.originalMessageId()).isEqualTo(id));
    consumer.consume(sent.getLast(), envelope, channel);
    String json = sessions.findById(session.getId()).orElseThrow().getReportJson();
    assertThat(json).isNotNull();
    consumer.consume(sent.getFirst(), envelope, channel);
    assertThat(calls.get()).isEqualTo(2);
    assertThat(sessions.findById(session.getId()).orElseThrow().getReportJson()).isEqualTo(json);
    assertThat(evidence.findByOwnerIdAndInterviewSessionId(owner.getId(), session.getId())).hasSize(1);
  }

  @Test
  @DisplayName("真实PG允许旧提交元数据为空，拒绝外部归属且读取旧报告不改写")
  void legacyNullMetadataAndReportRemainUntouched() {
    var old = new InterviewSessionEntity();
    old.setOwner(owner);
    old.setSessionId(UUID.randomUUID().toString());
    old.setQuestionsJson("[]");
    old.setStatus(InterviewSessionEntity.SessionStatus.EVALUATED);
    old.setEvaluateStatus(AsyncTaskStatus.COMPLETED);
    String original = mapper.writeValueAsString(new InterviewReportDTO(old.getSessionId(), 1, 0,
        List.of(), List.of(), "旧报告原文", List.of(), List.of(), List.of()));
    old.setReportJson(original);
    tx.executeWithoutResult(status -> em.persist(old));
    jdbc().update("insert into interview_answers (session_id, question_index, answered_at, category_key) "
        + "values (?, 0, now(), 'OTHER')", old.getId());
    assertThat(jdbc().queryForObject("select count(*) from pg_constraint where conname = "
        + "'fk_dynamic_answer_session_owner'", Integer.class)).isOne();
    assertThatThrownBy(() -> jdbc().update("update interview_answers set submission_intent = 'ANSWER', "
        + "submission_owner_id = ?, request_id = ?, request_fingerprint = ? where session_id = ?",
        UUID.randomUUID(), UUID.randomUUID(), "a".repeat(64), old.getId()))
        .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("fk_dynamic_answer_session_owner");
    assertThat(persistence.findCompletedReport(old.getSessionId()).orElseThrow().overallScore()).isZero();
    assertThat(sessions.findById(old.getId()).orElseThrow().getReportJson()).isEqualTo(original);
    assertThat(runtimes.findBySession_SessionId(old.getSessionId())).isEmpty();
  }

  @Test
  @DisplayName("真实Redis会话恢复、安全过滤链、CSRF、归属及Lua限流在动态入口共同生效")
  void realSessionSecurityAndRateLimit() throws Exception {
    try (var web = new DynamicAcceptanceWebFixture(applicationContext)) {
      String base = "/api/interview/sessions/" + session.getSessionId() + "/dynamic";
      web.mvc.perform(get(base)).andExpect(jsonPath("$.code").value(401));
      Cookie auth = web.cookie(owner);
      web.mvc.perform(get(base).cookie(auth)).andExpect(jsonPath("$.code").value(200))
          .andExpect(jsonPath("$.data.sessionId").value(session.getSessionId()));
      web.mvc.perform(get("/api/interview/sessions/unknown/dynamic").cookie(auth))
          .andExpect(jsonPath("$.code").value(3001));
      String payload = mapper.writeValueAsString(new DynamicSubmitRequest(UUID.randomUUID(), 0, 0,
          SubmissionIntent.ANSWER, "已通过真实会话与CSRF的回答"));
      web.mvc.perform(post(base + "/answers").cookie(auth).contentType("application/json").content(payload))
          .andExpect(jsonPath("$.code").value(403));
      String csrf = UUID.randomUUID().toString();
      var csrfCookie = new Cookie("XSRF-TOKEN", csrf);
      web.mvc.perform(post(base + "/answers").cookie(auth, csrfCookie).header("X-XSRF-TOKEN", "mismatch")
          .contentType("application/json").content(payload)).andExpect(jsonPath("$.code").value(403));
      for (int i = 0; i < 10; i++) {
        web.mvc.perform(post(base + "/answers").cookie(auth, csrfCookie).header("X-XSRF-TOKEN", csrf)
            .contentType("application/json").content(payload)).andExpect(jsonPath("$.code").value(200));
      }
      web.mvc.perform(post(base + "/answers").cookie(auth, csrfCookie).header("X-XSRF-TOKEN", csrf)
          .contentType("application/json").content(payload)).andExpect(jsonPath("$.code").value(8001));
      assertThat(answers.findBySessionIdOrderByQuestionIndex(session.getId())).hasSize(1);
      jdbc().update("update users set status = 'DISABLED' where id = ?", owner.getId());
      web.mvc.perform(get(base).cookie(auth)).andExpect(jsonPath("$.code").value(12002));
    }
  }
}
