package interview.guide.modules.voiceinterview.messaging.rabbit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class VoiceEvaluationRetryPolicyTest {

    private final VoiceEvaluationRabbitProperties properties =
        new VoiceEvaluationRabbitProperties(
            "rabbitmq",
            "voice.evaluation.exchange",
            "voice.evaluation.queue",
            "voice.evaluation",
            "voice.evaluation.dead.exchange",
            "voice.evaluation.dead.queue",
            "voice.evaluation.dead",
            List.of(Duration.ofSeconds(10), Duration.ofSeconds(30), Duration.ofSeconds(60))
        );

    private final VoiceEvaluationRetryPolicy policy = new VoiceEvaluationRetryPolicy(properties);

    @Test
    void shouldSelectRetryDelayByCurrentRetryCount() {
        assertThat(policy.destinationFor(0).delay()).isEqualTo(Duration.ofSeconds(10));
        assertThat(policy.destinationFor(1).delay()).isEqualTo(Duration.ofSeconds(30));
        assertThat(policy.destinationFor(2).delay()).isEqualTo(Duration.ofSeconds(60));
        assertThat(policy.destinationFor(3).deadLetter()).isTrue();
    }

    @Test
    void shouldRejectNegativeRetryCount() {
        assertThatThrownBy(() -> policy.destinationFor(-1))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
