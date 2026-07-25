package interview.guide.modules.interview.deadletter;

import java.util.List;

public record InterviewEvaluationDeadLetterPageResponse(
    List<InterviewEvaluationDeadLetterDTO> items,
    int page,
    int size,
    long totalElements,
    int totalPages
) {
}

