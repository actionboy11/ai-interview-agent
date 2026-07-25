package interview.guide.modules.interview.messaging.rabbit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Declarable;
import org.springframework.amqp.core.Binding;
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
class InterviewEvaluationRabbitIntegrationTest {
    @Container
    static final RabbitMQContainer RABBIT = new RabbitMQContainer(
        DockerImageName.parse("rabbitmq:4-management")
    );

    private static CachingConnectionFactory connectionFactory;
    private static RabbitTemplate template;
    private static InterviewEvaluationRabbitProperties properties;

    @BeforeAll
    static void setUp() {
        String suffix = UUID.randomUUID().toString();
        properties = new InterviewEvaluationRabbitProperties(
            "rabbitmq",
            "interview.evaluation.test." + suffix,
            "interview.evaluation.test.queue." + suffix,
            "interview.evaluation.test",
            "interview.evaluation.test.dead." + suffix,
            "interview.evaluation.test.dead.queue." + suffix,
            "interview.evaluation.test.dead",
            List.of(Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofSeconds(3))
        );
        connectionFactory = new CachingConnectionFactory(
            RABBIT.getHost(),
            RABBIT.getAmqpPort()
        );
        connectionFactory.setUsername(RABBIT.getAdminUsername());
        connectionFactory.setPassword(RABBIT.getAdminPassword());

        InterviewEvaluationRabbitConfig config = new InterviewEvaluationRabbitConfig();
        RabbitAdmin admin = new RabbitAdmin(connectionFactory);
        var mainExchange = config.interviewEvaluationExchange(properties);
        var deadExchange = config.interviewEvaluationDeadExchange(properties);
        var mainQueue = config.interviewEvaluationQueue(properties);
        var deadQueue = config.interviewEvaluationDeadQueue(properties);
        admin.declareExchange(mainExchange);
        admin.declareExchange(deadExchange);
        admin.declareQueue(mainQueue);
        admin.declareQueue(deadQueue);
        admin.declareBinding(config.interviewEvaluationBinding(mainQueue, mainExchange, properties));
        admin.declareBinding(config.interviewEvaluationDeadBinding(deadQueue, deadExchange, properties));
        for (Declarable declarable :
            config.interviewEvaluationRetryTopology(mainExchange, properties).getDeclarables()) {
            if (declarable instanceof Queue queue) {
                admin.declareQueue(queue);
            } else if (declarable instanceof Exchange exchange) {
                admin.declareExchange(exchange);
            } else if (declarable instanceof Binding binding) {
                admin.declareBinding(binding);
            }
        }
        template = config.interviewEvaluationRabbitTemplate(
            connectionFactory,
            config.interviewEvaluationMessageConverter()
        );
    }

    @AfterAll
    static void tearDown() {
        if (connectionFactory != null) {
            connectionFactory.destroy();
        }
    }

    @Test
    void routesMainRetryAndDeadMessagesThroughRealBroker() throws Exception {
        UUID messageId = UUID.randomUUID();
        InterviewEvaluationMessage message = new InterviewEvaluationMessage(
            messageId,
            "session-42",
            0,
            OffsetDateTime.now(),
            messageId
        );
        template.convertAndSend(properties.exchange(), properties.routingKey(), message);
        assertThat(await(properties.queue())).isInstanceOf(InterviewEvaluationMessage.class);

        String retryKey = InterviewEvaluationRetryPolicy.retryRoutingKey(
            properties.routingKey(),
            Duration.ofSeconds(1)
        );
        template.convertAndSend(properties.exchange(), retryKey, message);
        assertThat(await(properties.queue())).isInstanceOf(InterviewEvaluationMessage.class);

        template.convertAndSend(
            properties.deadExchange(),
            properties.deadRoutingKey(),
            message
        );
        assertThat(await(properties.deadQueue())).isInstanceOf(InterviewEvaluationMessage.class);
    }

    private static Object await(String queue) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(6).toNanos();
        Object received;
        do {
            received = template.receiveAndConvert(queue);
            if (received != null) {
                return received;
            }
            Thread.sleep(25);
        } while (System.nanoTime() < deadline);
        return null;
    }
}
