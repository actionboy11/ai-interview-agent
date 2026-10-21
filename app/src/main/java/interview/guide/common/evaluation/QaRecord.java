package interview.guide.common.evaluation;

/**
 * 通用面试问答记录（文字面试和语音面试共用）
 */
public record QaRecord(
    int questionIndex,
    String question,
    String categoryKey,
    String categoryLabel,
    String userAnswer   // null 表示未回答
) {
    public QaRecord(int questionIndex, String question, String category, String userAnswer) {
        this(questionIndex, question, voiceCategoryKey(category), category, userAnswer);
    }

    public String category() {
        return categoryLabel;
    }

    private static String voiceCategoryKey(String label) {
        if (label == null) {
            return "OTHER";
        }
        return switch (label) {
            case "项目深挖" -> "PROJECT";
            case "自我介绍" -> "SELF_INTRODUCTION";
            case "HR问题" -> "HR";
            case "技术问题" -> "TECHNICAL";
            case "综合" -> "GENERAL";
            default -> "OTHER";
        };
    }
}
