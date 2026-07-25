package interview.guide.modules.knowledgebase.messaging.rabbit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(
    classes = KnowledgeVectorizationRabbitPropertiesTest.TestConfig.class,
    properties = {
        "app.knowledge-vectorization.messaging.provider=rabbitmq",
        "app.knowledge-vectorization.messaging.exchange=knowledge.vectorization.exchange",
        "app.knowledge-vectorization.messaging.retry-delays=10s,30s,60s"
    }
)
class KnowledgeVectorizationRabbitPropertiesTest {

  @Autowired
  private KnowledgeVectorizationRabbitProperties properties;

  @Test
  void shouldBindProperties() {
    assertThat(properties.provider()).isEqualTo("rabbitmq");
    assertThat(properties.exchange()).isEqualTo("knowledge.vectorization.exchange");
    assertThat(properties.retryDelays()).containsExactly(
        Duration.ofSeconds(10),
        Duration.ofSeconds(30),
        Duration.ofSeconds(60)
    );
  }

  @EnableConfigurationProperties(KnowledgeVectorizationRabbitProperties.class)
  static class TestConfig {
  }
}
