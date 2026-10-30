package interview.guide.modules.interview.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonInclude;
import interview.guide.modules.interview.dynamic.model.DynamicGroupEvaluation;
import interview.guide.modules.interview.dynamic.model.InterviewExecutionMode;
import java.util.List;

/**
 * 面试评估报告
 */
public record InterviewReportDTO(
    String sessionId,
    int totalQuestions,
    Integer overallScore,                     // 无观测时为 null，0 是真实零分
    List<CategoryScore> categoryScores,        // 各类别得分
    List<QuestionEvaluation> questionDetails,  // 每题详情
    String overallFeedback,                    // 总体评价
    List<String> strengths,                    // 优势
    List<String> improvements,                 // 改进建议
    List<ReferenceAnswer> referenceAnswers,    // 参考答案
    @JsonInclude(JsonInclude.Include.NON_NULL) List<DynamicGroupEvaluation> groupEvaluations,
    @JsonInclude(JsonInclude.Include.NON_NULL) InterviewExecutionMode executionMode,
    @JsonInclude(JsonInclude.Include.NON_NULL) Integer groupEvaluationSchemaVersion,
    @JsonInclude(JsonInclude.Include.NON_NULL) Long frozenVersion
) {
    public InterviewReportDTO(String sessionId, int totalQuestions, int overallScore,
        List<CategoryScore> categoryScores, List<QuestionEvaluation> questionDetails,
        String overallFeedback, List<String> strengths, List<String> improvements,
        List<ReferenceAnswer> referenceAnswers) {
      this(sessionId, totalQuestions, Integer.valueOf(overallScore), categoryScores, questionDetails,
          overallFeedback, strengths, improvements, referenceAnswers);
    }
    public InterviewReportDTO(String sessionId, int totalQuestions, Integer overallScore,
        List<CategoryScore> categoryScores, List<QuestionEvaluation> questionDetails,
        String overallFeedback, List<String> strengths, List<String> improvements,
        List<ReferenceAnswer> referenceAnswers) {
      this(sessionId, totalQuestions, overallScore, categoryScores, questionDetails,
          overallFeedback, strengths, improvements, referenceAnswers, null, null, null, null);
    }
    /**
     * 类别得分
     */
    public record CategoryScore(
        String categoryKey,
        String categoryLabel,
        Integer score,
        int questionCount
    ) {
        public CategoryScore(String categoryKey, String categoryLabel, int score, int questionCount) {
          this(categoryKey, categoryLabel, Integer.valueOf(score), questionCount);
        }
        @JsonProperty("category")
        public String category() { return categoryLabel; }
    }
    
    /**
     * 问题评估详情
     */
    public record QuestionEvaluation(
        int questionIndex,
        String question,
        String categoryKey,
        String categoryLabel,
        String userAnswer,
        Integer score,
        String feedback
    ) {
        public QuestionEvaluation(int questionIndex, String question, String categoryKey,
            String categoryLabel, String userAnswer, int score, String feedback) {
          this(questionIndex, question, categoryKey, categoryLabel, userAnswer,
              Integer.valueOf(score), feedback);
        }
        @JsonProperty("category")
        public String category() { return categoryLabel; }
    }
    
    /**
     * 参考答案
     */
    public record ReferenceAnswer(
        int questionIndex,
        String question,
        String referenceAnswer,
        List<String> keyPoints
    ) {}
}
