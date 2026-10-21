package interview.guide.modules.memory.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import interview.guide.modules.memory.model.AbilityLevel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("能力分数策略")
class AbilityScorePolicyTest {
  private final AbilityScorePolicy policy = new AbilityScorePolicy();

  @Test
  @DisplayName("等级边界覆盖零分、满分和每个分段的首尾")
  void mapsEveryBoundary() {
    int[] scores = {0, 39, 40, 59, 60, 74, 75, 89, 90, 100};
    AbilityLevel[] levels = {AbilityLevel.WEAK, AbilityLevel.WEAK,
        AbilityLevel.DEVELOPING, AbilityLevel.DEVELOPING,
        AbilityLevel.COMPETENT, AbilityLevel.COMPETENT,
        AbilityLevel.PROFICIENT, AbilityLevel.PROFICIENT,
        AbilityLevel.STRONG, AbilityLevel.STRONG};
    for (int index = 0; index < scores.length; index++) {
      assertThat(policy.levelFor(scores[index])).isEqualTo(levels[index]);
    }
  }

  @Test
  @DisplayName("加权分数采用整数半入舍入且首尾保持不变")
  void mergesWithDeterministicRounding() {
    assertThat(policy.mergeScore(0, 0)).isZero();
    assertThat(policy.mergeScore(100, 100)).isEqualTo(100);
    assertThat(policy.mergeScore(40, 41)).isEqualTo(40);
    assertThat(policy.mergeScore(40, 42)).isEqualTo(41);
    assertThat(policy.mergeScore(0, 5)).isEqualTo(2);
    assertThat(policy.mergeScore(5, 0)).isEqualTo(4);
    assertThat(policy.mergeScore(1, 0)).isEqualTo(1);
    assertThat(policy.mergeScore(0, 1)).isZero();
    assertThat(policy.mergeScore(79, 80)).isEqualTo(79);
  }

  @Test
  @DisplayName("等级和加权输入都拒绝范围外分数")
  void rejectsOutOfRange() {
    for (int invalid : new int[] {-1, 101}) {
      assertThatThrownBy(() -> policy.levelFor(invalid))
          .isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> policy.mergeScore(invalid, 50))
          .isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> policy.mergeScore(50, invalid))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }
}
