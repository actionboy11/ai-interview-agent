package interview.guide.modules.knowledgebase.messaging.rabbit;

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
@EnableConfigurationProperties(KnowledgeVectorizationRabbitProperties.class)
@ConditionalOnProperty(
    name = "app.knowledge-vectorization.messaging.provider",
    havingValue = "rabbitmq",
    matchIfMissing = true
)
public class KnowledgeVectorizationRabbitConfig {

  @Bean
  DirectExchange knowledgeVectorizationExchange(
      KnowledgeVectorizationRabbitProperties properties
  ) {
    return new DirectExchange(properties.exchange(), true, false);
  }

  @Bean
  DirectExchange knowledgeVectorizationDeadExchange(
      KnowledgeVectorizationRabbitProperties properties
  ) {
    return new DirectExchange(properties.deadExchange(), true, false);
  }

  @Bean
  Queue knowledgeVectorizationQueue(KnowledgeVectorizationRabbitProperties properties) {
    return new Queue(properties.queue(), true);
  }

  @Bean
  Queue knowledgeVectorizationDeadQueue(KnowledgeVectorizationRabbitProperties properties) {
    return new Queue(properties.deadQueue(), true);
  }

  @Bean
  Binding knowledgeVectorizationBinding(
      Queue knowledgeVectorizationQueue,
      DirectExchange knowledgeVectorizationExchange,
      KnowledgeVectorizationRabbitProperties properties
  ) {
    return BindingBuilder.bind(knowledgeVectorizationQueue)
        .to(knowledgeVectorizationExchange)
        .with(properties.routingKey());
  }

  @Bean
  Binding knowledgeVectorizationDeadBinding(
      Queue knowledgeVectorizationDeadQueue,
      DirectExchange knowledgeVectorizationDeadExchange,
      KnowledgeVectorizationRabbitProperties properties
  ) {
    return BindingBuilder.bind(knowledgeVectorizationDeadQueue)
        .to(knowledgeVectorizationDeadExchange)
        .with(properties.deadRoutingKey());
  }

  @Bean
  Declarables knowledgeVectorizationRetryTopology(
      DirectExchange knowledgeVectorizationExchange,
      KnowledgeVectorizationRabbitProperties properties
  ) {
    List<Declarable> declarables = new ArrayList<>();
    for (Duration delay : properties.retryDelays()) {
      String suffix = delay.toSeconds() + "s";
      Queue queue = new Queue(
          properties.queue() + ".retry." + suffix,
          true,
          false,
          false,
          Map.of(
              "x-message-ttl", delay.toMillis(),
              "x-dead-letter-exchange", properties.exchange(),
              "x-dead-letter-routing-key", properties.routingKey()
          )
      );
      declarables.add(queue);
      declarables.add(
          BindingBuilder.bind(queue)
              .to(knowledgeVectorizationExchange)
              .with(KnowledgeVectorizationRetryPolicy.retryRoutingKey(
                  properties.routingKey(),
                  delay
              ))
      );
    }
    return new Declarables(declarables);
  }

  @Bean
  MessageConverter knowledgeVectorizationMessageConverter() {
    return new JacksonJsonMessageConverter(
        "interview.guide.modules.knowledgebase.messaging.rabbit"
    );
  }

  @Bean
  RabbitTemplate knowledgeVectorizationRabbitTemplate(
      ConnectionFactory connectionFactory,
      MessageConverter knowledgeVectorizationMessageConverter
  ) {
    RabbitTemplate template = new RabbitTemplate(connectionFactory);
    template.setMessageConverter(knowledgeVectorizationMessageConverter);
    template.setMandatory(true);
    return template;
  }

  @Bean
  SimpleRabbitListenerContainerFactory knowledgeVectorizationRabbitListenerContainerFactory(
      ConnectionFactory connectionFactory,
      MessageConverter knowledgeVectorizationMessageConverter
  ) {
    SimpleRabbitListenerContainerFactory factory =
        new SimpleRabbitListenerContainerFactory();
    factory.setConnectionFactory(connectionFactory);
    factory.setMessageConverter(knowledgeVectorizationMessageConverter);
    factory.setAcknowledgeMode(AcknowledgeMode.MANUAL);
    return factory;
  }
}
