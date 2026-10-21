package interview.guide.modules.interview.planning;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import interview.guide.common.exception.BusinessException;
import interview.guide.modules.interview.planning.model.PlanningMode;
import interview.guide.modules.interview.planning.model.QuestionPlan;
import interview.guide.modules.interview.planning.model.QuestionPlanningCommand;
import interview.guide.modules.interview.planning.model.QuestionPurpose;
import interview.guide.modules.interview.planning.model.QuestionSlot;
import interview.guide.modules.interview.planning.model.QuestionSourceMode;
import interview.guide.modules.interview.skill.InterviewSkillService.SkillCategoryDTO;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class QuestionPlanValidatorTest {
  private final QuestionPlanValidator validator = new QuestionPlanValidator();

  @Test
  @DisplayName("八个分类足够时拒绝单分类超过总题量三成的计划")
  void rejectsAvoidableCategoryCapViolation() {
    var categories = List.of("PROJECT", "JAVA", "MYSQL", "REDIS", "SPRING", "MQ", "OS", "NETWORK")
        .stream().map(key -> new SkillCategoryDTO(key, key, "CORE", null, false)).toList();
    var command = new QuestionPlanningCommand("java-backend", categories, 10, "mid", false,
        Map.of(), List.of(), Set.of());
    var slots = new ArrayList<QuestionSlot>();
    for (int i = 0; i < 10; i++) {
      String category = i < 4 ? "JAVA" : categories.get(i - 4).key();
      slots.add(new QuestionSlot("slot-%02d".formatted(i + 1), category,
          QuestionPurpose.BASELINE, "mid", QuestionSourceMode.DIRECTION,
          List.of(), List.of(), null));
    }

    assertThatThrownBy(() -> validator.validate(command,
        new QuestionPlan(1, PlanningMode.ADAPTIVE, slots, null)))
        .isInstanceOf(BusinessException.class);
  }

  @ParameterizedTest(name = "未核实简历分类拒绝 {0} 简历题槽")
  @EnumSource(QuestionPurpose.class)
  @DisplayName("任一用途都不能把无简历事实的分类标为简历题")
  void rejectsUngroundedResumeSlot(QuestionPurpose purpose) {
    var command = new QuestionPlanningCommand("java-backend",
        List.of(new SkillCategoryDTO("JAVA", "Java", "CORE", null, false)),
        1, "mid", true, Map.of(), List.of(), Set.of());
    var slot = new QuestionSlot("slot-01", "JAVA", purpose, "mid",
        QuestionSourceMode.RESUME, List.of(), List.of(), null);

    assertThatThrownBy(() -> validator.validate(command,
        new QuestionPlan(1, PlanningMode.ADAPTIVE, List.of(slot), null)))
        .isInstanceOf(BusinessException.class);
  }
}
