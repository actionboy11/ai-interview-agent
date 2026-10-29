package interview.guide.common.ai;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import java.time.Clock;
import java.time.Instant;
import java.time.Duration;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;

/** Bounds local waiting and queue growth. Cancellation cannot retract a remote HTTP request. */
@Component
public class StructuredOutputDeadlineExecutor implements AutoCloseable {
  private final ThreadPoolExecutor executor;
  private final Clock clock;
  private final ThreadLocal<AtomicBoolean> cancellation = new ThreadLocal<>();

  public StructuredOutputDeadlineExecutor(StructuredOutputProperties properties, Clock clock) {
    if (properties.getStructuredDeadlineThreads() < 1
        || properties.getStructuredDeadlineQueueCapacity() < 1) {
      throw new IllegalArgumentException("结构化deadline线程数及队列容量必须为正数");
    }
    this.clock = clock;
    var sequence = new AtomicInteger();
    this.executor = new ThreadPoolExecutor(properties.getStructuredDeadlineThreads(),
        properties.getStructuredDeadlineThreads(), 0, TimeUnit.MILLISECONDS,
        new ArrayBlockingQueue<>(properties.getStructuredDeadlineQueueCapacity()), task -> {
          var thread = new Thread(task, "structured-output-deadline-" + sequence.incrementAndGet());
          thread.setDaemon(true);
          return thread;
        }, new ThreadPoolExecutor.AbortPolicy());
  }

  public <T> T execute(Callable<T> task, Instant deadline, ErrorCode errorCode, String prefix) {
    checkDeadline(deadline, errorCode, prefix);
    var cancelled = new AtomicBoolean();
    var future = new FutureTask<T>(() -> {
      cancellation.set(cancelled);
      try {
        checkDeadline(deadline, errorCode, prefix);
        T value = task.call();
        checkDeadline(deadline, errorCode, prefix);
        return value;
      } finally { cancellation.remove(); }
    });
    try {
      executor.execute(future);
      T result = future.get(Math.max(1, Duration.between(clock.instant(), deadline).toNanos()),
          TimeUnit.NANOSECONDS);
      checkDeadline(deadline, errorCode, prefix);
      return result;
    } catch (TimeoutException exception) {
      throw new BusinessException(errorCode, prefix + "决策deadline已耗尽");
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new BusinessException(errorCode, prefix + "等待已中断");
    } catch (RejectedExecutionException exception) {
      throw new BusinessException(errorCode, prefix + "有界执行队列已满或已关闭");
    } catch (ExecutionException exception) {
      if (exception.getCause() instanceof BusinessException businessException) {
        throw businessException;
      }
      throw new BusinessException(errorCode, prefix + "结构化调用失败");
    } finally {
      // remove avoids cancelled-but-not-yet-dequeued entries consuming scarce queue capacity.
      if (!future.isDone()) { cancelled.set(true); future.cancel(true); }
      executor.remove(future);
    }
  }

  void checkDeadline(Instant deadline, ErrorCode errorCode, String prefix) {
    if (deadline == null || expired(deadline)) {
      throw new BusinessException(errorCode, prefix + "决策deadline已耗尽或等待已取消");
    }
  }

  boolean expired(Instant deadline) {
    return Thread.currentThread().isInterrupted()
        || (cancellation.get() != null && cancellation.get().get())
        || !clock.instant().isBefore(deadline);
  }

  @PreDestroy
  @Override public void close() { executor.shutdownNow(); }
}
