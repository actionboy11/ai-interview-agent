package interview.guide.modules.voiceinterview.deadletter;

import java.util.List;

public record VoiceEvaluationDeadLetterPageResponse(
    List<VoiceEvaluationDeadLetterDTO> items,
    int page,
    int size,
    long totalElements,
    int totalPages
) {
}
