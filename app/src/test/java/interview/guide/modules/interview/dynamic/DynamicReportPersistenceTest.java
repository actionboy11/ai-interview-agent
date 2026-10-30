package interview.guide.modules.interview.dynamic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.never;

import com.rabbitmq.client.Channel;
import interview.guide.common.ai.LlmProviderRegistry;
import interview.guide.common.constant.AsyncTaskStreamConstants;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.common.model.AsyncTaskStatus;
import interview.guide.modules.auth.model.UserEntity;
import interview.guide.modules.auth.model.UserRole;
import interview.guide.modules.auth.model.UserStatus;
import interview.guide.modules.auth.security.CurrentUserProvider;
import interview.guide.modules.interview.dynamic.dto.DynamicSubmitRequest;
import interview.guide.modules.interview.dynamic.model.DynamicInterviewEvaluationSnapshot;
import interview.guide.modules.interview.dynamic.model.DynamicInterviewRuntimeEntity;
import interview.guide.modules.interview.dynamic.model.InterviewExecutionMode;
import interview.guide.modules.interview.dynamic.model.SubmissionIntent;
import interview.guide.modules.interview.dynamic.repository.DynamicEvaluationOutboxRepository;
import interview.guide.modules.interview.dynamic.repository.DynamicInterviewRuntimeRepository;
import interview.guide.modules.interview.dynamic.service.DynamicAdvancePolicy;
import interview.guide.modules.interview.dynamic.service.DynamicEvaluationOrchestrationService;
import interview.guide.modules.interview.dynamic.service.DynamicInterviewCommandService;
import interview.guide.modules.interview.dynamic.service.DynamicInterviewEvaluationService;
import interview.guide.modules.interview.dynamic.service.DynamicInterviewLifecycle;
import interview.guide.modules.interview.dynamic.service.DynamicInterviewProperties;
import interview.guide.modules.interview.dynamic.service.DynamicInterviewQueryService;
import interview.guide.modules.interview.dynamic.service.DynamicInterviewTransitionService;
import interview.guide.modules.interview.dynamic.service.DynamicInterviewTransitionService.FrozenSnapshot;
import interview.guide.modules.interview.model.InterviewQuestionDTO;
import interview.guide.modules.interview.model.InterviewReportDTO;
import interview.guide.modules.interview.model.InterviewSessionEntity;
import interview.guide.modules.interview.listener.EvaluateStreamConsumer;
import interview.guide.modules.interview.messaging.rabbit.InterviewEvaluationMessage;
import interview.guide.modules.interview.messaging.rabbit.InterviewEvaluationRabbitConsumer;
import interview.guide.modules.interview.messaging.rabbit.InterviewEvaluationRabbitProducer;
import interview.guide.modules.interview.messaging.rabbit.InterviewEvaluationRetryPolicy;
import interview.guide.modules.interview.service.AnswerEvaluationService;
import interview.guide.modules.interview.service.MemoryDerivationPublicationService;
import interview.guide.modules.interview.service.InterviewHistoryService;
import interview.guide.modules.interview.service.InterviewQuestionCountService;
import interview.guide.modules.interview.dynamic.service.DynamicInterviewSessionSupport;
import interview.guide.infrastructure.export.PdfExportService;
import interview.guide.infrastructure.mapper.InterviewMapper;
import interview.guide.modules.memory.messaging.AbilityMemoryDerivationTaskPublisher;
import interview.guide.infrastructure.redis.InterviewSessionCache;
import interview.guide.infrastructure.redis.RedisService;
import interview.guide.modules.interview.repository.InterviewAnswerRepository;
import interview.guide.modules.interview.repository.InterviewSessionRepository;
import interview.guide.modules.interview.service.InterviewPersistenceService;
import interview.guide.modules.interview.service.InterviewPersistenceService.CompletionResult;
import interview.guide.modules.memory.service.AbilityMemoryDeletionService;
import interview.guide.modules.memory.service.AbilityScorePolicy;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.transaction.autoconfigure.TransactionAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.test.util.ReflectionTestUtils;
import org.redisson.api.stream.StreamMessageId;
import org.mapstruct.factory.Mappers;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(classes = DynamicReportPersistenceTest.JpaApplication.class, properties = {
    "spring.datasource.url=jdbc:h2:mem:dynamic-report;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa",
    "spring.datasource.password=", "spring.jpa.hibernate.ddl-auto=create-drop"
})
@DisplayName("正式报告兼容性")
class DynamicReportPersistenceTest {
  @Configuration(proxyBeanMethods = false)
  @EntityScan("interview.guide.modules")
  @EnableJpaRepositories("interview.guide.modules")
  @ImportAutoConfiguration({DataSourceAutoConfiguration.class, HibernateJpaAutoConfiguration.class,
      TransactionAutoConfiguration.class})
  @Import({DynamicAdvancePolicy.class, DynamicInterviewCommandService.class,
      DynamicInterviewQueryService.class, DynamicInterviewTransitionService.class,
      InterviewPersistenceService.class, DynamicEvaluationOrchestrationService.class,
      MemoryDerivationPublicationService.class,
      DynamicInterviewLifecycle.class, AbilityMemoryDeletionService.class, AbilityScorePolicy.class})
  static class JpaApplication {
    @Bean DynamicInterviewProperties properties() { return new DynamicInterviewProperties(); }
    @Bean ObjectMapper mapper() { return JsonMapper.builder().build(); }
    @Bean Clock clock() { return Clock.systemUTC(); }
  }

  @Autowired EntityManager em;
  @Autowired TransactionTemplate transactions;
  @Autowired ObjectMapper mapper;
  @Autowired InterviewPersistenceService persistence;
  @Autowired DynamicEvaluationOrchestrationService orchestration;
  @Autowired MemoryDerivationPublicationService memoryPublication;
  @Autowired DynamicInterviewCommandService commands;
  @Autowired DynamicEvaluationOutboxRepository outboxes;
  @Autowired DynamicInterviewRuntimeRepository runtimes;
  @Autowired InterviewSessionRepository sessions;
  @MockitoSpyBean InterviewAnswerRepository answers;
  @MockitoBean CurrentUserProvider currentUser;
  @MockitoBean DynamicInterviewEvaluationService evaluation;
  @MockitoBean AbilityMemoryDerivationTaskPublisher memoryPublisher;
  UserEntity owner;
  InterviewSessionEntity session;
  UUID messageId;
  DynamicInterviewEvaluationSnapshot scope;

  @BeforeEach
  void freezeFormalAnswer() {
    String name = UUID.randomUUID().toString();
    owner = UserEntity.builder().username(name).normalizedUsername(name).displayName(name)
        .passwordHash("test-only").role(UserRole.USER).status(UserStatus.ACTIVE)
        .createdAt(Instant.now()).updatedAt(Instant.now()).build();
    session = new InterviewSessionEntity();
    session.setOwner(owner);
    session.setSessionId(name);
    session.setExecutionMode(InterviewExecutionMode.DYNAMIC_FOLLOW_UP);
    session.setLlmProvider("test");
    var main = DynamicInterviewEvaluationTest.main("one", "已发布主问题");
    session.setQuestionsJson(mapper.writeValueAsString(List.of(InterviewQuestionDTO.planned(0,
        main.question(), main.type(), main.category(), null, false, null, main.slotId()))));
    session.setTotalQuestions(1);
    transactions.executeWithoutResult(tx -> {
      em.persist(owner);
      em.persist(session);
      em.persist(new DynamicInterviewRuntimeEntity(session, mapper.writeValueAsString(List.of(main)),
          mapper.writeValueAsString(new DynamicInterviewProperties().snapshot(1)), 1, main.slotId()));
    });
    when(currentUser.requireUserId()).thenReturn(owner.getId());
    var receipt = commands.submit(owner.getId(), session.getSessionId(),
        new DynamicSubmitRequest(UUID.randomUUID(), 0, 0, SubmissionIntent.DONT_KNOW, null));
    var outbox = outboxes.findBySession_IdAndFrozenVersion(session.getId(), receipt.runtime().version())
        .orElseThrow();
    messageId = outbox.getMessageId();
    scope = DynamicInterviewEvaluationSnapshot.fromFrozen(
        mapper.readValue(outbox.getFrozenSnapshotJson(), FrozenSnapshot.class));
  }

  @Test
  @DisplayName("旧报告JSON读取不重算评分")
  void oldReportJsonRoundTripsUnchanged() {
    var mapper = JsonMapper.builder().build();
    var original = new InterviewReportDTO("legacy", 1, 0, List.of(), List.of(), "原始评价",
        List.of("原始优势"), List.of(), List.of());
    String json = mapper.writeValueAsString(original);
    assertThat(json).doesNotContain("groupEvaluations", "executionMode", "frozenVersion");
    assertThat(mapper.writeValueAsString(mapper.readValue(json, InterviewReportDTO.class)))
        .isEqualTo(json);
    assertThat(mapper.readValue(json, InterviewReportDTO.class).overallScore()).isZero();
  }

  @Test
  @DisplayName("预分配消息ID首次评价仍原子保存正式组证据和逐项反馈")
  void preassignedIdentityCompletesAndReplayIsExact() throws Exception {
    var report = report(30);
    assertThat(persistence.completeDynamicReport(session.getSessionId(), messageId,
        scope.frozenVersion(), report)).isEqualTo(CompletionResult.CREATED);
    var saved = sessions.findBySessionId(session.getSessionId()).orElseThrow();
    String exactJson = saved.getReportJson();
    assertThat(exactJson).contains("groupEvaluationSchemaVersion", "frozenVersion", "one", "t1");
    assertThat(saved.getEvaluationMessageId()).isEqualTo(messageId.toString());
    assertThat(saved.getEvaluateStatus()).isEqualTo(AsyncTaskStatus.COMPLETED);
    assertThat(saved.getStatus()).isEqualTo(InterviewSessionEntity.SessionStatus.EVALUATED);
    assertThat(answers.findBySession_SessionIdOrderByQuestionIndex(session.getSessionId()))
        .singleElement().satisfies(answer -> {
          assertThat(answer.getScore()).isEqualTo(30);
          assertThat(answer.getFeedback()).isEqualTo("不会基础概念");
          assertThat(answer.getSubmissionIntent()).isEqualTo(SubmissionIntent.DONT_KNOW);
          assertThat(answer.getUserAnswer()).isNull();
        });
    assertThat(persistence.completeDynamicReport(session.getSessionId(), messageId,
        scope.frozenVersion(), report(99))).isEqualTo(CompletionResult.ALREADY_COMPLETED);
    assertThat(sessions.findBySessionId(session.getSessionId()).orElseThrow().getReportJson())
        .isEqualTo(exactJson);
    assertThat(mapper.writeValueAsString(persistence.findCompletedReport(session.getSessionId()).orElseThrow()))
        .isEqualTo(exactJson);
  }

  @Test
  @DisplayName("版本或消息不匹配以及旧固定入口不能写动态报告")
  void rejectsWrongScopeAndLegacyCompletion() throws Exception {
    assertThat(persistence.completeDynamicReport(session.getSessionId(), UUID.randomUUID(),
        scope.frozenVersion(), report(30))).isEqualTo(CompletionResult.SESSION_MISSING);
    assertThat(persistence.completeDynamicReport(session.getSessionId(), messageId,
        scope.frozenVersion() + 1, report(30))).isEqualTo(CompletionResult.SESSION_MISSING);
    assertThatThrownBy(() -> persistence.saveReport(session.getSessionId(), report(30), "legacy-message"))
        .isInstanceOf(BusinessException.class);
    assertThat(sessions.findBySessionId(session.getSessionId()).orElseThrow().getReportJson()).isNull();
  }

  @Test
  @DisplayName("正式来源不一致时组快照报告与逐项反馈全部保持原状")
  void invalidReportCannotPartiallyCommit() throws Exception {
    var valid = report(30);
    var forged = new InterviewReportDTO(valid.sessionId(), 1, 99, valid.categoryScores(),
        valid.questionDetails(), valid.overallFeedback(), valid.strengths(), valid.improvements(),
        valid.referenceAnswers(), valid.groupEvaluations(), valid.executionMode(), 1, valid.frozenVersion());
    assertThatThrownBy(() -> persistence.completeDynamicReport(session.getSessionId(), messageId,
        scope.frozenVersion(), forged)).isInstanceOf(BusinessException.class);
    var unchanged = sessions.findBySessionId(session.getSessionId()).orElseThrow();
    assertThat(unchanged.getReportJson()).isNull();
    assertThat(unchanged.getEvaluateStatus()).isEqualTo(AsyncTaskStatus.PENDING);
    assertThat(answers.findBySession_SessionIdOrderByQuestionIndex(session.getSessionId()))
        .singleElement().satisfies(answer -> assertThat(answer.getScore()).isNull());
  }

  @Test
  @DisplayName("逐项反馈写入中途失败回滚已刷新报告组快照与完成状态")
  void failedFeedbackWriteRollsBackReportAndStatus() throws Exception {
    var report = report(30);
    doAnswer(call -> {
      assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
      em.flush();
      throw new BusinessException(ErrorCode.INTERNAL_ERROR,
          "测试反馈写入失败");
    }).when(answers).saveAll(any());
    assertThatThrownBy(() -> persistence.completeDynamicReport(session.getSessionId(), messageId,
        scope.frozenVersion(), report)).isInstanceOf(BusinessException.class);
    var unchanged = sessions.findBySessionId(session.getSessionId()).orElseThrow();
    assertThat(unchanged.getReportJson()).isNull();
    assertThat(unchanged.getOverallScore()).isNull();
    assertThat(unchanged.getStatus()).isEqualTo(InterviewSessionEntity.SessionStatus.COMPLETED);
    assertThat(unchanged.getEvaluateStatus()).isEqualTo(AsyncTaskStatus.PENDING);
    assertThat(answers.findBySession_SessionIdOrderByQuestionIndex(session.getSessionId()))
        .singleElement().satisfies(answer -> assertThat(answer.getScore()).isNull());
  }

  @Test
  @DisplayName("冻结后的正式答案来源被改动不能产生正式评价")
  void changedAnswerSourceIsRejected() throws Exception {
    var report = report(30);
    transactions.executeWithoutResult(tx -> answers.findBySession_SessionIdOrderByQuestionIndex(
        session.getSessionId()).getFirst().setUserAnswer("不可信改动"));
    assertThatThrownBy(() -> persistence.completeDynamicReport(session.getSessionId(), messageId,
        scope.frozenVersion(), report)).isInstanceOf(BusinessException.class);
    assertThat(sessions.findBySessionId(session.getSessionId()).orElseThrow().getReportJson()).isNull();
  }

  @Test
  @DisplayName("模型返回前账号被禁用不能提交正式报告")
  void ownerDisabledDuringEvaluationCannotCommit() throws Exception {
    var report = report(30);
    when(evaluation.evaluate(eq("test"), any())).thenAnswer(call -> {
      transactions.executeWithoutResult(tx -> em.find(UserEntity.class, owner.getId())
          .setStatus(UserStatus.DISABLED));
      return report;
    });
    assertThat(orchestration.evaluate(session.getSessionId(), messageId))
        .isEqualTo(CompletionResult.SESSION_MISSING);
    assertThat(sessions.findBySessionId(session.getSessionId()).orElseThrow().getReportJson()).isNull();
  }

  @Test
  @DisplayName("模型在事务外运行并发后到报告不能覆盖首个持久结果")
  void modelRunsOutsideTransactionAndFirstResultWins() throws Exception {
    var first = report(30);
    var later = report(80);
    var calls = new AtomicInteger();
    when(evaluation.evaluate(eq("test"), any())).thenAnswer(call -> {
      assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
      calls.incrementAndGet();
      persistence.completeDynamicReport(session.getSessionId(), messageId, scope.frozenVersion(), first);
      return later;
    });
    assertThat(orchestration.evaluate(session.getSessionId(), messageId))
        .isEqualTo(CompletionResult.ALREADY_COMPLETED);
    String firstJson = sessions.findBySessionId(session.getSessionId()).orElseThrow().getReportJson();
    assertThat(mapper.readValue(firstJson, InterviewReportDTO.class).overallScore()).isEqualTo(30);
    assertThat(orchestration.evaluate(session.getSessionId(), messageId))
        .isEqualTo(CompletionResult.ALREADY_COMPLETED);
    assertThat(calls.get()).isEqualTo(1);
    assertThat(sessions.findBySessionId(session.getSessionId()).orElseThrow().getReportJson())
        .isEqualTo(firstJson);
  }

  @Test
  @DisplayName("真实删除路径删除冻结快照后旧任务安全丢弃")
  void deletedSessionIsSafelyDiscarded() {
    persistence.deleteSessionBySessionId(session.getSessionId());
    assertThat(orchestration.evaluate(session.getSessionId(), messageId))
        .isEqualTo(CompletionResult.SESSION_MISSING);
    assertThat(outboxes.findAll()).noneMatch(outbox -> outbox.getMessageId().equals(messageId));
  }

  @Test
  @DisplayName("Rabbit预分配ID首投可评价且重试只认originalMessageId")
  void rabbitUsesOriginalIdentityBeforeLegacyDuplicateCheck() throws Exception {
    var report = report(30);
    var calls = new AtomicInteger();
    when(evaluation.evaluate(eq("test"), any())).thenAnswer(call -> {
      calls.incrementAndGet();
      assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
      return report;
    });
    var consumer = rabbitConsumer();
    var channel = mock(Channel.class);
    consumer.consume(new InterviewEvaluationMessage(messageId, session.getSessionId(), 0,
        OffsetDateTime.now(), messageId), envelope(1), channel);
    var saved = sessions.findBySessionId(session.getSessionId()).orElseThrow();
    assertThat(saved.getReportJson()).isNotNull();
    String exactJson = saved.getReportJson();
    consumer.consume(new InterviewEvaluationMessage(UUID.randomUUID(), session.getSessionId(), 1,
        OffsetDateTime.now(), messageId), envelope(2), channel);
    assertThat(calls.get()).isEqualTo(1);
    assertThat(sessions.findBySessionId(session.getSessionId()).orElseThrow().getEvaluationMessageId())
        .isEqualTo(messageId.toString());
    assertThat(sessions.findBySessionId(session.getSessionId()).orElseThrow().getReportJson())
        .isEqualTo(exactJson);
    verify(channel).basicAck(1, false);
    verify(channel).basicAck(2, false);
    verify(channel, never()).basicNack(anyLong(), anyBoolean(), anyBoolean());
  }

  @Test
  @DisplayName("Rabbit重试封套ID变化仍首次完成冻结报告")
  void rabbitRetryCompletesUsingOriginalIdentity() throws Exception {
    var report = report(30);
    when(evaluation.evaluate(eq("test"), any())).thenReturn(report);
    rabbitConsumer().consume(new InterviewEvaluationMessage(UUID.randomUUID(), session.getSessionId(), 2,
        OffsetDateTime.now(), messageId), envelope(3), mock(Channel.class));
    var saved = sessions.findBySessionId(session.getSessionId()).orElseThrow();
    assertThat(saved.getEvaluateStatus()).isEqualTo(AsyncTaskStatus.COMPLETED);
    assertThat(saved.getEvaluationMessageId()).isEqualTo(messageId.toString());
  }

  @Test
  @DisplayName("Redis解析重试持久化全程保留稳定ID并只读冻结答案")
  void redisPreservesIdentityAcrossRetryAndPersistence() throws Exception {
    var report = report(30);
    var calls = new AtomicInteger();
    when(evaluation.evaluate(eq("test"), any())).thenAnswer(call -> {
      assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
      if (calls.getAndIncrement() == 0) {
        throw new BusinessException(ErrorCode.INTERVIEW_EVALUATION_FAILED,
            "模型暂时不可用");
      }
      return report;
    });
    var redis = mock(RedisService.class);
    var consumer = redisConsumer(redis);
    var retry = new ArrayList<Map<String, String>>();
    doAnswer(call -> { retry.add(Map.copyOf(call.getArgument(1))); return null; })
        .when(redis).streamAdd(any(), any(), anyInt());
    var data = Map.of(AsyncTaskStreamConstants.FIELD_SESSION_ID, session.getSessionId(),
        "messageId", messageId.toString());
    ReflectionTestUtils.invokeMethod(consumer, "processMessage", new StreamMessageId(1, 1), data);
    assertThat(retry).hasSize(1);
    assertThat(retry.getFirst()).containsEntry("messageId", messageId.toString())
        .containsEntry(AsyncTaskStreamConstants.FIELD_RETRY_COUNT, "1");
    ReflectionTestUtils.invokeMethod(consumer, "processMessage", new StreamMessageId(2, 1), retry.getFirst());
    var saved = sessions.findBySessionId(session.getSessionId()).orElseThrow();
    assertThat(saved.getReportJson()).isNotNull();
    assertThat(saved.getEvaluationMessageId()).isEqualTo(messageId.toString());
    assertThat(saved.getOverallScore()).isEqualTo(30);
    String exactJson = saved.getReportJson();
    ReflectionTestUtils.invokeMethod(consumer, "processMessage", new StreamMessageId(3, 1), data);
    assertThat(calls.get()).isEqualTo(2);
    assertThat(sessions.findBySessionId(session.getSessionId()).orElseThrow().getReportJson())
        .isEqualTo(exactJson);
    verify(redis, times(3)).streamAck(any(), any(), any());
  }

  @Test
  @DisplayName("动态Redis缺失伪造或非法ID安全确认且不修改待评价状态")
  void redisRejectsMissingOrForgedIdentity() {
    var redis = mock(RedisService.class);
    var consumer = redisConsumer(redis);
    for (var data : List.of(Map.of(AsyncTaskStreamConstants.FIELD_SESSION_ID, session.getSessionId()),
        Map.of(AsyncTaskStreamConstants.FIELD_SESSION_ID, session.getSessionId(), "messageId", "bad-id"),
        Map.of(AsyncTaskStreamConstants.FIELD_SESSION_ID, session.getSessionId(), "messageId", UUID.randomUUID().toString()))) {
      ReflectionTestUtils.invokeMethod(consumer, "processMessage", new StreamMessageId(4, 1), data);
    }
    var saved = sessions.findBySessionId(session.getSessionId()).orElseThrow();
    assertThat(saved.getEvaluateStatus()).isEqualTo(AsyncTaskStatus.PENDING);
    assertThat(saved.getReportJson()).isNull();
    verify(redis, times(3)).streamAck(any(), any(), any());
  }

  @Test
  @DisplayName("模型执行期间真实删除会话后不会重建报告或派生")
  void deletionDuringModelCannotResurrectReport() throws Exception {
    var report = report(30);
    when(evaluation.evaluate(eq("test"), any())).thenAnswer(call -> {
      persistence.deleteSessionBySessionId(session.getSessionId());
      return report;
    });
    assertThat(orchestration.evaluate(session.getSessionId(), messageId))
        .isEqualTo(CompletionResult.SESSION_MISSING);
    assertThat(sessions.findBySessionId(session.getSessionId())).isEmpty();
  }

  @Test
  @DisplayName("详情公开冻结正式组评价且保留不会空文本与空分数语义")
  void detailExposesFormalVersionedGroups() throws Exception {
    persistence.completeDynamicReport(session.getSessionId(), messageId, scope.frozenVersion(), report(30));
    var history = new InterviewHistoryService(persistence, new PdfExportService(mapper), mapper,
        Mappers.getMapper(InterviewMapper.class), new InterviewQuestionCountService(mapper),
        new DynamicInterviewSessionSupport(answers, mapper, mock(InterviewSessionCache.class), runtimes));
    var detail = mapper.valueToTree(history.getInterviewDetail(session.getSessionId()));
    assertThat(detail.path("groupEvaluationSchemaVersion").asInt()).isEqualTo(1);
    assertThat(detail.path("frozenVersion").asLong()).isEqualTo(scope.frozenVersion());
    assertThat(detail.path("groupEvaluations").size()).isEqualTo(1);
    assertThat(detail.path("groupEvaluations").get(0).path("score").asInt()).isEqualTo(30);
    assertThat(detail.path("answers").get(0).path("submissionIntent").asString()).isEqualTo("DONT_KNOW");
    assertThat(detail.path("answers").get(0).path("userAnswer").isNull()).isTrue();
  }

  private InterviewEvaluationRabbitConsumer rabbitConsumer() {
    return new InterviewEvaluationRabbitConsumer(sessions, mock(AnswerEvaluationService.class), persistence,
        mapper, mock(LlmProviderRegistry.class), mock(InterviewEvaluationRabbitProducer.class),
        mock(InterviewEvaluationRetryPolicy.class), mock(InterviewSessionCache.class),
        memoryPublication, orchestration);
  }

  private EvaluateStreamConsumer redisConsumer(RedisService redis) {
    return new EvaluateStreamConsumer(redis, sessions, mock(AnswerEvaluationService.class), persistence,
        mapper, mock(LlmProviderRegistry.class), mock(InterviewSessionCache.class),
        memoryPublication, orchestration);
  }

  private Message envelope(long tag) {
    var properties = new MessageProperties();
    properties.setDeliveryTag(tag);
    return new Message(new byte[0], properties);
  }

  private InterviewReportDTO report(int score) throws Exception {
    return DynamicInterviewEvaluationTest.evaluator(new DynamicInterviewEvaluationTest.ScriptedModel(
        DynamicInterviewEvaluationTest.output(score, "不会基础概念"))).evaluate("test", scope);
  }
}
