package interview.guide.modules.interview.planning;

import interview.guide.modules.interview.planning.model.QuestionPlanningCommand;
import interview.guide.modules.interview.skill.InterviewSkillProperties;
import interview.guide.modules.interview.skill.InterviewSkillService;
import interview.guide.modules.interview.skill.InterviewSkillService.SkillCategoryDTO;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.yaml.snakeyaml.Yaml;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("简历事实与 Skill 分类确定性对齐")
class ResumeFactGroundingTest {
  private final ResumeFactGrounding grounding = new ResumeFactGrounding();

  @Test
  @DisplayName("实际 Python 预设从 Python 与 Django 事实保留固定计划简历题")
  void pythonAndDjangoKeepResumeBudget() throws Exception {
    var categories = preset("python-backend");
    var keys = grounding.grounded("Python, Django, PostgreSQL 项目经验", categories);
    assertThat(keys).contains("PYTHON_BASIC", "DJANGO_FLASK", "DATABASE", "PROJECT");
    assertThat(keys).doesNotContain("CACHE", "DEPLOY");
    var skills = mock(InterviewSkillService.class);
    when(skills.calculateAllocation(anyList(), anyInt())).thenReturn(Map.of());
    var command = new QuestionPlanningCommand("python-backend", categories, 10, "mid", true,
        Map.of(), List.of(), keys);
    assertThat(new AdaptiveAllocationPolicy(skills).fallback(command, "NO_MEMORY").resumeSlots())
        .isNotEmpty();
  }

  @Test
  @DisplayName("实际前端预设 React 单项事实保留简历分类且不会猜出其它事实")
  void reactOnlyDoesNotInventVueOrOtherCategories() throws Exception {
    var categories = preset("frontend");
    var keys = grounding.grounded("构建 React 组件", categories);
    assertThat(keys).containsExactly("REACT_VUE");
  }

  @Test
  @DisplayName("英文技术词必须完整匹配，空白与无关简历不产生分类")
  void boundariesAndUnrelatedFacts() throws Exception {
    var categories = preset("python-backend");
    assertThat(grounding.grounded("pythonic and djangoish", categories)).isEmpty();
    assertThat(grounding.grounded("销售及财务管理", categories)).isEmpty();
    assertThat(grounding.grounded("  ", categories)).isEmpty();
  }

  private List<SkillCategoryDTO> preset(String skillId) throws IOException {
    var resource = new ClassPathResource("skills/" + skillId + "/skill.meta.yml");
    try (var input = resource.getInputStream()) {
      InterviewSkillProperties.SkillMetaDefinition meta = new Yaml().loadAs(input,
          InterviewSkillProperties.SkillMetaDefinition.class);
      return meta.getCategories().stream().map(category -> new SkillCategoryDTO(
          category.getKey(), category.getLabel(), category.getPriority(), category.getRef(),
          Boolean.TRUE.equals(category.getShared()))).toList();
    }
  }
}
