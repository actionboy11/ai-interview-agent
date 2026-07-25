package interview.guide.modules.knowledgebase.messaging.rabbit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;

class KnowledgeVectorizationRabbitConfigTest {

  private KnowledgeVectorizationRabbitConfig config;
  private KnowledgeVectorizationRabbitProperties properties;

  @BeforeEach
  void setUp() {
    config = new KnowledgeVectorizationRabbitConfig();
    properties = new KnowledgeVectorizationRabbitProperties(
        "rabbitmq",
        "knowledge.vectorization.exchange",
        "knowledge.vectorization.queue",
        "knowledge.vectorization",
        "knowledge.vectorization.dead.exchange",
        "knowledge.vectorization.dead.queue",
        "knowledge.vectorization.dead",
        List.of(Duration.ofSeconds(10), Duration.ofSeconds(30), Duration.ofSeconds(60))
    );
  }

  @Test
  void shouldDeclareDurableResources() {
    DirectExchange main = config.knowledgeVectorizationExchange(properties);
    DirectExchange dead = config.knowledgeVectorizationDeadExchange(properties);
    Queue mainQueue = config.knowledgeVectorizationQueue(properties);
    Queue deadQueue = config.knowledgeVectorizationDeadQueue(properties);

    assertThat(main.isDurable()).isTrue();
    assertThat(dead.isDurable()).isTrue();
    assertThat(mainQueue.isDurable()).isTrue();
    assertThat(deadQueue.isDurable()).isTrue();
  }

  @Test
  void shouldDeclareRetryQueues() {
    Declarables topology = config.knowledgeVectorizationRetryTopology(
        config.knowledgeVectorizationExchange(properties),
        properties
    );
    List<Queue> queues = topology.getDeclarablesByType(Queue.class);

    assertThat(queues).hasSize(3);
    assertThat(queues)
        .extracting(queue -> queue.getArguments().get("x-message-ttl"))
        .containsExactly(10_000L, 30_000L, 60_000L);
    assertThat(queues).allSatisfy(queue -> {
      assertThat(queue.getArguments().get("x-dead-letter-exchange"))
          .isEqualTo(properties.exchange());
      assertThat(queue.getArguments().get("x-dead-letter-routing-key"))
          .isEqualTo(properties.routingKey());
    });
  }

  @Test
  void shouldConfigureMessagingBoundary() {
    var converter = config.knowledgeVectorizationMessageConverter();
    RabbitTemplate template = config.knowledgeVectorizationRabbitTemplate(
        mock(ConnectionFactory.class),
        converter
    );
    var factory = config.knowledgeVectorizationRabbitListenerContainerFactory(
        mock(ConnectionFactory.class),
        converter
    );

    assertThat(converter).isInstanceOf(JacksonJsonMessageConverter.class);
    assertThat(template.isMandatoryFor(null)).isTrue();
    assertThat(factory.createListenerContainer().getAcknowledgeMode())
        .isEqualTo(AcknowledgeMode.MANUAL);
  }
}
