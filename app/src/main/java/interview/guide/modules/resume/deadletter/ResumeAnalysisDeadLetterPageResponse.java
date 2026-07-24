package interview.guide.modules.resume.deadletter;

import java.util.List;

public record ResumeAnalysisDeadLetterPageResponse(
    List<ResumeAnalysisDeadLetterDTO> items,
    int page,
    int size,
    long totalElements,
    int totalPages
) {
}
