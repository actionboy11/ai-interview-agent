package interview.guide.modules.voiceinterview.messaging.rabbit;

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
@EnableConfigurationProperties(VoiceEvaluationRabbitProperties.class)
@ConditionalOnProperty(
    name = "app.voice-evaluation.messaging.provider",
    havingValue = "rabbitmq",
    matchIfMissing = true
)
public class VoiceEvaluationRabbitConfig {
    @Bean
    DirectExchange voiceEvaluationExchange(VoiceEvaluationRabbitProperties properties) {
        return new DirectExchange(properties.exchange(), true, false);
    }

    @Bean
    DirectExchange voiceEvaluationDeadExchange(VoiceEvaluationRabbitProperties properties) {
        return new DirectExchange(properties.deadExchange(), true, false);
    }

    @Bean
    Queue voiceEvaluationQueue(VoiceEvaluationRabbitProperties properties) {
        return new Queue(properties.queue(), true);
    }

    @Bean
    Queue voiceEvaluationDeadQueue(VoiceEvaluationRabbitProperties properties) {
        return new Queue(properties.deadQueue(), true);
    }

    @Bean
    Binding voiceEvaluationBinding(
        Queue voiceEvaluationQueue,
        DirectExchange voiceEvaluationExchange,
        VoiceEvaluationRabbitProperties properties
    ) {
        return BindingBuilder.bind(voiceEvaluationQueue)
            .to(voiceEvaluationExchange)
            .with(properties.routingKey());
    }

    @Bean
    Binding voiceEvaluationDeadBinding(
        Queue voiceEvaluationDeadQueue,
        DirectExchange voiceEvaluationDeadExchange,
        VoiceEvaluationRabbitProperties properties
    ) {
        return BindingBuilder.bind(voiceEvaluationDeadQueue)
            .to(voiceEvaluationDeadExchange)
            .with(properties.deadRoutingKey());
    }

    @Bean
    Declarables voiceEvaluationRetryTopology(
        DirectExchange voiceEvaluationExchange,
        VoiceEvaluationRabbitProperties properties
    ) {
        List<Declarable> declarables = new ArrayList<>();
        for (Duration delay : properties.retryDelays()) {
            String routingKey =
                VoiceEvaluationRetryPolicy.retryRoutingKey(properties.routingKey(), delay);
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
            declarables.add(queue);
            declarables.add(
                BindingBuilder.bind(queue).to(voiceEvaluationExchange).with(routingKey)
            );
        }
        return new Declarables(declarables);
    }

    @Bean
    MessageConverter voiceEvaluationMessageConverter() {
        return new JacksonJsonMessageConverter(
            "interview.guide.modules.voiceinterview.messaging.rabbit"
        );
    }

    @Bean
    RabbitTemplate voiceEvaluationRabbitTemplate(
        ConnectionFactory connectionFactory,
        MessageConverter voiceEvaluationMessageConverter
    ) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(voiceEvaluationMessageConverter);
        template.setMandatory(true);
        return template;
    }

    @Bean
    SimpleRabbitListenerContainerFactory voiceEvaluationRabbitListenerContainerFactory(
        ConnectionFactory connectionFactory,
        MessageConverter voiceEvaluationMessageConverter
    ) {
        SimpleRabbitListenerContainerFactory factory =
            new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setMessageConverter(voiceEvaluationMessageConverter);
        factory.setAcknowledgeMode(AcknowledgeMode.MANUAL);
        return factory;
    }
}
