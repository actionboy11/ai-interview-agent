package interview.guide.modules.memory.messaging.rabbit;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarable;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AbilityMemoryRabbitProperties.class)
@ConditionalOnProperty(name = "app.ability-memory.messaging.provider", havingValue = "rabbitmq",
    matchIfMissing = true)
public class AbilityMemoryRabbitConfig {
  @Bean
  public DirectExchange abilityMemoryExchange(AbilityMemoryRabbitProperties properties) {
    return new DirectExchange(properties.exchange(), true, false);
  }

  @Bean
  public DirectExchange abilityMemoryDeadExchange(AbilityMemoryRabbitProperties properties) {
    return new DirectExchange(properties.deadExchange(), true, false);
  }

  @Bean
  public Queue abilityMemoryQueue(AbilityMemoryRabbitProperties properties) {
    return new Queue(properties.queue(), true);
  }

  @Bean
  public Queue abilityMemoryDeadQueue(AbilityMemoryRabbitProperties properties) {
    return new Queue(properties.deadQueue(), true);
  }

  @Bean
  public Binding abilityMemoryBinding(Queue abilityMemoryQueue,
      DirectExchange abilityMemoryExchange, AbilityMemoryRabbitProperties properties) {
    return BindingBuilder.bind(abilityMemoryQueue).to(abilityMemoryExchange)
        .with(properties.routingKey());
  }

  @Bean
  public Binding abilityMemoryDeadBinding(Queue abilityMemoryDeadQueue,
      DirectExchange abilityMemoryDeadExchange, AbilityMemoryRabbitProperties properties) {
    return BindingBuilder.bind(abilityMemoryDeadQueue).to(abilityMemoryDeadExchange)
        .with(properties.deadRoutingKey());
  }

  @Bean
  public Declarables abilityMemoryRetryTopology(DirectExchange abilityMemoryExchange,
      AbilityMemoryRabbitProperties properties) {
    List<Declarable> declarables = new ArrayList<>();
    for (Duration delay : properties.retryDelays()) {
      Queue queue = new Queue(properties.queue() + ".retry." + delay.toSeconds() + "s",
          true, false, false, Map.of("x-message-ttl", delay.toMillis(),
              "x-dead-letter-exchange", properties.exchange(),
              "x-dead-letter-routing-key", properties.routingKey()));
      declarables.add(queue);
      declarables.add(BindingBuilder.bind(queue).to(abilityMemoryExchange)
          .with(AbilityMemoryRetryPolicy.retryRoutingKey(properties.routingKey(), delay)));
    }
    return new Declarables(declarables);
  }

  @Bean
  public MessageConverter abilityMemoryMessageConverter() {
    return new JacksonJsonMessageConverter("interview.guide.modules.memory.messaging.rabbit");
  }

  @Bean
  public RabbitTemplate abilityMemoryRabbitTemplate(ConnectionFactory connectionFactory,
      MessageConverter abilityMemoryMessageConverter) {
    RabbitTemplate template = new RabbitTemplate(connectionFactory);
    template.setMessageConverter(abilityMemoryMessageConverter);
    template.setMandatory(true);
    return template;
  }

  @Bean
  public SimpleRabbitListenerContainerFactory abilityMemoryRabbitListenerContainerFactory(
      ConnectionFactory connectionFactory, MessageConverter abilityMemoryMessageConverter) {
    SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
    factory.setConnectionFactory(connectionFactory);
    factory.setMessageConverter(abilityMemoryMessageConverter);
    factory.setAcknowledgeMode(AcknowledgeMode.MANUAL);
    return factory;
  }
}
