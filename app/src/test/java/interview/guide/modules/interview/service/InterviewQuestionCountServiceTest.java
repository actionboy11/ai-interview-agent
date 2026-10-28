package interview.guide.modules.interview.service;

import interview.guide.modules.interview.model.InterviewQuestionDTO;
import interview.guide.modules.interview.model.InterviewQuestionCountsDTO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class InterviewQuestionCountServiceTest {
  private final ObjectMapper json = new ObjectMapper();
  private final InterviewQuestionCountService service = new InterviewQuestionCountService(json);

  @ParameterizedTest
  @CsvSource({"6,6", "8,8", "6,0", "6,12", "6,3"})
  @DisplayName("实际主问题与追问独立计数而非平分总数")
  void countsActualQuestions(int main, int followUp) {
    List<InterviewQuestionDTO> questions = new ArrayList<>();
    for (int i = 0; i < main; i++) {
      questions.add(InterviewQuestionDTO.create(i, "主问题", "REDIS", "Redis"));
    }
    for (int i = 0; i < followUp; i++) {
      questions.add(InterviewQuestionDTO.create(main + i, "追问", "REDIS", "Redis",
          null, true, i % main));
    }
    var expected = new InterviewQuestionCountsDTO(main, followUp);
    assertThat(service.fromQuestions(questions)).isEqualTo(expected);
    assertThat(service.fromJson(json.writeValueAsString(questions))).isEqualTo(expected);
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {" ", "{broken", "null", "{}", "1", "[null]", "[true]",
      "[1]", "[[]]", "[{}]", "[\"text\"]", "[{\"question\":null}]",
      "[{\"question\":3}]", "[{\"question\":\"Q\",\"isFollowUp\":\"false\"}]",
      "[{\"question\":\"Q\",\"isFollowUp\":null}]",
      "[{\"question\":\"Q\",\"isFollowUp\":1}]",
      "[{\"question\":\"Q\",\"questionIndex\":{}}]",
      "[{\"question\":\"Q\",\"questionIndex\":\"invalid\"}]",
      "[] true", "[] trailing garbage",
      "[{\"question\":\"Q\"},null]"})
  @DisplayName("缺失损坏或非题目数组一律未知不伪造零或部分计数")
  void malformedIsUnknown(String input) {
    assertThat(service.fromJson(input)).isEqualTo(new InterviewQuestionCountsDTO(null, null));
  }

  @Test
  @DisplayName("合法空数组为零题且旧题目缺追问标记按主问题兼容")
  void handlesEmptyAndLegacy() {
    assertThat(service.fromJson("[]")).isEqualTo(new InterviewQuestionCountsDTO(0, 0));
    assertThat(service.fromQuestions(List.of())).isEqualTo(new InterviewQuestionCountsDTO(0, 0));
    assertThat(service.fromJson("[{\"questionIndex\":5,\"question\":\"旧问题\"}]"))
        .isEqualTo(new InterviewQuestionCountsDTO(1, 0));
  }

  @Test
  @DisplayName("已解析题目缺失或含非法项保持未知")
  void missingParsedQuestionsAreUnknown() {
    var unknown = new InterviewQuestionCountsDTO(null, null);
    assertThat(service.fromQuestions(null)).isEqualTo(unknown);
    assertThat(service.fromQuestions(Arrays.asList((InterviewQuestionDTO) null))).isEqualTo(unknown);
  }
}
