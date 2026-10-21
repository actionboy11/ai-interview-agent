package interview.guide.modules.memory.service;

import interview.guide.modules.memory.model.AbilityLevel;
import org.springframework.stereotype.Component;

@Component
public class AbilityScorePolicy {
  public AbilityLevel levelFor(int score) {
    requireScore(score);
    if (score < 40) {
      return AbilityLevel.WEAK;
    }
    if (score < 60) {
      return AbilityLevel.DEVELOPING;
    }
    if (score < 75) {
      return AbilityLevel.COMPETENT;
    }
    if (score < 90) {
      return AbilityLevel.PROFICIENT;
    }
    return AbilityLevel.STRONG;
  }

  public int mergeScore(int oldScore, int sessionScore) {
    requireScore(oldScore);
    requireScore(sessionScore);
    // Scores are nonnegative; +50 implements half-up rounding without floating point drift.
    return (oldScore * 70 + sessionScore * 30 + 50) / 100;
  }

  private void requireScore(int score) {
    if (score < 0 || score > 100) {
      throw new IllegalArgumentException("score must be between 0 and 100");
    }
  }
}
