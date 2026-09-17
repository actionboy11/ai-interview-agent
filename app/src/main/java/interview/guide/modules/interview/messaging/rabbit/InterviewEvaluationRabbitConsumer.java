package interview.guide.modules.interview.messaging.rabbit;

import com.rabbitmq.client.Channel;
import interview.guide.common.ai.LlmProviderRegistry;
import interview.guide.common.model.AsyncTaskStatus;
import interview.guide.infrastructure.redis.InterviewSessionCache;
import interview.guide.modules.interview.model.InterviewAnswerEntity;
import interview.guide.modules.interview.model.InterviewQuestionDTO;
import interview.guide.modules.interview.model.InterviewReportDTO;
import interview.guide.modules.interview.model.InterviewSessionDTO;
import interview.guide.modules.interview.model.InterviewSessionEntity;
import interview.guide.modules.interview.repository.InterviewSessionRepository;
import interview.guide.modules.interview.service.AnswerEvaluationService;
import interview.guide.modules.interview.service.InterviewPersistenceService;
import java.util.List;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Slf4j
@Component
@ConditionalOnProperty(
    name = "app.interview-evaluation.messaging.provider",
    havingValue = "rabbitmq",
    matchIfMissing = true
)
public class InterviewEvaluationRabbitConsumer {

  private final InterviewSessionRepository sessionRepository;
  private final AnswerEvaluationService evaluationService;
  private final InterviewPersistenceService persistenceService;
  private final ObjectMapper objectMapper;
  private final LlmProviderRegistry llmProviderRegistry;
  private final InterviewEvaluationRabbitProducer producer;
  private final InterviewEvaluationRetryPolicy retryPolicy;
  private final InterviewSessionCache sessionCache;

  public InterviewEvaluationRabbitConsumer(
      InterviewSessionRepository sessionRepository,
      AnswerEvaluationService evaluationService,
      InterviewPersistenceService persistenceService,
      ObjectMapper objectMapper,
      LlmProviderRegistry llmProviderRegistry,
      InterviewEvaluationRabbitProducer producer,
      InterviewEvaluationRetryPolicy retryPolicy,
      InterviewSessionCache sessionCache
  ) {
    this.sessionRepository = sessionRepository;
    this.evaluationService = evaluationService;
    this.persistenceService = persistenceService;
    this.objectMapper = objectMapper;
    this.llmProviderRegistry = llmProviderRegistry;
    this.producer = producer;
    this.retryPolicy = retryPolicy;
    this.sessionCache = sessionCache;
  }

  @RabbitListener(
      queues = "${app.interview-evaluation.messaging.queue:interview.evaluation.queue}",
      containerFactory = "interviewEvaluationRabbitListenerContainerFactory"
  )
  public void consume(
      InterviewEvaluationMessage task,
      Message envelope,
      Channel channel
  ) throws Exception {
    long deliveryTag = envelope.getMessageProperties().getDeliveryTag();
    Optional<InterviewSessionEntity> sessionOpt =
        sessionRepository.findBySessionIdWithResume(task.sessionId());
    if (sessionOpt.isEmpty()) {
      channel.basicAck(deliveryTag, false);
      return;
    }

    InterviewSessionEntity session = sessionOpt.get();
    if (session.getEvaluateStatus() == AsyncTaskStatus.COMPLETED
        || task.messageId().toString().equals(session.getEvaluationMessageId())) {
      channel.basicAck(deliveryTag, false);
      return;
    }

    try {
      persistenceService.updateEvaluateStatus(
          task.sessionId(),
          AsyncTaskStatus.PROCESSING,
          null
      );
      InterviewReportDTO report = evaluate(session);
      persistenceService.saveReport(
          task.sessionId(),
          report,
          task.messageId().toString()
      );
      syncEvaluatedStatusToCache(task.sessionId());
      channel.basicAck(deliveryTag, false);
    } catch (Exception exception) {
      routeFailure(task, deliveryTag, channel, exception);
    }
  }

  /**
   * 评估完成后同步缓存状态。缓存是派生态，同步失败不影响主流程。
   */
  private void syncEvaluatedStatusToCache(String sessionId) {
    try {
      sessionCache.updateSessionStatus(sessionId, InterviewSessionDTO.SessionStatus.EVALUATED);
    } catch (Exception e) {
      log.warn("同步评估完成状态到缓存失败: sessionId={}, error={}", sessionId, e.getMessage());
    }
  }

  private InterviewReportDTO evaluate(InterviewSessionEntity session) {
    List<InterviewQuestionDTO> questions = objectMapper.readValue(
        session.getQuestionsJson(),
        new TypeReference<>() {
        }
    );
    List<InterviewAnswerEntity> answers =
        persistenceService.findAnswersBySessionId(session.getSessionId());
    for (InterviewAnswerEntity answer : answers) {
      int index = answer.getQuestionIndex();
      if (index >= 0 && index < questions.size()) {
        questions.set(
            index,
            questions.get(index).withAnswer(answer.getUserAnswer())
        );
      }
    }

    var chatClient = llmProviderRegistry.getChatClientOrDefault(
        session.getLlmProvider()
    );
    String resumeText =
        session.getResume() == null ? "" : session.getResume().getResumeText();
    return evaluationService.evaluateInterview(
        chatClient,
        session.getSessionId(),
        resumeText,
        questions
    );
  }

  private void routeFailure(
      InterviewEvaluationMessage task,
      long deliveryTag,
      Channel channel,
      Exception failure
  ) throws Exception {
    String reason = truncate(failure.getMessage());
    var destination = retryPolicy.destinationFor(task.retryCount());
    try {
      if (destination.deadLetter()) {
        persistenceService.updateEvaluateStatus(
            task.sessionId(),
            AsyncTaskStatus.FAILED,
            reason
        );
        producer.publishDead(task, reason);
      } else {
        producer.publishRetry(task, destination);
      }
      channel.basicAck(deliveryTag, false);
    } catch (RuntimeException publishFailure) {
      log.error(
          "Failed to reroute text evaluation: sessionId={}",
          task.sessionId(),
          publishFailure
      );
      channel.basicNack(deliveryTag, false, true);
    }
  }

  private String truncate(String value) {
    if (value == null) {
      return "Unknown text evaluation failure";
    }
    return value.length() <= 500 ? value : value.substring(0, 500);
  }
}
