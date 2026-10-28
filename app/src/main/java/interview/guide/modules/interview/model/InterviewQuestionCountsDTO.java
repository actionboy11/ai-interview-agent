package interview.guide.modules.interview.model;

/** 实际题目只读计数；两个 null 表示题目数据未知。 */
public record InterviewQuestionCountsDTO(
    Integer mainQuestionCount,
    Integer followUpQuestionCount
) {}
