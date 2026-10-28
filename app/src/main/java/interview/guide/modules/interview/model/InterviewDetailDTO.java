package interview.guide.modules.interview.model;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 面试详情DTO
 */
public record InterviewDetailDTO(
    Long id,
    String sessionId,
    Integer totalQuestions,
    String status,
    String evaluateStatus,
    String evaluateError,
    Integer overallScore,
    String overallFeedback,
    LocalDateTime createdAt,
    LocalDateTime completedAt,
    List<Object> questions,
    List<String> strengths,
    List<String> improvements,
    List<Object> referenceAnswers,
    List<AnswerDetailDTO> answers,
    Integer mainQuestionCount,
    Integer followUpQuestionCount
) {
    public InterviewDetailDTO(Long id, String sessionId, Integer totalQuestions, String status,
        String evaluateStatus, String evaluateError, Integer overallScore, String overallFeedback,
        LocalDateTime createdAt, LocalDateTime completedAt, List<Object> questions,
        List<String> strengths, List<String> improvements, List<Object> referenceAnswers,
        List<AnswerDetailDTO> answers) {
      this(id, sessionId, totalQuestions, status, evaluateStatus, evaluateError, overallScore,
          overallFeedback, createdAt, completedAt, questions, strengths, improvements,
          referenceAnswers, answers, null, null);
    }

    /**
     * 答案详情DTO
     */
    public record AnswerDetailDTO(
        Integer questionIndex,
        String question,
        String category,
        String categoryKey,
        String categoryLabel,
        String userAnswer,
        Integer score,
        String feedback,
        String referenceAnswer,
        List<String> keyPoints,
        LocalDateTime answeredAt
    ) {}
}

