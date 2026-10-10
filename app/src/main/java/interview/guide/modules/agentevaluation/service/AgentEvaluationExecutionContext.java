package interview.guide.modules.agentevaluation.service;

import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.function.Supplier;

@Component
public class AgentEvaluationExecutionContext {

  private final ThreadLocal<StageContext> currentStage = new ThreadLocal<>();

  public Optional<StageContext> current() {
    return Optional.ofNullable(currentStage.get());
  }

  public <T> T runWithStage(StageContext stageContext, Supplier<T> operation) {
    StageContext previous = currentStage.get();
    currentStage.set(stageContext);
    try {
      return operation.get();
    } finally {
      if (previous == null) {
        currentStage.remove();
      } else {
        currentStage.set(previous);
      }
    }
  }
}
