package interview.guide.modules.interview.planning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import interview.guide.modules.auth.security.CurrentUserProvider;
import interview.guide.modules.interview.planning.model.PlanningMode;
import interview.guide.modules.interview.planning.model.QuestionPlanningCommand;
import interview.guide.modules.interview.skill.InterviewSkillService;
import interview.guide.modules.interview.skill.InterviewSkillService.SkillCategoryDTO;
import interview.guide.modules.memory.service.AbilityProfileQueryService;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

class QuestionPlanningServiceTest {
  private final CurrentUserProvider currentUser = org.mockito.Mockito.mock(CurrentUserProvider.class);
  private final AbilityProfileQueryService memory = org.mockito.Mockito.mock(AbilityProfileQueryService.class);
  private QuestionPlanningService service;

  @BeforeEach
  void setUp() throws Exception {
    var skills = new InterviewSkillService(null, null, new DefaultResourceLoader(), null);
    service = new QuestionPlanningService(currentUser, memory,
        new AdaptiveAllocationPolicy(skills), new QuestionPlanValidator());
  }

  @Test
  @DisplayName("规划服务使用可信身份做 owner 范围查询")
  void scopesMemoryToCurrentUser() {
    UUID owner = UUID.randomUUID();
    when(currentUser.requireUserId()).thenReturn(owner);
    when(memory.profiles(owner, "java-backend")).thenReturn(List.of());
    when(memory.confirmedSessionCounts(owner, "java-backend")).thenReturn(Map.of());

    var result = service.plan(command());

    assertThat(result.slots()).hasSize(6);
    assertThat(result.version()).isEqualTo(2);
    assertThat(result.slots().stream().map(slot -> slot.categoryKey()).distinct())
        .containsExactlyInAnyOrder("PROJECT", "JAVA", "MYSQL", "REDIS", "SPRING",
            "SYSTEM_DESIGN_SCENARIO");
    verify(memory).profiles(owner, "java-backend");
    verify(memory).confirmedSessionCounts(owner, "java-backend");
  }

  @Test
  @DisplayName("画像读取异常回退到固定分配并记录原因")
  void memoryReadFailureFallsBack() {
    UUID owner = UUID.randomUUID();
    when(currentUser.requireUserId()).thenReturn(owner);
    when(memory.profiles(owner, "java-backend"))
        .thenThrow(new IllegalStateException("memory unavailable"));

    var result = service.plan(command());

    assertThat(result.mode()).isEqualTo(PlanningMode.FIXED);
    assertThat(result.fallbackReason()).isNotBlank();
    assertThat(result.slots()).hasSize(6);
    assertThat(result.version()).isEqualTo(2);
    assertThat(result.slots().stream().map(slot -> slot.categoryKey()).distinct())
        .containsExactlyInAnyOrder("PROJECT", "JAVA", "MYSQL", "REDIS", "SPRING",
            "SYSTEM_DESIGN_SCENARIO");
  }

  private static QuestionPlanningCommand command() {
    return new QuestionPlanningCommand("java-backend", List.of(
        new SkillCategoryDTO("PROJECT", "Project", "ALWAYS_ONE", null, false),
        new SkillCategoryDTO("JAVA", "Java", "CORE", null, false),
        new SkillCategoryDTO("MYSQL", "MySQL", "CORE", null, false),
        new SkillCategoryDTO("REDIS", "Redis", "CORE", null, false),
        new SkillCategoryDTO("SPRING", "Spring", "CORE", null, false),
        new SkillCategoryDTO("SYSTEM_DESIGN_SCENARIO", "Design", "NORMAL", null, false)),
        6, "mid", false, Map.of(), List.of(), Set.of());
  }
}
