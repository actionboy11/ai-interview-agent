package interview.guide.modules.knowledgebase.messaging.rabbit;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.rabbitmq.client.Channel;
import interview.guide.modules.knowledgebase.model.KnowledgeBaseEntity;
import interview.guide.modules.knowledgebase.model.VectorStatus;
import interview.guide.modules.knowledgebase.repository.KnowledgeBaseRepository;
import interview.guide.modules.knowledgebase.service.KnowledgeBaseParseService;
import interview.guide.modules.knowledgebase.service.KnowledgeBasePersistenceService;
import interview.guide.modules.knowledgebase.service.KnowledgeBaseVectorService;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;

class KnowledgeVectorizationRabbitConsumerTest {

  private final KnowledgeBaseRepository repository = mock(KnowledgeBaseRepository.class);
  private final KnowledgeBaseParseService parseService = mock(KnowledgeBaseParseService.class);
  private final KnowledgeBaseVectorService vectorService = mock(KnowledgeBaseVectorService.class);
  private final KnowledgeBasePersistenceService persistenceService =
      mock(KnowledgeBasePersistenceService.class);
  private final KnowledgeVectorizationRabbitProducer producer =
      mock(KnowledgeVectorizationRabbitProducer.class);
  private final KnowledgeVectorizationRetryPolicy retryPolicy =
      mock(KnowledgeVectorizationRetryPolicy.class);
  private final Channel channel = mock(Channel.class);
  private KnowledgeVectorizationRabbitConsumer consumer;
  private Message envelope;

  @BeforeEach
  void setUp() {
    consumer = new KnowledgeVectorizationRabbitConsumer(
        repository,
        parseService,
        vectorService,
        persistenceService,
        producer,
        retryPolicy
    );
    MessageProperties properties = new MessageProperties();
    properties.setDeliveryTag(12L);
    envelope = new Message(new byte[0], properties);
  }

  @Test
  @DisplayName("消费时从对象存储重载文件并在完成后 ACK")
  void shouldReloadVectorizeCompleteAndAck() throws Exception {
    var task = task(0);
    var knowledgeBase = knowledgeBase();
    when(repository.findById(42L)).thenReturn(Optional.of(knowledgeBase));
    when(parseService.downloadAndParseContent("kb/file.pdf", "file.pdf"))
        .thenReturn("content");
    when(vectorService.vectorizeAndStore(42L, "content")).thenReturn(3);

    consumer.consume(task, envelope, channel);

    verify(persistenceService).updateVectorStatus(42L, VectorStatus.PROCESSING, null);
    verify(persistenceService).completeVectorization(
        42L,
        3,
        task.messageId().toString()
    );
    verify(channel).basicAck(12L, false);
  }

  @Test
  @DisplayName("知识库不存在时直接 ACK 丢弃")
  void shouldAckWhenKnowledgeBaseMissing() throws Exception {
    var task = task(0);
    when(repository.findById(42L)).thenReturn(Optional.empty());

    consumer.consume(task, envelope, channel);

    verify(vectorService, never()).vectorizeAndStore(42L, "content");
    verify(channel).basicAck(12L, false);
  }

  @Test
  @DisplayName("处理失败时发布到对应重试队列后 ACK")
  void shouldRetryThenAck() throws Exception {
    var task = task(0);
    when(repository.findById(42L)).thenReturn(Optional.of(knowledgeBase()));
    when(parseService.downloadAndParseContent("kb/file.pdf", "file.pdf"))
        .thenThrow(new IllegalStateException("storage unavailable"));
    var destination = new KnowledgeVectorizationRetryPolicy.RetryDestination(
        "knowledge.vectorization.retry.10s",
        java.time.Duration.ofSeconds(10),
        false
    );
    when(retryPolicy.destinationFor(0)).thenReturn(destination);

    consumer.consume(task, envelope, channel);

    verify(producer).publishRetry(task, destination);
    verify(channel).basicAck(12L, false);
  }

  private KnowledgeVectorizationMessage task(int retryCount) {
    UUID messageId = UUID.randomUUID();
    return new KnowledgeVectorizationMessage(
        messageId,
        42L,
        retryCount,
        OffsetDateTime.now(),
        messageId
    );
  }

  private KnowledgeBaseEntity knowledgeBase() {
    var knowledgeBase = new KnowledgeBaseEntity();
    knowledgeBase.setId(42L);
    knowledgeBase.setStorageKey("kb/file.pdf");
    knowledgeBase.setOriginalFilename("file.pdf");
    knowledgeBase.setVectorStatus(VectorStatus.PENDING);
    return knowledgeBase;
  }
}
