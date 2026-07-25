package interview.guide.modules.knowledgebase.messaging.rabbit;

import com.rabbitmq.client.Channel;
import interview.guide.modules.knowledgebase.model.VectorStatus;
import interview.guide.modules.knowledgebase.repository.KnowledgeBaseRepository;
import interview.guide.modules.knowledgebase.service.KnowledgeBaseParseService;
import interview.guide.modules.knowledgebase.service.KnowledgeBasePersistenceService;
import interview.guide.modules.knowledgebase.service.KnowledgeBaseVectorService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@ConditionalOnProperty(
    name = "app.knowledge-vectorization.messaging.provider",
    havingValue = "rabbitmq",
    matchIfMissing = true
)
public class KnowledgeVectorizationRabbitConsumer {

  private final KnowledgeBaseRepository repository;
  private final KnowledgeBaseParseService parseService;
  private final KnowledgeBaseVectorService vectorService;
  private final KnowledgeBasePersistenceService persistenceService;
  private final KnowledgeVectorizationRabbitProducer producer;
  private final KnowledgeVectorizationRetryPolicy retryPolicy;

  public KnowledgeVectorizationRabbitConsumer(
      KnowledgeBaseRepository repository,
      KnowledgeBaseParseService parseService,
      KnowledgeBaseVectorService vectorService,
      KnowledgeBasePersistenceService persistenceService,
      KnowledgeVectorizationRabbitProducer producer,
      KnowledgeVectorizationRetryPolicy retryPolicy
  ) {
    this.repository = repository;
    this.parseService = parseService;
    this.vectorService = vectorService;
    this.persistenceService = persistenceService;
    this.producer = producer;
    this.retryPolicy = retryPolicy;
  }

  @RabbitListener(
      queues = "${app.knowledge-vectorization.messaging.queue:knowledge.vectorization.queue}",
      containerFactory = "knowledgeVectorizationRabbitListenerContainerFactory"
  )
  public void consume(
      KnowledgeVectorizationMessage task,
      Message envelope,
      Channel channel
  ) throws Exception {
    long deliveryTag = envelope.getMessageProperties().getDeliveryTag();
    var knowledgeBaseOpt = repository.findById(task.knowledgeBaseId());
    if (knowledgeBaseOpt.isEmpty()) {
      channel.basicAck(deliveryTag, false);
      return;
    }
    var knowledgeBase = knowledgeBaseOpt.get();
    if (knowledgeBase.getVectorStatus() == VectorStatus.COMPLETED
        || task.messageId().toString().equals(knowledgeBase.getVectorizationMessageId())) {
      channel.basicAck(deliveryTag, false);
      return;
    }

    try {
      persistenceService.updateVectorStatus(
          task.knowledgeBaseId(),
          VectorStatus.PROCESSING,
          null
      );
      String content = parseService.downloadAndParseContent(
          knowledgeBase.getStorageKey(),
          knowledgeBase.getOriginalFilename()
      );
      if (content == null || content.isBlank()) {
        throw new IllegalStateException("知识库文件没有可向量化的文本内容");
      }
      int chunkCount = vectorService.vectorizeAndStore(task.knowledgeBaseId(), content);
      persistenceService.completeVectorization(
          task.knowledgeBaseId(),
          chunkCount,
          task.messageId().toString()
      );
      channel.basicAck(deliveryTag, false);
    } catch (Exception exception) {
      routeFailure(task, deliveryTag, channel, exception);
    }
  }

  private void routeFailure(
      KnowledgeVectorizationMessage task,
      long deliveryTag,
      Channel channel,
      Exception failure
  ) throws Exception {
    String reason = truncate(failure.getMessage());
    var destination = retryPolicy.destinationFor(task.retryCount());
    try {
      if (destination.deadLetter()) {
        persistenceService.updateVectorStatus(
            task.knowledgeBaseId(),
            VectorStatus.FAILED,
            reason
        );
        producer.publishDead(task, reason);
      } else {
        producer.publishRetry(task, destination);
      }
      channel.basicAck(deliveryTag, false);
    } catch (RuntimeException publishFailure) {
      log.error(
          "Failed to reroute knowledge vectorization: knowledgeBaseId={}",
          task.knowledgeBaseId(),
          publishFailure
      );
      channel.basicNack(deliveryTag, false, true);
    }
  }

  private String truncate(String value) {
    if (value == null) {
      return "Unknown knowledge vectorization failure";
    }
    return value.length() <= 500 ? value : value.substring(0, 500);
  }
}
