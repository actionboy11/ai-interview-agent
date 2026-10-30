package interview.guide.modules.interview.model;

import java.time.LocalDateTime;
import java.util.List;
import interview.guide.modules.interview.dynamic.model.InterviewExecutionMode;
import interview.guide.modules.interview.dynamic.model.SubmissionIntent;
import interview.guide.modules.interview.dynamic.model.DynamicGroupEvaluation;
import com.fasterxml.jackson.annotation.JsonInclude;

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
    Integer followUpQuestionCount,
    InterviewExecutionMode executionMode,
    Integer plannedMainQuestionCount,
    @JsonInclude(JsonInclude.Include.NON_NULL) List<DynamicGroupEvaluation> groupEvaluations,
    @JsonInclude(JsonInclude.Include.NON_NULL) Integer groupEvaluationSchemaVersion,
    @JsonInclude(JsonInclude.Include.NON_NULL) Long frozenVersion
) {
    public InterviewDetailDTO(Long id, String sessionId, Integer totalQuestions, String status,
        String evaluateStatus, String evaluateError, Integer overallScore, String overallFeedback,
        LocalDateTime createdAt, LocalDateTime completedAt, List<Object> questions,
        List<String> strengths, List<String> improvements, List<Object> referenceAnswers,
        List<AnswerDetailDTO> answers, Integer mainQuestionCount, Integer followUpQuestionCount,
        InterviewExecutionMode executionMode, Integer plannedMainQuestionCount) {
      this(id, sessionId, totalQuestions, status, evaluateStatus, evaluateError, overallScore,
          overallFeedback, createdAt, completedAt, questions, strengths, improvements,
          referenceAnswers, answers, mainQuestionCount, followUpQuestionCount, executionMode,
          plannedMainQuestionCount, null, null, null);
    }

    public InterviewDetailDTO withFormalReport(InterviewReportDTO report) {
      return new InterviewDetailDTO(id, sessionId, totalQuestions, status, evaluateStatus,
          evaluateError, overallScore, overallFeedback, createdAt, completedAt, questions,
          strengths, improvements, referenceAnswers, answers, mainQuestionCount, followUpQuestionCount,
          executionMode, plannedMainQuestionCount, report.groupEvaluations(),
          report.groupEvaluationSchemaVersion(), report.frozenVersion());
    }
    public InterviewDetailDTO(Long id, String sessionId, Integer totalQuestions, String status,
        String evaluateStatus, String evaluateError, Integer overallScore, String overallFeedback,
        LocalDateTime createdAt, LocalDateTime completedAt, List<Object> questions,
        List<String> strengths, List<String> improvements, List<Object> referenceAnswers,
        List<AnswerDetailDTO> answers, Integer mainQuestionCount, Integer followUpQuestionCount) {
      this(id, sessionId, totalQuestions, status, evaluateStatus, evaluateError, overallScore,
          overallFeedback, createdAt, completedAt, questions, strengths, improvements,
          referenceAnswers, answers, mainQuestionCount, followUpQuestionCount,
          InterviewExecutionMode.LEGACY_FIXED, null);
    }
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
        LocalDateTime answeredAt,
        SubmissionIntent submissionIntent
    ) {
      public AnswerDetailDTO(Integer questionIndex, String question, String category,
          String categoryKey, String categoryLabel, String userAnswer, Integer score,
          String feedback, String referenceAnswer, List<String> keyPoints, LocalDateTime answeredAt) {
        this(questionIndex, question, category, categoryKey, categoryLabel, userAnswer, score,
            feedback, referenceAnswer, keyPoints, answeredAt, null);
      }
    }
}

