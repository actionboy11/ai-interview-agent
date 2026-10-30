package interview.guide.modules.interview.model;

import interview.guide.modules.interview.dynamic.model.SubmissionIntent;

/**
 * 面试问题DTO
 * type 由 Skill category key 驱动（如 MYSQL、CSS、DYNAMIC_PROGRAMMING 等），不再使用枚举
 */
public record InterviewQuestionDTO(
    int questionIndex,
    String question,
    String type,           // Skill category key，如 "MYSQL"、"CSS"、"DP"
    String category,       // 展示用标签，如 "MySQL"、"CSS"、"动态规划"
    String topicSummary,   // 知识点摘要，如 "Redis RDB/AOF 持久化对比"，用于历史去重压缩
    String userAnswer,
    Integer score,
    String feedback,
    boolean isFollowUp,
    Integer parentQuestionIndex,
    String slotId,
    SubmissionIntent submissionIntent
) {
    public InterviewQuestionDTO(int questionIndex, String question, String type, String category,
        String topicSummary, String userAnswer, Integer score, String feedback, boolean isFollowUp,
        Integer parentQuestionIndex, String slotId) {
      this(questionIndex, question, type, category, topicSummary, userAnswer, score, feedback,
          isFollowUp, parentQuestionIndex, slotId, null);
    }
    public static InterviewQuestionDTO create(int index, String question, String type, String category) {
        return new InterviewQuestionDTO(index, question, type, category, null, null, null, null, false, null, null);
    }

    public static InterviewQuestionDTO create(int index, String question, String type, String category,
                                               String topicSummary, boolean isFollowUp, Integer parentQuestionIndex) {
        return new InterviewQuestionDTO(index, question, type, category, topicSummary, null, null, null, isFollowUp, parentQuestionIndex, null);
    }

    public static InterviewQuestionDTO planned(int index, String question, String type,
                                               String category, String topicSummary,
                                               boolean isFollowUp, Integer parentQuestionIndex,
                                               String slotId) {
        return new InterviewQuestionDTO(index, question, type, category, topicSummary,
            null, null, null, isFollowUp, parentQuestionIndex, slotId);
    }

    public InterviewQuestionDTO withAnswer(String answer) {
        return withSubmission(answer, submissionIntent);
    }

    public InterviewQuestionDTO withSubmission(String answer, SubmissionIntent intent) {
      return new InterviewQuestionDTO(questionIndex, question, type, category, topicSummary, answer,
          score, feedback, isFollowUp, parentQuestionIndex, slotId, intent);
    }

    public InterviewQuestionDTO withEvaluation(int score, String feedback) {
        return new InterviewQuestionDTO(questionIndex, question, type, category, topicSummary, userAnswer, score, feedback, isFollowUp, parentQuestionIndex, slotId, submissionIntent);
    }
}
