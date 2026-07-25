package interview.guide.modules.knowledgebase.messaging.rabbit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class KnowledgeVectorizationRetryPolicyTest {

  private final KnowledgeVectorizationRabbitProperties properties =
      new KnowledgeVectorizationRabbitProperties(
          "rabbitmq",
          "knowledge.vectorization.exchange",
          "knowledge.vectorization.queue",
          "knowledge.vectorization",
          "knowledge.vectorization.dead.exchange",
          "knowledge.vectorization.dead.queue",
          "knowledge.vectorization.dead",
          List.of(Duration.ofSeconds(10), Duration.ofSeconds(30), Duration.ofSeconds(60))
      );

  private final KnowledgeVectorizationRetryPolicy policy =
      new KnowledgeVectorizationRetryPolicy(properties);

  @Test
  void shouldSelectDestination() {
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
