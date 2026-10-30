package interview.guide.modules.interview.model;

import interview.guide.common.model.AsyncTaskStatus;
import interview.guide.modules.interview.model.InterviewSessionEntity.SessionStatus;
import interview.guide.modules.interview.dynamic.model.InterviewExecutionMode;

import java.time.LocalDateTime;

/**
 * 面试会话列表项 DTO（轻量，不含题目/答案等大字段）
 */
public record SessionListItemDTO(
    String sessionId,
    String skillId,
    String difficulty,
    Long resumeId,
    int totalQuestions,
    SessionStatus status,
    AsyncTaskStatus evaluateStatus,
    String evaluateError,
    Integer overallScore,
    LocalDateTime createdAt,
    LocalDateTime completedAt,
    Integer mainQuestionCount,
    Integer followUpQuestionCount,
    InterviewExecutionMode executionMode,
    Integer plannedMainQuestionCount
) {
    public SessionListItemDTO(String sessionId, String skillId, String difficulty, Long resumeId,
        int totalQuestions, SessionStatus status, AsyncTaskStatus evaluateStatus,
        String evaluateError, Integer overallScore, LocalDateTime createdAt,
        LocalDateTime completedAt, Integer mainQuestionCount, Integer followUpQuestionCount) {
      this(sessionId, skillId, difficulty, resumeId, totalQuestions, status, evaluateStatus,
          evaluateError, overallScore, createdAt, completedAt, mainQuestionCount,
          followUpQuestionCount, InterviewExecutionMode.LEGACY_FIXED, null);
    }
    public SessionListItemDTO(String sessionId, String skillId, String difficulty, Long resumeId,
        int totalQuestions, SessionStatus status, AsyncTaskStatus evaluateStatus,
        String evaluateError, Integer overallScore, LocalDateTime createdAt,
        LocalDateTime completedAt) {
      this(sessionId, skillId, difficulty, resumeId, totalQuestions, status, evaluateStatus,
          evaluateError, overallScore, createdAt, completedAt, null, null);
    }

    public static SessionListItemDTO from(InterviewSessionEntity e) {
        return new SessionListItemDTO(
            e.getSessionId(),
            e.getSkillId(),
            e.getDifficulty(),
            e.getResumeId(),
            e.getTotalQuestions() != null ? e.getTotalQuestions() : 0,
            e.getStatus(),
            e.getEvaluateStatus(),
            e.getEvaluateError(),
            e.getOverallScore(),
            e.getCreatedAt(),
            e.getCompletedAt()
        );
    }
}
