package interview.guide.modules.interview.messaging.rabbit;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class InterviewEvaluationProviderConditionTest {

  private final ApplicationContextRunner contextRunner =
      new ApplicationContextRunner()
          .withUserConfiguration(
              InterviewEvaluationRabbitConfig.class,
              InterviewEvaluationRetryPolicy.class
          );

  @Test
  @DisplayName("Redis Stream 模式不注册 RabbitMQ 重试策略")
  void shouldDisableRabbitRetryPolicyInRedisMode() {
    contextRunner
        .withPropertyValues(
            "app.interview-evaluation.messaging.provider=redis-stream"
        )
        .run(context -> {
          assertThat(context).hasNotFailed();
          assertThat(context)
              .doesNotHaveBean(InterviewEvaluationRetryPolicy.class);
        });
  }
}
