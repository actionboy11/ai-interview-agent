package interview.guide.modules.interview.planning.model;

import java.util.List;

public record QuestionSlot(String slotId, String categoryKey, QuestionPurpose purpose,
    String targetDifficulty, QuestionSourceMode sourceMode, List<String> focusTopics,
    List<String> avoidedTopics, Long relatedProfileId) {
  public QuestionSlot {
    focusTopics = focusTopics == null ? List.of() : List.copyOf(focusTopics);
    avoidedTopics = avoidedTopics == null ? List.of() : List.copyOf(avoidedTopics);
  }
}
