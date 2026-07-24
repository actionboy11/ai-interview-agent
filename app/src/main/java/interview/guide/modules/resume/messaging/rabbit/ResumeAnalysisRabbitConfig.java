package interview.guide.modules.resume.messaging.rabbit;

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
@EnableConfigurationProperties(ResumeAnalysisRabbitProperties.class)
@ConditionalOnProperty(
    name = "app.resume.messaging.provider",
    havingValue = "rabbitmq"
)
public class ResumeAnalysisRabbitConfig {

    @Bean
    DirectExchange resumeAnalysisExchange(ResumeAnalysisRabbitProperties properties) {
        return new DirectExchange(properties.exchange(), true, false);
    }

    @Bean
    DirectExchange resumeAnalysisDeadExchange(ResumeAnalysisRabbitProperties properties) {
        return new DirectExchange(properties.deadExchange(), true, false);
    }

    @Bean
    Queue resumeAnalysisQueue(ResumeAnalysisRabbitProperties properties) {
        return new Queue(properties.queue(), true);
    }

    @Bean
    Queue resumeAnalysisDeadQueue(ResumeAnalysisRabbitProperties properties) {
        return new Queue(properties.deadQueue(), true);
    }

    @Bean
    Binding resumeAnalysisBinding(
        Queue resumeAnalysisQueue,
        DirectExchange resumeAnalysisExchange,
        ResumeAnalysisRabbitProperties properties
    ) {
        return BindingBuilder.bind(resumeAnalysisQueue)
            .to(resumeAnalysisExchange)
            .with(properties.routingKey());
    }

    @Bean
    Binding resumeAnalysisDeadBinding(
        Queue resumeAnalysisDeadQueue,
        DirectExchange resumeAnalysisDeadExchange,
        ResumeAnalysisRabbitProperties properties
    ) {
        return BindingBuilder.bind(resumeAnalysisDeadQueue)
            .to(resumeAnalysisDeadExchange)
            .with(properties.deadRoutingKey());
    }

    @Bean
    Declarables resumeAnalysisRetryTopology(
        DirectExchange resumeAnalysisExchange,
        ResumeAnalysisRabbitProperties properties
    ) {
        List<Declarable> declarables = new ArrayList<>();
        for (Duration delay : properties.retryDelays()) {
            String routingKey =
                ResumeAnalysisRetryPolicy.retryRoutingKey(properties.routingKey(), delay);
            Queue queue = new Queue(
                properties.queue() + ".retry." + delay.toSeconds() + "s",
                true,
                false,
                false,
                Map.of(
                    "x-message-ttl", delay.toMillis(),
                    "x-dead-letter-exchange", properties.exchange(),
                    "x-dead-letter-routing-key", properties.routingKey()
                )
            );
            Binding binding = BindingBuilder.bind(queue)
                .to(resumeAnalysisExchange)
                .with(routingKey);
            declarables.add(queue);
            declarables.add(binding);
        }
        return new Declarables(declarables);
    }

    @Bean
    MessageConverter resumeAnalysisMessageConverter() {
        return new JacksonJsonMessageConverter();
    }

    @Bean
    RabbitTemplate resumeAnalysisRabbitTemplate(
        ConnectionFactory connectionFactory,
        MessageConverter resumeAnalysisMessageConverter
    ) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(resumeAnalysisMessageConverter);
        template.setMandatory(true);
        return template;
    }

    @Bean
    SimpleRabbitListenerContainerFactory resumeAnalysisRabbitListenerContainerFactory(
        ConnectionFactory connectionFactory,
        MessageConverter resumeAnalysisMessageConverter
    ) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setMessageConverter(resumeAnalysisMessageConverter);
        factory.setAcknowledgeMode(AcknowledgeMode.MANUAL);
        return factory;
    }
}
