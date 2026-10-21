package interview.guide.modules.memory.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import interview.guide.modules.memory.messaging.rabbit.AbilityMemoryDerivationMessage;
import interview.guide.modules.memory.messaging.rabbit.AbilityMemoryRabbitConfig;
import interview.guide.modules.memory.messaging.rabbit.AbilityMemoryRabbitProperties;
import interview.guide.modules.memory.messaging.rabbit.AbilityMemoryRetryPolicy;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Declarable;
import org.springframework.amqp.core.Exchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers(disabledWithoutDocker = true)
@DisplayName("能力派生 RabbitMQ 路由")
class AbilityMemoryRabbitIntegrationTest {
  @Container
  static final RabbitMQContainer RABBIT = new RabbitMQContainer(
      DockerImageName.parse("rabbitmq:4-management"));

  private static CachingConnectionFactory connectionFactory;
  private static RabbitTemplate template;
  private static AbilityMemoryRabbitProperties properties;

  @BeforeAll
  static void setUp() {
    String suffix = UUID.randomUUID().toString();
    properties = new AbilityMemoryRabbitProperties("rabbitmq", "ability.memory.test." + suffix,
        "ability.memory.test.queue." + suffix, "ability.memory.test",
        "ability.memory.test.dead." + suffix, "ability.memory.test.dead.queue." + suffix,
        "ability.memory.test.dead", List.of(Duration.ofSeconds(1)));
    connectionFactory = new CachingConnectionFactory(RABBIT.getHost(), RABBIT.getAmqpPort());
    connectionFactory.setUsername(RABBIT.getAdminUsername());
    connectionFactory.setPassword(RABBIT.getAdminPassword());
    AbilityMemoryRabbitConfig config = new AbilityMemoryRabbitConfig();
    RabbitAdmin admin = new RabbitAdmin(connectionFactory);
    var exchange = config.abilityMemoryExchange(properties);
    var deadExchange = config.abilityMemoryDeadExchange(properties);
    var queue = config.abilityMemoryQueue(properties);
    var deadQueue = config.abilityMemoryDeadQueue(properties);
    admin.declareExchange(exchange);
    admin.declareExchange(deadExchange);
    admin.declareQueue(queue);
    admin.declareQueue(deadQueue);
    admin.declareBinding(config.abilityMemoryBinding(queue, exchange, properties));
    admin.declareBinding(config.abilityMemoryDeadBinding(deadQueue, deadExchange, properties));
    for (Declarable declarable : config.abilityMemoryRetryTopology(exchange, properties)
        .getDeclarables()) {
      if (declarable instanceof Queue retryQueue) {
        admin.declareQueue(retryQueue);
      } else if (declarable instanceof Exchange retryExchange) {
        admin.declareExchange(retryExchange);
      } else if (declarable instanceof Binding binding) {
        admin.declareBinding(binding);
      }
    }
    template = config.abilityMemoryRabbitTemplate(connectionFactory,
        config.abilityMemoryMessageConverter());
  }

  @AfterAll
  static void tearDown() {
    if (connectionFactory != null) {
      connectionFactory.destroy();
    }
  }

  @Test
  @DisplayName("主队列、延迟重试和死信均通过真实 RabbitMQ 路由")
  void routesMessages() throws Exception {
    UUID id = UUID.randomUUID();
    var message = new AbilityMemoryDerivationMessage(id, "session-42", 0,
        OffsetDateTime.now(), id);
    template.convertAndSend(properties.exchange(), properties.routingKey(), message);
    assertThat(await(properties.queue())).isInstanceOf(AbilityMemoryDerivationMessage.class);
    template.convertAndSend(properties.exchange(), AbilityMemoryRetryPolicy.retryRoutingKey(
        properties.routingKey(), Duration.ofSeconds(1)), message);
    assertThat(await(properties.queue())).isInstanceOf(AbilityMemoryDerivationMessage.class);
    template.convertAndSend(properties.deadExchange(), properties.deadRoutingKey(), message);
    assertThat(await(properties.deadQueue())).isInstanceOf(AbilityMemoryDerivationMessage.class);
  }

  private Object await(String queue) throws InterruptedException {
    long deadline = System.nanoTime() + Duration.ofSeconds(6).toNanos();
    Object item;
    do {
      item = template.receiveAndConvert(queue);
      if (item != null) {
        return item;
      }
      Thread.sleep(25);
    } while (System.nanoTime() < deadline);
    return null;
  }
}
