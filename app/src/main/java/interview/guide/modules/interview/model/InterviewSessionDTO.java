package interview.guide.modules.interview.model;

import java.util.List;
import interview.guide.modules.interview.dynamic.model.InterviewExecutionMode;

/**
 * 面试会话DTO
 */
public record InterviewSessionDTO(
    String sessionId,
    String resumeText,
    int totalQuestions,
    int currentQuestionIndex,
    List<InterviewQuestionDTO> questions,
    SessionStatus status,
    Integer mainQuestionCount,
    Integer followUpQuestionCount,
    InterviewExecutionMode executionMode
) {
    public InterviewSessionDTO {
      if (executionMode == null) { executionMode = InterviewExecutionMode.LEGACY_FIXED; }
    }

    public InterviewSessionDTO(String sessionId, String resumeText, int totalQuestions,
        int currentQuestionIndex, List<InterviewQuestionDTO> questions, SessionStatus status,
        Integer mainQuestionCount, Integer followUpQuestionCount) {
      this(sessionId, resumeText, totalQuestions, currentQuestionIndex, questions, status,
          mainQuestionCount, followUpQuestionCount, InterviewExecutionMode.LEGACY_FIXED);
    }
    public InterviewSessionDTO(String sessionId, String resumeText, int totalQuestions,
        int currentQuestionIndex, List<InterviewQuestionDTO> questions, SessionStatus status) {
      this(sessionId, resumeText, totalQuestions, currentQuestionIndex, questions, status, null, null);
    }

    public enum SessionStatus {
        CREATED,      // 会话已创建
        IN_PROGRESS,  // 面试进行中
        COMPLETED,    // 面试已完成
        EVALUATED     // 已生成评估报告
    }
}
