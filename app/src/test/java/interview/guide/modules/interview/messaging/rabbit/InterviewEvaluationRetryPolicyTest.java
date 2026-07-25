package interview.guide.modules.interview.messaging.rabbit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class InterviewEvaluationRetryPolicyTest {

  private final InterviewEvaluationRabbitProperties properties =
      new InterviewEvaluationRabbitProperties(
          "rabbitmq",
          "interview.evaluation.exchange",
          "interview.evaluation.queue",
          "interview.evaluation",
          "interview.evaluation.dead.exchange",
          "interview.evaluation.dead.queue",
          "interview.evaluation.dead",
          List.of(Duration.ofSeconds(10), Duration.ofSeconds(30), Duration.ofSeconds(60))
      );

  private final InterviewEvaluationRetryPolicy policy =
      new InterviewEvaluationRetryPolicy(properties);

  @Test
  @DisplayName("按当前失败次数选择 10、30、60 秒重试及最终死信")
  void shouldSelectDestination() {
    assertThat(policy.destinationFor(0).delay()).isEqualTo(Duration.ofSeconds(10));
    assertThat(policy.destinationFor(1).delay()).isEqualTo(Duration.ofSeconds(30));
    assertThat(policy.destinationFor(2).delay()).isEqualTo(Duration.ofSeconds(60));
    assertThat(policy.destinationFor(3).deadLetter()).isTrue();
  }

  @Test
  @DisplayName("拒绝负数重试次数")
  void shouldRejectNegativeRetryCount() {
    assertThatThrownBy(() -> policy.destinationFor(-1))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
