package interview.guide.modules.interview.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * 面试评估报告
 */
public record InterviewReportDTO(
    String sessionId,
    int totalQuestions,
    int overallScore,                          // 总分 (0-100)
    List<CategoryScore> categoryScores,        // 各类别得分
    List<QuestionEvaluation> questionDetails,  // 每题详情
    String overallFeedback,                    // 总体评价
    List<String> strengths,                    // 优势
    List<String> improvements,                 // 改进建议
    List<ReferenceAnswer> referenceAnswers     // 参考答案
) {
    /**
     * 类别得分
     */
    public record CategoryScore(
        String categoryKey,
        String categoryLabel,
        int score,
        int questionCount
    ) {
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
        int score,
        String feedback
    ) {
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
