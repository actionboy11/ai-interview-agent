package interview.guide.modules.interview.model;

import java.time.LocalDateTime;

/**
 * 面试历史列表项 DTO（用于简历详情页展示关联面试记录）
 */
public record InterviewHistoryItemDTO(
    Long id,
    String sessionId,
    Integer totalQuestions,
    String status,
    String evaluateStatus,
    String evaluateError,
    Integer overallScore,
    LocalDateTime createdAt,
    LocalDateTime completedAt,
    Integer mainQuestionCount,
    Integer followUpQuestionCount
) {
  public InterviewHistoryItemDTO(Long id, String sessionId, Integer totalQuestions, String status,
      String evaluateStatus, String evaluateError, Integer overallScore, LocalDateTime createdAt,
      LocalDateTime completedAt) {
    this(id, sessionId, totalQuestions, status, evaluateStatus, evaluateError, overallScore,
        createdAt, completedAt, null, null);
  }
}
