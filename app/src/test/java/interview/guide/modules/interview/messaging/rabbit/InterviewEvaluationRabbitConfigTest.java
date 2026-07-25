package interview.guide.modules.interview.messaging.rabbit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;

class InterviewEvaluationRabbitConfigTest {

  private InterviewEvaluationRabbitConfig config;
  private InterviewEvaluationRabbitProperties properties;

  @BeforeEach
  void setUp() {
    config = new InterviewEvaluationRabbitConfig();
    properties = new InterviewEvaluationRabbitProperties(
        "rabbitmq",
        "interview.evaluation.exchange",
        "interview.evaluation.queue",
        "interview.evaluation",
        "interview.evaluation.dead.exchange",
        "interview.evaluation.dead.queue",
        "interview.evaluation.dead",
        List.of(Duration.ofSeconds(10), Duration.ofSeconds(30), Duration.ofSeconds(60))
    );
  }

  @Test
  @DisplayName("声明持久化主资源和死信资源")
  void shouldDeclareDurableResources() {
    DirectExchange main = config.interviewEvaluationExchange(properties);
    DirectExchange dead = config.interviewEvaluationDeadExchange(properties);
    Queue mainQueue = config.interviewEvaluationQueue(properties);
    Queue deadQueue = config.interviewEvaluationDeadQueue(properties);

    assertThat(main.isDurable()).isTrue();
    assertThat(dead.isDurable()).isTrue();
    assertThat(mainQueue.isDurable()).isTrue();
    assertThat(deadQueue.isDurable()).isTrue();
  }

  @Test
  @DisplayName("声明三个 TTL 重试队列并回流主交换机")
  void shouldDeclareRetryQueues() {
    Declarables topology = config.interviewEvaluationRetryTopology(
        config.interviewEvaluationExchange(properties),
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
  @DisplayName("启用 JSON 转换、mandatory 发布和手动确认")
  void shouldConfigureMessagingBoundary() {
    var converter = config.interviewEvaluationMessageConverter();
    RabbitTemplate template = config.interviewEvaluationRabbitTemplate(
        mock(ConnectionFactory.class),
        converter
    );
    var factory = config.interviewEvaluationRabbitListenerContainerFactory(
        mock(ConnectionFactory.class),
        converter
    );

    assertThat(converter).isInstanceOf(JacksonJsonMessageConverter.class);
    assertThat(template.isMandatoryFor(null)).isTrue();
    assertThat(factory.createListenerContainer().getAcknowledgeMode())
        .isEqualTo(AcknowledgeMode.MANUAL);
  }
}
