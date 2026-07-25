package interview.guide.modules.interview.messaging.rabbit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(
    classes = InterviewEvaluationRabbitPropertiesTest.TestConfig.class,
    properties = {
        "app.interview-evaluation.messaging.provider=rabbitmq",
        "app.interview-evaluation.messaging.exchange=interview.evaluation.exchange",
        "app.interview-evaluation.messaging.retry-delays=10s,30s,60s"
    }
)
class InterviewEvaluationRabbitPropertiesTest {

  @Autowired
  private InterviewEvaluationRabbitProperties properties;

  @Test
  @DisplayName("绑定文字评估 RabbitMQ 配置")
  void shouldBindProperties() {
    assertThat(properties.provider()).isEqualTo("rabbitmq");
    assertThat(properties.exchange()).isEqualTo("interview.evaluation.exchange");
    assertThat(properties.retryDelays()).containsExactly(
        Duration.ofSeconds(10),
        Duration.ofSeconds(30),
        Duration.ofSeconds(60)
    );
  }

  @EnableConfigurationProperties(InterviewEvaluationRabbitProperties.class)
  static class TestConfig {
  }
}
