package interview.guide.modules.interview.dynamic.model;

import java.util.List;

public record DynamicMainQuestion(String slotId, String question, String type, String category,
    String topicSummary, List<AssessmentTarget> assessmentTargets, String difficulty,
    List<String> profileFocus) {
  public DynamicMainQuestion {
    assessmentTargets = List.copyOf(assessmentTargets);
    profileFocus = List.copyOf(profileFocus);
  }
}
