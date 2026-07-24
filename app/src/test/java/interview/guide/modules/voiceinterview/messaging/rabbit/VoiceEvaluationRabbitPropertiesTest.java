package interview.guide.modules.voiceinterview.messaging.rabbit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(
    classes = VoiceEvaluationRabbitPropertiesTest.TestConfig.class,
    properties = {
        "app.voice-evaluation.messaging.provider=rabbitmq",
        "app.voice-evaluation.messaging.exchange=voice.evaluation.exchange",
        "app.voice-evaluation.messaging.retry-delays=10s,30s,60s"
    }
)
class VoiceEvaluationRabbitPropertiesTest {

    @Autowired
    private VoiceEvaluationRabbitProperties properties;

    @Test
    void shouldBindVoiceEvaluationMessagingProperties() {
        assertThat(properties.provider()).isEqualTo("rabbitmq");
        assertThat(properties.exchange()).isEqualTo("voice.evaluation.exchange");
        assertThat(properties.retryDelays()).containsExactly(
            Duration.ofSeconds(10),
            Duration.ofSeconds(30),
            Duration.ofSeconds(60)
        );
    }

    @EnableConfigurationProperties(VoiceEvaluationRabbitProperties.class)
    static class TestConfig {
    }
}
