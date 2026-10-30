package interview.guide.modules.interview.dynamic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import interview.guide.infrastructure.redis.RedisService;
import interview.guide.modules.interview.dynamic.messaging.FollowUpDecisionConsumer;
import interview.guide.modules.interview.dynamic.messaging.FollowUpDecisionProducer;
import interview.guide.modules.interview.dynamic.service.DynamicDecisionDispatchService;
import interview.guide.modules.interview.dynamic.service.DynamicDecisionRecoveryService;
import interview.guide.modules.interview.dynamic.service.DynamicDecisionSchedules;
import interview.guide.modules.interview.dynamic.service.DynamicDecisionWorker;
import interview.guide.modules.interview.dynamic.service.DynamicEvaluationDispatchService;
import java.time.Clock;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.TaskScheduler;

@DisplayName("动态后台任务开关与隔离调度")
class DynamicDecisionSchedulingTest {
  private final DynamicDecisionRecoveryService recovery = mock(DynamicDecisionRecoveryService.class);
  private final DynamicDecisionDispatchService dispatch = mock(DynamicDecisionDispatchService.class);
  private final DynamicEvaluationDispatchService evaluations = mock(DynamicEvaluationDispatchService.class);
  private final RedisService redis = mock(RedisService.class);

  private ApplicationContextRunner runner() {
    return new ApplicationContextRunner().withUserConfiguration(DynamicDecisionSchedules.class)
        .withBean(DynamicDecisionRecoveryService.class, () -> recovery)
        .withBean(DynamicDecisionDispatchService.class, () -> dispatch)
        .withBean(DynamicEvaluationDispatchService.class, () -> evaluations)
        .withBean(Clock.class, Clock::systemUTC);
  }

  @Test
  @DisplayName("默认关闭时没有动态定时器或Stream消费者，不接触Redis")
  void disabledDoesNotStartConsumerOrSchedules() {
    runner().withUserConfiguration(FollowUpDecisionConsumer.class)
        .withBean(RedisService.class, () -> redis)
        .withBean(DynamicDecisionWorker.class, () -> mock(DynamicDecisionWorker.class))
        .withBean(FollowUpDecisionProducer.class, () -> mock(FollowUpDecisionProducer.class))
        .withPropertyValues("app.interview.dynamic.enabled=false").run(context -> {
          assertThat(context).doesNotHaveBean(DynamicDecisionSchedules.class)
              .doesNotHaveBean(FollowUpDecisionConsumer.class);
          verifyNoInteractions(redis, recovery, dispatch, evaluations);
        });
  }

  @Test
  @DisplayName("三任务独立周期运行，恢复异常后继续，关闭context后停止且不更换默认scheduler")
  void schedulerIsPrivateSurvivesFailureAndStops() throws Exception {
    var recovered = new CountDownLatch(2);
    var dispatched = new CountDownLatch(2);
    var evaluated = new CountDownLatch(2);
    var afterClose = new CountDownLatch(1);
    var closed = new AtomicBoolean();
    doAnswer(invocation -> {
      if (closed.get()) { afterClose.countDown(); }
      recovered.countDown();
      if (recovered.getCount() == 1) { throw new IllegalStateException("one failed scan"); }
      return null;
    }).when(recovery).recover(any());
    doAnswer(invocation -> {
      if (closed.get()) { afterClose.countDown(); }
      dispatched.countDown(); return null;
    }).when(dispatch).dispatch(any());
    doAnswer(invocation -> {
      if (closed.get()) { afterClose.countDown(); }
      evaluated.countDown(); return null;
    }).when(evaluations).dispatch(any());
    runner().withPropertyValues("app.interview.dynamic.enabled=true").run(context -> {
      assertThat(context).hasSingleBean(DynamicDecisionSchedules.class).doesNotHaveBean(TaskScheduler.class);
      assertThat(recovered.await(5, TimeUnit.SECONDS)).isTrue();
      assertThat(dispatched.await(5, TimeUnit.SECONDS)).isTrue();
      assertThat(evaluated.await(5, TimeUnit.SECONDS)).isTrue();
    });
    closed.set(true);
    assertThat(afterClose.await(2500, TimeUnit.MILLISECONDS)).isFalse();
  }
}
