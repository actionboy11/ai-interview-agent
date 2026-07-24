package interview.guide.modules.resume.messaging.rabbit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(
    classes = ResumeAnalysisRabbitPropertiesTest.TestConfig.class,
    properties = {
        "app.resume.messaging.provider=rabbitmq",
        "app.resume.messaging.exchange=resume.analysis.exchange",
        "app.resume.messaging.retry-delays=10s,30s,60s"
    })
class ResumeAnalysisRabbitPropertiesTest {

  @Autowired
  private ResumeAnalysisRabbitProperties properties;

  @Test
  @DisplayName("应绑定简历分析 RabbitMQ 配置")
  void shouldBindProperties() {
    assertThat(properties.provider()).isEqualTo("rabbitmq");
    assertThat(properties.exchange()).isEqualTo("resume.analysis.exchange");
    assertThat(properties.retryDelays())
        .containsExactly(Duration.ofSeconds(10), Duration.ofSeconds(30), Duration.ofSeconds(60));
  }

  @EnableConfigurationProperties(ResumeAnalysisRabbitProperties.class)
  static class TestConfig {
  }
}
