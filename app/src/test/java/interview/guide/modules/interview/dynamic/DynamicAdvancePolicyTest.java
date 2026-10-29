package interview.guide.modules.interview.dynamic;

import static org.assertj.core.api.Assertions.assertThat;

import interview.guide.modules.interview.dynamic.model.DynamicGroupEndReason;
import interview.guide.modules.interview.dynamic.model.SubmissionIntent;
import interview.guide.modules.interview.dynamic.service.DynamicAdvancePolicy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DynamicAdvancePolicyTest {
  private final DynamicAdvancePolicy policy = new DynamicAdvancePolicy();

  @Test
  @DisplayName("明确不会和跳过分别结束组且不请求模型")
  void dontKnowAndSkipEndGroupWithoutModel() {
    assertThat(policy.closeReason(SubmissionIntent.DONT_KNOW, 0, 0, 2, 3))
        .isEqualTo(DynamicGroupEndReason.DONT_KNOW);
    assertThat(policy.closeReason(SubmissionIntent.SKIP, 0, 0, 2, 3))
        .isEqualTo(DynamicGroupEndReason.SKIP);
  }

  @Test
  @DisplayName("每组两次和全场N次两个预算独立限制追问")
  void twoPerGroupAndNPerSession() {
    assertThat(policy.closeReason(SubmissionIntent.ANSWER, 2, 2, 2, 3))
        .isEqualTo(DynamicGroupEndReason.BUDGET_EXHAUSTED);
    assertThat(policy.closeReason(SubmissionIntent.ANSWER, 0, 3, 2, 3))
        .isEqualTo(DynamicGroupEndReason.BUDGET_EXHAUSTED);
    assertThat(policy.closeReason(SubmissionIntent.ANSWER, 1, 2, 2, 3)).isNull();
  }
}
