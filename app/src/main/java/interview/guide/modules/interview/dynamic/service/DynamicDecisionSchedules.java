package interview.guide.modules.interview.dynamic.service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.Clock;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.interview.dynamic.enabled", havingValue = "true")
@RequiredArgsConstructor
@Slf4j
public class DynamicDecisionSchedules {
  private final DynamicDecisionRecoveryService recovery;
  private final DynamicDecisionDispatchService decisions;
  private final DynamicEvaluationDispatchService evaluations;
  private final Clock clock;
  private ThreadPoolTaskScheduler scheduler;

  @PostConstruct
  public void start() {
    // Private ownership avoids becoming the default scheduler for existing @Scheduled jobs.
    scheduler = new ThreadPoolTaskScheduler();
    scheduler.setPoolSize(3);
    scheduler.setThreadNamePrefix("dynamic-interview-schedule-");
    scheduler.setErrorHandler(error -> log.error("动态面试定时任务失败，等待下一轮", error));
    scheduler.initialize();
    scheduler.scheduleWithFixedDelay(() -> recovery.recover(clock.instant()), Duration.ofSeconds(1));
    scheduler.scheduleWithFixedDelay(() -> decisions.dispatch(clock.instant()), Duration.ofSeconds(2));
    scheduler.scheduleWithFixedDelay(() -> evaluations.dispatch(clock.instant()), Duration.ofSeconds(2));
  }

  @PreDestroy
  public void stop() {
    if (scheduler != null) { scheduler.shutdown(); }
  }
}
