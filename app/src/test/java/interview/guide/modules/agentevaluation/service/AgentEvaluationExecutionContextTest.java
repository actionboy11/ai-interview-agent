package interview.guide.modules.agentevaluation.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Agent 评测执行上下文")
class AgentEvaluationExecutionContextTest {

  private final AgentEvaluationExecutionContext context = new AgentEvaluationExecutionContext();

  @Test
  @DisplayName("正常结束后清理当前阶段")
  void shouldClearContextAfterSuccess() {
    StageContext stage = new StageContext("run-1", "case-1", "stage-1");

    String value = context.runWithStage(stage, () -> {
      assertThat(context.current()).contains(stage);
      return "done";
    });

    assertThat(value).isEqualTo("done");
    assertThat(context.current()).isEmpty();
  }

  @Test
  @DisplayName("业务异常后仍清理当前阶段")
  void shouldClearContextAfterFailure() {
    StageContext stage = new StageContext("run-1", "case-1", "stage-1");

    assertThatThrownBy(() -> context.runWithStage(stage, () -> {
      throw new IllegalStateException("boom");
    })).isInstanceOf(IllegalStateException.class);

    assertThat(context.current()).isEmpty();
  }

  @Test
  @DisplayName("嵌套执行结束后恢复父阶段")
  void shouldRestoreParentContextAfterNestedScope() {
    StageContext parent = new StageContext("run-1", "case-1", "stage-parent");
    StageContext child = new StageContext("run-1", "case-1", "stage-child");

    context.runWithStage(parent, () -> {
      context.runWithStage(child, () -> {
        assertThat(context.current()).contains(child);
        return null;
      });
      assertThat(context.current()).contains(parent);
      return null;
    });

    assertThat(context.current()).isEmpty();
  }
}
