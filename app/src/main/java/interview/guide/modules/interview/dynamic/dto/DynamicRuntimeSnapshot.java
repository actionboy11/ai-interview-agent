package interview.guide.modules.interview.dynamic.dto;

import interview.guide.modules.interview.dynamic.model.DynamicPhase;
import interview.guide.modules.interview.model.InterviewQuestionDTO;
import java.util.UUID;

public record DynamicRuntimeSnapshot(String sessionId, long version, DynamicPhase phase,
    int plannedMainQuestionCount, int currentMainOrdinal, int completedMainCount,
    int usedFollowUps, int totalQuestions, int mainQuestionCount, int followUpQuestionCount,
    InterviewQuestionDTO currentQuestion, UUID pendingDecisionId, String noticeCode) {}
