package interview.guide.modules.voiceinterview.controller;

import static org.assertj.core.api.Assertions.assertThat;

import interview.guide.modules.voiceinterview.listener.VoiceEvaluateStreamProducer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class VoiceInterviewControllerContextTest {

  @Test
  @DisplayName("RabbitMQ 模式下控制器不依赖 Redis Stream 生产者")
  void shouldNotRequireRedisStreamProducer() {
    assertThat(VoiceInterviewController.class.getDeclaredConstructors())
        .allSatisfy(constructor ->
            assertThat(constructor.getParameterTypes())
                .doesNotContain(VoiceEvaluateStreamProducer.class));
  }
}
