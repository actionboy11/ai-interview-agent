package interview.guide.modules.resume.messaging.rabbit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class ResumeAnalysisRetryPolicyTest {

    private final ResumeAnalysisRabbitProperties properties =
        new ResumeAnalysisRabbitProperties(
            "rabbitmq",
            "resume.analysis.exchange",
            "resume.analysis.queue",
            "resume.analysis",
            "resume.analysis.dead.exchange",
            "resume.analysis.dead.queue",
            "resume.analysis.dead",
            List.of(Duration.ofSeconds(10), Duration.ofSeconds(30), Duration.ofSeconds(60))
        );

    private final ResumeAnalysisRetryPolicy policy = new ResumeAnalysisRetryPolicy(properties);

    @Test
    void shouldSelectRetryDelayByCurrentRetryCount() {
        assertThat(policy.destinationFor(0).delay()).isEqualTo(Duration.ofSeconds(10));
        assertThat(policy.destinationFor(1).delay()).isEqualTo(Duration.ofSeconds(30));
        assertThat(policy.destinationFor(2).delay()).isEqualTo(Duration.ofSeconds(60));
    }

    @Test
    void shouldSelectDeadLetterAfterAllRetries() {
        assertThat(policy.destinationFor(3).deadLetter()).isTrue();
    }

    @Test
    void shouldRejectNegativeRetryCount() {
        assertThatThrownBy(() -> policy.destinationFor(-1))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
