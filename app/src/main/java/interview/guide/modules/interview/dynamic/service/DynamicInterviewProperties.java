package interview.guide.modules.interview.dynamic.service;

import java.time.Duration;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

@Data
@Component
@Validated
@ConfigurationProperties(prefix = "app.interview.dynamic")
public class DynamicInterviewProperties {
  private boolean enabled = false;
  @Min(2)
  @Max(2)
  private int maxFollowUpsPerMain = 2;
  private Duration decisionTimeout = Duration.ofSeconds(30);
  @Min(1)
  private int maxAnswerLength = 20000;
  @Min(1)
  @Max(1000)
  private int deliveryBatchSize = 100;
  private Duration workLease = Duration.ofSeconds(30);
  private Duration deliveryLease = Duration.ofSeconds(10);
  private Duration deliveryRetryDelay = Duration.ofSeconds(2);

  @AssertTrue(message = "动态投递与工作租约必须为正时长")
  public boolean isDeliveryTimingValid() {
    return positive(workLease) && positive(deliveryLease) && positive(deliveryRetryDelay);
  }

  private boolean positive(Duration value) {
    return value != null && !value.isNegative() && !value.isZero();
  }

  @AssertTrue(message = "动态决策超时必须为正整数秒且不超过整数上限")
  public boolean isDecisionTimeoutValid() {
    return decisionTimeout != null && decisionTimeout.getNano() == 0
        && decisionTimeout.getSeconds() > 0 && decisionTimeout.getSeconds() <= Integer.MAX_VALUE;
  }

  public PolicySnapshot snapshot(int plannedMainQuestionCount) {
    if (maxFollowUpsPerMain != 2 || !isDecisionTimeoutValid() || plannedMainQuestionCount < 1) {
      throw new IllegalArgumentException("动态面试政策配置无效");
    }
    return new PolicySnapshot(1, maxFollowUpsPerMain, plannedMainQuestionCount,
        Math.toIntExact(decisionTimeout.getSeconds()));
  }

  public record PolicySnapshot(int schemaVersion, int maxFollowUpsPerGroup,
      int totalFollowUpBudget, int decisionTimeoutSeconds) {}
}
