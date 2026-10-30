package interview.guide.modules.interview.dynamic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import interview.guide.common.transaction.TransactionalExecutor;
import interview.guide.infrastructure.redis.RedisService;
import interview.guide.modules.interview.dynamic.messaging.FollowUpDecisionConsumer;
import interview.guide.modules.interview.dynamic.messaging.FollowUpDecisionProducer;
import interview.guide.modules.interview.dynamic.model.FollowUpDecisionEntity.DeliveryStatus;
import interview.guide.modules.interview.dynamic.model.FollowUpDecisionEntity.Status;
import interview.guide.modules.interview.dynamic.service.DynamicDecisionDispatchService;
import interview.guide.modules.interview.dynamic.service.DynamicDecisionWorker;
import interview.guide.modules.interview.dynamic.service.DynamicEvaluationDispatchService;
import interview.guide.modules.interview.listener.EvaluateStreamProducer;
import interview.guide.modules.interview.model.InterviewSessionEntity;
import interview.guide.modules.interview.service.InterviewPersistenceService;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
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
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
@DisplayName("隔离Redis动态决策投递和ACK恢复")
class DynamicDecisionRedisIntegrationTest extends DynamicDecisionTestSupport {
  @Container
  static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine"))
      .withExposedPorts(6379);

  private RedissonClient client;
  private RedisService realRedis;
  private FollowUpDecisionProducer realProducer;
  private FollowUpDecisionConsumer consumer;
  private String streamKey;
  private String group;

  @BeforeEach
  void connectDisposableRedis() {
    var config = new Config();
    config.useSingleServer().setAddress("redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379))
        .setConnectionMinimumIdleSize(1).setConnectionPoolSize(4).setTimeout(1500);
    client = Redisson.create(config);
    realRedis = new RedisService(client);
    streamKey = "task5:" + UUID.randomUUID() + ":stream";
    group = "task5-" + UUID.randomUUID();
    realRedis.createStreamGroup(streamKey, group);
    realProducer = new FollowUpDecisionProducer(realRedis) {
      @Override protected String streamKey() { return streamKey; }
    };
  }

  @AfterEach
  void closeDisposableClients() {
    if (consumer != null) { consumer.shutdown(); }
    if (client != null) { client.shutdown(); }
  }

  @Test
  @DisplayName("真实Redis重投与ACK丢失后的pending回收不会重复追加")
  void redeliveryAfterLostAckAppendsOnlyOnce() {
    var submitted = submitAnswer();
    var modelCalls = new AtomicInteger();
    when(model.decide(any(), any(), any())).thenAnswer(invocation -> {
      modelCalls.incrementAndGet(); return followUp();
    });
    var ackAttempts = new AtomicInteger();
    RedisService lostFirstAck = new RedisService(client) {
      @Override public void streamAck(String key, String name, StreamMessageId... ids) {
        if (ackAttempts.incrementAndGet() == 1) { throw new IllegalStateException("lost ACK"); }
        super.streamAck(key, name, ids);
      }
      @Override public boolean streamConsumeMessages(String key, String name, String consumerName,
          int count, long timeout, long idle, int claimBatch, StreamMessageProcessor processor) {
        // Short test-only idle interval exercises the same production reclaim/ACK template.
        return super.streamConsumeMessages(key, name, consumerName, count, timeout, 1, claimBatch, processor);
      }
    };
    startConsumer(lostFirstAck, worker);
    new DynamicDecisionDispatchService(store, realProducer, clock).dispatch(NOW);
    await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
      assertThat(ackAttempts.get()).isGreaterThanOrEqualTo(2);
      assertThat(pending()).isZero();
      assertThat(runtime().getUsedFollowUps()).isEqualTo(1);
    });
    assertThat(modelCalls.get()).isEqualTo(1);
    assertThat(queries.get(owner.getId(), session.getSessionId()).totalQuestions()).isEqualTo(2);
    assertThat(decisions.findById(submitted.decisionId()).orElseThrow().getDeadline())
        .isEqualTo(NOW.plusSeconds(30));
  }

  @Test
  @DisplayName("真实消息已ACK但工作器未处理时数据库deadline仍收敛")
  void ackedMessageLostStillExpiresFromDatabase() {
    var submitted = submitAnswer();
    new DynamicDecisionDispatchService(store, realProducer, clock).dispatch(NOW);
    assertThat(decisions.findById(submitted.decisionId()).orElseThrow().getDeliveryStatus())
        .isEqualTo(DeliveryStatus.PUBLISHED);
    var messages = realRedis.streamReadGroup(streamKey, group, "lost-worker", 1);
    assertThat(messages).hasSize(1);
    messages.forEach((id, payload) -> {
      assertThat(payload).containsExactlyInAnyOrderEntriesOf(Map.of(
          "decisionId", submitted.decisionId().toString(), "retryCount", "0"));
      realRedis.streamAck(streamKey, group, id);
    });
    assertThat(pending()).isZero();
    clock.now = NOW.plusSeconds(30);
    recovery.recover(clock.instant());
    assertThat(runtime().getCurrentMainOrdinal()).isEqualTo(1);
    assertThat(runtime().getUsedFollowUps()).isZero();
    assertThat(decisions.findById(submitted.decisionId()).orElseThrow().getStatus())
        .isEqualTo(Status.FAILED);
    verifyNoInteractions(model);
  }

  @Test
  @DisplayName("已删除会话的真实消息被ACK且不会调用模型")
  void deletedSessionIsAcked() throws Exception {
    var submitted = submitAnswer();
    tx.executeWithoutResult(status -> em.remove(em.find(InterviewSessionEntity.class, session.getId())));
    var acked = new CountDownLatch(1);
    RedisService observed = new RedisService(client) {
      @Override public void streamAck(String key, String name, StreamMessageId... ids) {
        super.streamAck(key, name, ids); acked.countDown();
      }
    };
    startConsumer(observed, worker);
    realProducer.publish(submitted.decisionId());
    assertThat(acked.await(10, TimeUnit.SECONDS)).isTrue();
    assertThat(pending()).isZero();
    verifyNoInteractions(model);
  }

  @Test
  @DisplayName("通用模板仅重试三次，四次投递跨越期限也不能重置数据库deadline")
  void queueRetriesCannotExtendOriginalDeadline() throws Exception {
    var submitted = submitAnswer();
    var failingWorker = mock(DynamicDecisionWorker.class);
    var attempts = new AtomicInteger();
    doAnswer(invocation -> {
      clock.now = NOW.plusSeconds(8L * attempts.incrementAndGet());
      throw new IllegalStateException("database temporarily unavailable");
    }).when(failingWorker).process(submitted.decisionId());
    var acked = new CountDownLatch(4);
    RedisService observed = new RedisService(client) {
      @Override public void streamAck(String key, String name, StreamMessageId... ids) {
        super.streamAck(key, name, ids); acked.countDown();
      }
    };
    startConsumer(observed, failingWorker);
    realProducer.publish(submitted.decisionId());
    assertThat(acked.await(10, TimeUnit.SECONDS)).isTrue();
    assertThat(attempts.get()).isEqualTo(4);
    assertThat(realRedis.streamLen(streamKey)).isEqualTo(4);
    assertThat(pending()).isZero();
    assertThat(decisions.findById(submitted.decisionId()).orElseThrow().getDeadline())
        .isEqualTo(NOW.plusSeconds(30));
    recovery.recover(clock.instant());
    assertThat(runtime().getNoticeCode()).isEqualTo("DECISION_TIMEOUT");
    assertThat(runtime().getUsedFollowUps()).isZero();
  }

  @Test
  @DisplayName("真实Redis评价发布成功但回执丢失后重投仍携带相同冻结ID")
  void evaluationLostReceiptKeepsStableStreamIdentity() {
    var completed = commands.complete(owner.getId(), session.getSessionId(), 0);
    var outbox = outboxes.findBySession_IdAndFrozenVersion(session.getId(), completed.version()).orElseThrow();
    var sends = new AtomicInteger();
    var evaluationProducer = new EvaluateStreamProducer(realRedis,
        mock(InterviewPersistenceService.class), mock(TransactionalExecutor.class)) {
      @Override protected String streamKey() { return streamKey; }
      @Override public PublishReceipt publish(String sessionId, UUID messageId) {
        var receipt = super.publish(sessionId, messageId);
        if (sends.incrementAndGet() == 1) { throw new IllegalStateException("receipt lost"); }
        return receipt;
      }
    };
    var evaluation = new DynamicEvaluationDispatchService(evaluationStore, evaluationProducer, clock);
    evaluation.dispatch(NOW);
    assertThat(outboxes.findById(outbox.getId()).orElseThrow().getDeliveryStatus())
        .isEqualTo(DeliveryStatus.PENDING);
    clock.now = NOW.plusSeconds(2);
    evaluation.dispatch(clock.instant());
    var messages = realRedis.streamReadGroup(streamKey, group, "evaluation-probe", 10);
    assertThat(messages).hasSize(2);
    assertThat(messages.values()).allSatisfy(payload -> assertThat(payload)
        .containsEntry("messageId", outbox.getMessageId().toString())
        .containsEntry("sessionId", session.getSessionId()));
    assertThat(outboxes.findById(outbox.getId()).orElseThrow().getDeliveryStatus())
        .isEqualTo(DeliveryStatus.PUBLISHED);
  }

  private void startConsumer(RedisService redisService, DynamicDecisionWorker taskWorker) {
    consumer = new FollowUpDecisionConsumer(redisService, taskWorker, realProducer) {
      @Override protected String streamKey() { return streamKey; }
      @Override protected String groupName() { return group; }
    };
    consumer.init();
  }

  private long pending() {
    return client.<String, String>getStream(streamKey, StringCodec.INSTANCE).getPendingInfo(group).getTotal();
  }
}
