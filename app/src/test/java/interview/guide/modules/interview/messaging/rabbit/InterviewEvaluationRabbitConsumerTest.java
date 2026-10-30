package interview.guide.modules.interview.messaging.rabbit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.rabbitmq.client.Channel;
import interview.guide.common.ai.LlmProviderRegistry;
import interview.guide.common.model.AsyncTaskStatus;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.auth.model.UserEntity;
import interview.guide.modules.auth.model.UserStatus;
import interview.guide.infrastructure.redis.InterviewSessionCache;
import interview.guide.modules.interview.model.InterviewSessionEntity;
import interview.guide.modules.interview.model.InterviewQuestionDTO;
import interview.guide.modules.interview.repository.InterviewSessionRepository;
import interview.guide.modules.interview.service.AnswerEvaluationService;
import interview.guide.modules.interview.service.InterviewPersistenceService;
import interview.guide.modules.interview.service.MemoryDerivationPublicationService;
import interview.guide.modules.interview.dynamic.service.DynamicEvaluationOrchestrationService;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.ai.chat.client.ChatClient;
import tools.jackson.databind.ObjectMapper;

class InterviewEvaluationRabbitConsumerTest {

  private InterviewSessionRepository sessionRepository;
  private InterviewPersistenceService persistenceService;
  private AnswerEvaluationService evaluationService;
  private InterviewEvaluationRabbitProducer producer;
  private MemoryDerivationPublicationService memoryPublication;
  private Channel channel;
  private InterviewEvaluationRabbitConsumer consumer;

  @BeforeEach
  void setUp() {
    sessionRepository = mock(InterviewSessionRepository.class);
    persistenceService = mock(InterviewPersistenceService.class);
    evaluationService = mock(AnswerEvaluationService.class);
    producer = mock(InterviewEvaluationRabbitProducer.class);
    memoryPublication = mock(MemoryDerivationPublicationService.class);
    channel = mock(Channel.class);
    var properties = new InterviewEvaluationRabbitProperties(
        "rabbitmq", "main", "queue", "route", "dead", "dead.queue", "dead.route",
        List.of(Duration.ofSeconds(10), Duration.ofSeconds(30), Duration.ofSeconds(60))
    );
    consumer = new InterviewEvaluationRabbitConsumer(
        sessionRepository,
        evaluationService,
        persistenceService,
        new ObjectMapper(),
        mock(LlmProviderRegistry.class),
        producer,
        new InterviewEvaluationRetryPolicy(properties),
        mock(InterviewSessionCache.class),
        memoryPublication,
        mock(DynamicEvaluationOrchestrationService.class)
    );
  }

  @Test
  @DisplayName("会话已删除时直接确认消息")
  void shouldAckMissingSession() throws Exception {
    when(sessionRepository.findBySessionIdWithResume("missing"))
        .thenReturn(Optional.empty());

    consumer.consume(message("missing", 0), envelope(7L), channel);

    verify(channel).basicAck(7L, false);
    verify(producer, never()).publishRetry(any(), any());
  }

  @Test
  @DisplayName("被禁用账号的排队评估直接确认且不调用模型")
  void disabledOwnerIsAcknowledgedWithoutModelCall() throws Exception {
    InterviewSessionEntity session = new InterviewSessionEntity();
    session.setOwner(UserEntity.builder().id(UUID.randomUUID()).status(UserStatus.DISABLED).build());
    session.setSessionId("session-42");
    session.setQuestionsJson("[]");
    when(sessionRepository.findBySessionIdWithResume("session-42"))
        .thenReturn(Optional.of(session));

    consumer.consume(message("session-42", 0), envelope(7L), channel);

    verify(channel).basicAck(7L, false);
    org.mockito.Mockito.verifyNoInteractions(evaluationService);
  }

  @Test
  @DisplayName("评估已完成时直接确认重复消息")
  void shouldAckCompletedSession() throws Exception {
    InterviewSessionEntity session = new InterviewSessionEntity();
    session.setOwner(UserEntity.builder().id(UUID.randomUUID()).status(UserStatus.ACTIVE).build());
    session.setEvaluateStatus(AsyncTaskStatus.COMPLETED);
    session.setMemoryDerivationStatus(AsyncTaskStatus.COMPLETED);
    when(sessionRepository.findBySessionIdWithResume("session-42"))
        .thenReturn(Optional.of(session));

    consumer.consume(message("session-42", 0), envelope(8L), channel);

    verify(channel).basicAck(8L, false);
    verify(persistenceService, never()).updateEvaluateStatusInternal(any(), any(), any());
  }

  @Test
  @DisplayName("已完成评估重投递时补发派生任务且不再次调用模型")
  void republishesCompletedEvaluationWithoutModelCall() throws Exception {
    InterviewSessionEntity session = new InterviewSessionEntity();
    session.setOwner(UserEntity.builder().id(UUID.randomUUID()).status(UserStatus.ACTIVE).build());
    session.setSessionId("session-42");
    session.setEvaluateStatus(AsyncTaskStatus.COMPLETED);
    when(sessionRepository.findBySessionIdWithResume("session-42"))
        .thenReturn(Optional.of(session));

    consumer.consume(message("session-42", 0), envelope(18L), channel);

    verify(memoryPublication).publishIfPending("session-42");
    verify(channel).basicAck(18L, false);
    verify(evaluationService, never()).evaluateInterview(any(), any(), any(), any());
  }

  @Test
  @DisplayName("派生消息发布失败时重入队且评估保持完成")
  void requeuesWhenDerivationPublicationFails() throws Exception {
    InterviewSessionEntity session = new InterviewSessionEntity();
    session.setOwner(UserEntity.builder().id(UUID.randomUUID()).status(UserStatus.ACTIVE).build());
    session.setSessionId("session-42");
    session.setEvaluateStatus(AsyncTaskStatus.COMPLETED);
    when(sessionRepository.findBySessionIdWithResume("session-42"))
        .thenReturn(Optional.of(session));
    doThrow(new IllegalStateException("broker unavailable"))
        .when(memoryPublication).publishIfPending("session-42");

    consumer.consume(message("session-42", 0), envelope(19L), channel);

    verify(channel).basicNack(19L, false, true);
    verify(persistenceService, never()).updateEvaluateStatusInternal(any(), any(), any());
    assertThat(session.getEvaluateStatus()).isEqualTo(AsyncTaskStatus.COMPLETED);
  }

  @Test
  @DisplayName("处理失败后确认重试发布再确认原消息")
  void shouldPublishRetryBeforeAck() throws Exception {
    InterviewSessionEntity session = new InterviewSessionEntity();
    session.setOwner(UserEntity.builder().id(UUID.randomUUID()).status(UserStatus.ACTIVE).build());
    session.setSessionId("session-42");
    session.setQuestionsJson("not-json");
    when(sessionRepository.findBySessionIdWithResume("session-42"))
        .thenReturn(Optional.of(session));

    InterviewEvaluationMessage message = message("session-42", 0);
    consumer.consume(message, envelope(9L), channel);

    verify(producer).publishRetry(eq(message), any());
    verify(channel).basicAck(9L, false);
  }

  @Test
  @DisplayName("最终失败后发布死信并确认原消息")
  void shouldPublishDeadAfterRetries() throws Exception {
    InterviewSessionEntity session = new InterviewSessionEntity();
    session.setOwner(UserEntity.builder().id(UUID.randomUUID()).status(UserStatus.ACTIVE).build());
    session.setSessionId("session-42");
    session.setQuestionsJson("not-json");
    when(sessionRepository.findBySessionIdWithResume("session-42"))
        .thenReturn(Optional.of(session));

    InterviewEvaluationMessage message = message("session-42", 3);
    consumer.consume(message, envelope(10L), channel);

    verify(producer).publishDead(eq(message), any());
    verify(channel).basicAck(10L, false);
  }

  @Test
  @DisplayName("评估题号或类别键校验失败时重试消息且不保存报告")
  void shouldNotPersistInvalidEvaluation() throws Exception {
    InterviewSessionEntity session = new InterviewSessionEntity();
    session.setOwner(UserEntity.builder().id(UUID.randomUUID()).status(UserStatus.ACTIVE).build());
    session.setSessionId("session-42");
    session.setQuestionsJson(new ObjectMapper().writeValueAsString(List.of(
        InterviewQuestionDTO.create(0, "索引？", "MYSQL", "MySQL"))));
    when(sessionRepository.findBySessionIdWithResume("session-42"))
        .thenReturn(Optional.of(session));
    when(persistenceService.findAnswersBySessionIdInternal("session-42"))
        .thenReturn(List.of());
    when(evaluationService.evaluateInterview(nullable(ChatClient.class), eq("session-42"),
        eq(""), any())).thenThrow(new BusinessException(
            ErrorCode.INTERVIEW_EVALUATION_FAILED, "categoryKey 不匹配"));
    InterviewEvaluationMessage task = message("session-42", 0);

    consumer.consume(task, envelope(11L), channel);

    verify(producer).publishRetry(eq(task), any());
    verify(persistenceService, never()).saveReport(eq("session-42"), any(), any());
    verify(channel).basicAck(11L, false);
  }

  private InterviewEvaluationMessage message(String sessionId, int retryCount) {
    UUID id = UUID.randomUUID();
    return new InterviewEvaluationMessage(
        id, sessionId, retryCount, OffsetDateTime.now(), id
    );
  }

  private Message envelope(long deliveryTag) {
    MessageProperties properties = new MessageProperties();
    properties.setDeliveryTag(deliveryTag);
    return new Message(new byte[0], properties);
  }
}
