package interview.guide.modules.interview.messaging.rabbit;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.rabbitmq.client.Channel;
import interview.guide.common.ai.LlmProviderRegistry;
import interview.guide.common.model.AsyncTaskStatus;
import interview.guide.infrastructure.redis.InterviewSessionCache;
import interview.guide.modules.interview.model.InterviewSessionEntity;
import interview.guide.modules.interview.repository.InterviewSessionRepository;
import interview.guide.modules.interview.service.AnswerEvaluationService;
import interview.guide.modules.interview.service.InterviewPersistenceService;
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
import tools.jackson.databind.ObjectMapper;

class InterviewEvaluationRabbitConsumerTest {

  private InterviewSessionRepository sessionRepository;
  private InterviewPersistenceService persistenceService;
  private InterviewEvaluationRabbitProducer producer;
  private Channel channel;
  private InterviewEvaluationRabbitConsumer consumer;

  @BeforeEach
  void setUp() {
    sessionRepository = mock(InterviewSessionRepository.class);
    persistenceService = mock(InterviewPersistenceService.class);
    producer = mock(InterviewEvaluationRabbitProducer.class);
    channel = mock(Channel.class);
    var properties = new InterviewEvaluationRabbitProperties(
        "rabbitmq", "main", "queue", "route", "dead", "dead.queue", "dead.route",
        List.of(Duration.ofSeconds(10), Duration.ofSeconds(30), Duration.ofSeconds(60))
    );
    consumer = new InterviewEvaluationRabbitConsumer(
        sessionRepository,
        mock(AnswerEvaluationService.class),
        persistenceService,
        new ObjectMapper(),
        mock(LlmProviderRegistry.class),
        producer,
        new InterviewEvaluationRetryPolicy(properties),
        mock(InterviewSessionCache.class)
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
  @DisplayName("评估已完成时直接确认重复消息")
  void shouldAckCompletedSession() throws Exception {
    InterviewSessionEntity session = new InterviewSessionEntity();
    session.setEvaluateStatus(AsyncTaskStatus.COMPLETED);
    when(sessionRepository.findBySessionIdWithResume("session-42"))
        .thenReturn(Optional.of(session));

    consumer.consume(message("session-42", 0), envelope(8L), channel);

    verify(channel).basicAck(8L, false);
    verify(persistenceService, never()).updateEvaluateStatus(any(), any(), any());
  }

  @Test
  @DisplayName("处理失败后确认重试发布再确认原消息")
  void shouldPublishRetryBeforeAck() throws Exception {
    InterviewSessionEntity session = new InterviewSessionEntity();
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
    session.setSessionId("session-42");
    session.setQuestionsJson("not-json");
    when(sessionRepository.findBySessionIdWithResume("session-42"))
        .thenReturn(Optional.of(session));

    InterviewEvaluationMessage message = message("session-42", 3);
    consumer.consume(message, envelope(10L), channel);

    verify(producer).publishDead(eq(message), any());
    verify(channel).basicAck(10L, false);
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
