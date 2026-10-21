package interview.guide.common.evaluation;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * 通用面试评估报告（文字面试和语音面试共用）
 */
public record EvaluationReport(
    String sessionId,
    int totalQuestions,
    int overallScore,
    List<CategoryScore> categoryScores,
    List<QuestionEvaluation> questionDetails,
    String overallFeedback,
    List<String> strengths,
    List<String> improvements,
    List<ReferenceAnswer> referenceAnswers
) {
    public record CategoryScore(
        String categoryKey,
        @JsonAlias("category") String categoryLabel,
        int score,
        int questionCount
    ) {
        @JsonProperty("category")
        public String category() { return categoryLabel; }
    }

    public record QuestionEvaluation(
        int questionIndex,
        String question,
        String categoryKey,
        @JsonAlias("category") String categoryLabel,
        String userAnswer,
        int score,
        String feedback
    ) {
        public QuestionEvaluation {
            if (categoryKey == null || categoryKey.isBlank()) {
                categoryKey = "OTHER";
            }
        }

        @JsonProperty("category")
        public String category() { return categoryLabel; }
    }

    public record ReferenceAnswer(
        int questionIndex,
        String question,
        String referenceAnswer,
        List<String> keyPoints
    ) {}
}
