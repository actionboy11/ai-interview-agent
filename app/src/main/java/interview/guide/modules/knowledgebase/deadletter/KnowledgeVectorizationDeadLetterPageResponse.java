package interview.guide.modules.knowledgebase.deadletter;

import java.util.List;

public record KnowledgeVectorizationDeadLetterPageResponse(
    List<KnowledgeVectorizationDeadLetterDTO> items,
    int page,
    int size,
    long totalElements,
    int totalPages
) {
}
