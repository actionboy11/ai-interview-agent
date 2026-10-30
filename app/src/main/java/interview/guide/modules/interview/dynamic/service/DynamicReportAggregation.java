package interview.guide.modules.interview.dynamic.service;

import interview.guide.modules.interview.dynamic.model.DynamicGroupEvaluation;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import java.util.HashSet;
import java.util.List;

public final class DynamicReportAggregation {
  private DynamicReportAggregation() {}

  public static AggregationResult aggregate(List<DynamicGroupEvaluation> groups) {
    int sum = 0;
    int count = 0;
    var slots = new HashSet<String>();
    for (var group : groups) {
      if (group == null || group.slotId() == null || !slots.add(group.slotId())
          || (group.score() != null && (group.score() < 0 || group.score() > 100))) {
        throw new BusinessException(ErrorCode.INTERVIEW_EVALUATION_FAILED, "主问题组评分无效");
      }
      if (group.score() != null) {
        sum += group.score();
        count++;
      }
    }
    return new AggregationResult(count == 0 ? null : (int) Math.round((double) sum / count), count);
  }

  /** Used for the overall score and independently for each category's valid-group count. */
  public record AggregationResult(Integer score, int questionCount) {}
}
