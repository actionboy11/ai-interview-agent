package interview.guide.modules.interview.messaging.rabbit;

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
@EnableConfigurationProperties(InterviewEvaluationRabbitProperties.class)
@ConditionalOnProperty(
    name = "app.interview-evaluation.messaging.provider",
    havingValue = "rabbitmq",
    matchIfMissing = true
)
public class InterviewEvaluationRabbitConfig {

  @Bean
  DirectExchange interviewEvaluationExchange(
      InterviewEvaluationRabbitProperties properties
  ) {
    return new DirectExchange(properties.exchange(), true, false);
  }

  @Bean
  DirectExchange interviewEvaluationDeadExchange(
      InterviewEvaluationRabbitProperties properties
  ) {
    return new DirectExchange(properties.deadExchange(), true, false);
  }

  @Bean
  Queue interviewEvaluationQueue(InterviewEvaluationRabbitProperties properties) {
    return new Queue(properties.queue(), true);
  }

  @Bean
  Queue interviewEvaluationDeadQueue(InterviewEvaluationRabbitProperties properties) {
    return new Queue(properties.deadQueue(), true);
  }

  @Bean
  Binding interviewEvaluationBinding(
      Queue interviewEvaluationQueue,
      DirectExchange interviewEvaluationExchange,
      InterviewEvaluationRabbitProperties properties
  ) {
    return BindingBuilder.bind(interviewEvaluationQueue)
        .to(interviewEvaluationExchange)
        .with(properties.routingKey());
  }

  @Bean
  Binding interviewEvaluationDeadBinding(
      Queue interviewEvaluationDeadQueue,
      DirectExchange interviewEvaluationDeadExchange,
      InterviewEvaluationRabbitProperties properties
  ) {
    return BindingBuilder.bind(interviewEvaluationDeadQueue)
        .to(interviewEvaluationDeadExchange)
        .with(properties.deadRoutingKey());
  }

  @Bean
  Declarables interviewEvaluationRetryTopology(
      DirectExchange interviewEvaluationExchange,
      InterviewEvaluationRabbitProperties properties
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
              .to(interviewEvaluationExchange)
              .with(InterviewEvaluationRetryPolicy.retryRoutingKey(
                  properties.routingKey(),
                  delay
              ))
      );
    }
    return new Declarables(declarables);
  }

  @Bean
  MessageConverter interviewEvaluationMessageConverter() {
    return new JacksonJsonMessageConverter(
        "interview.guide.modules.interview.messaging.rabbit"
    );
  }

  @Bean
  RabbitTemplate interviewEvaluationRabbitTemplate(
      ConnectionFactory connectionFactory,
      MessageConverter interviewEvaluationMessageConverter
  ) {
    RabbitTemplate template = new RabbitTemplate(connectionFactory);
    template.setMessageConverter(interviewEvaluationMessageConverter);
    template.setMandatory(true);
    return template;
  }

  @Bean
  SimpleRabbitListenerContainerFactory interviewEvaluationRabbitListenerContainerFactory(
      ConnectionFactory connectionFactory,
      MessageConverter interviewEvaluationMessageConverter
  ) {
    SimpleRabbitListenerContainerFactory factory =
        new SimpleRabbitListenerContainerFactory();
    factory.setConnectionFactory(connectionFactory);
    factory.setMessageConverter(interviewEvaluationMessageConverter);
    factory.setAcknowledgeMode(AcknowledgeMode.MANUAL);
    return factory;
  }
}
