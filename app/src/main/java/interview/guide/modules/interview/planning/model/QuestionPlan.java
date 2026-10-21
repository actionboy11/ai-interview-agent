package interview.guide.modules.interview.planning.model;

import java.util.List;

public record QuestionPlan(int version, PlanningMode mode, List<QuestionSlot> slots,
    String fallbackReason) {
  public QuestionPlan {
    slots = List.copyOf(slots);
  }

  public List<QuestionSlot> resumeSlots() {
    return slots.stream().filter(slot -> slot.sourceMode() == QuestionSourceMode.RESUME).toList();
  }

  public List<QuestionSlot> directionSlots() {
    return slots.stream().filter(slot -> slot.sourceMode() == QuestionSourceMode.DIRECTION)
        .toList();
  }
}
