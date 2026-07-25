package interview.guide.modules.interview.messaging.rabbit;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.rabbitmq.client.Channel;
import interview.guide.modules.interview.deadletter.InterviewEvaluationDeadLetterService;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;

class InterviewEvaluationDeadLetterConsumerTest {

  @Test
  @DisplayName("死信持久化成功后手动确认")
  void shouldAckAfterAuditPersistence() throws Exception {
    InterviewEvaluationDeadLetterService service =
        mock(InterviewEvaluationDeadLetterService.class);
    var consumer = new InterviewEvaluationDeadLetterConsumer(service);
    UUID id = UUID.randomUUID();
    var task = new InterviewEvaluationMessage(
        id, "session-42", 3, OffsetDateTime.now(), id
    );
    MessageProperties properties = new MessageProperties();
    properties.setDeliveryTag(9L);
    properties.setHeader("x-interview-evaluation-failure", "AI unavailable");
    Message delivery = new Message(new byte[0], properties);
    Channel channel = mock(Channel.class);

    consumer.consume(task, delivery, channel);

    verify(service).record(task, "AI unavailable");
    verify(channel).basicAck(9L, false);
  }
}
