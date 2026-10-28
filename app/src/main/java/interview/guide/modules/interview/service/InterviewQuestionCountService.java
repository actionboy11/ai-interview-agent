package interview.guide.modules.interview.service;

import interview.guide.modules.interview.model.InterviewQuestionCountsDTO;
import interview.guide.modules.interview.model.InterviewQuestionDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

/** 只读取实际题目，不访问数据库、不推测计划题数。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InterviewQuestionCountService {
  private static final InterviewQuestionCountsDTO UNKNOWN =
      new InterviewQuestionCountsDTO(null, null);
  private final ObjectMapper objectMapper;

  public InterviewQuestionCountsDTO fromJson(String json) {
    if (json == null || json.isBlank()) {
      return UNKNOWN;
    }
    try {
      return fromParsedQuestions(objectMapper.readValue(json, new TypeReference<List<Object>>() {}));
    } catch (JacksonException e) {
      log.warn("无法统计面试题数：题目 JSON 损坏", e);
      return UNKNOWN;
    }
  }

  /** 详情已解析的原始题目可以直接复用，避免再次解析 JSON。 */
  public InterviewQuestionCountsDTO fromParsedQuestions(List<?> questions) {
    if (questions == null) {
      return UNKNOWN;
    }
    int followUps = 0;
    for (Object question : questions) {
      if (!(question instanceof Map<?, ?> fields)
          || !(fields.get("question") instanceof String)
          || (fields.containsKey("questionIndex")
              && !(fields.get("questionIndex") instanceof Integer)
              && !(fields.get("questionIndex") instanceof Long))
          || (fields.containsKey("isFollowUp")
              && !(fields.get("isFollowUp") instanceof Boolean))) {
        return UNKNOWN;
      }
      if (Boolean.TRUE.equals(fields.get("isFollowUp"))) {
        followUps++;
      }
    }
    return new InterviewQuestionCountsDTO(questions.size() - followUps, followUps);
  }

  public InterviewQuestionCountsDTO fromQuestions(List<InterviewQuestionDTO> questions) {
    if (questions == null) {
      return UNKNOWN;
    }
    int followUps = 0;
    for (InterviewQuestionDTO question : questions) {
      if (question == null || question.question() == null) {
        return UNKNOWN;
      }
      if (question.isFollowUp()) {
        followUps++;
      }
    }
    return new InterviewQuestionCountsDTO(questions.size() - followUps, followUps);
  }
}
