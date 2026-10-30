package interview.guide.modules.interview.service;

import interview.guide.infrastructure.mapper.InterviewMapper;
import interview.guide.infrastructure.mapper.ResumeMapper;
import interview.guide.infrastructure.redis.InterviewSessionCache;
import interview.guide.infrastructure.redis.InterviewSessionCache.CachedSession;
import interview.guide.modules.interview.model.InterviewQuestionDTO;
import interview.guide.modules.interview.model.InterviewSessionDTO.SessionStatus;
import interview.guide.modules.interview.model.InterviewSessionEntity;
import interview.guide.modules.interview.model.CreateInterviewRequest;
import interview.guide.modules.interview.model.InterviewAnswerEntity;
import interview.guide.modules.auth.security.CurrentUserProvider;
import interview.guide.modules.interview.planning.QuestionPlanningService;
import interview.guide.modules.interview.planning.model.PlanningMode;
import interview.guide.modules.interview.planning.model.QuestionPlan;
import interview.guide.modules.interview.planning.service.InterviewQuestionPlanPersistenceService;
import interview.guide.modules.interview.skill.InterviewSkillService.SkillDTO;
import interview.guide.modules.resume.model.ResumeEntity;
import interview.guide.modules.resume.service.ResumeHistoryService;
import interview.guide.modules.resume.service.ResumePersistenceService;
import interview.guide.modules.interview.dynamic.service.DynamicInterviewSessionSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mapstruct.factory.Mappers;
import org.mockito.ArgumentMatchers;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.core.type.TypeReference;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;

class InterviewQuestionCountsResponseTest {
  private final ObjectMapper json = new ObjectMapper();
  private final InterviewMapper mapper = Mappers.getMapper(InterviewMapper.class);
  private final InterviewPersistenceService persistence = mock(InterviewPersistenceService.class);
  private final InterviewSessionCache cache = mock(InterviewSessionCache.class);
  private final InterviewQuestionCountService counts = new InterviewQuestionCountService(json);
  private final DynamicInterviewSessionSupport dynamicSessions = mock(DynamicInterviewSessionSupport.class);
  private final List<InterviewQuestionDTO> questions = List.of(
      InterviewQuestionDTO.create(4, "主问题", "REDIS", "Redis"),
      InterviewQuestionDTO.create(7, "追问", "REDIS", "Redis", null, true, 4));

  @Test
  @DisplayName("缓存恢复响应暴露实际主追问数并保留原始索引和总项")
  void cachedSessionExposesCountsWithoutRenumbering() {
    when(persistence.findBySessionId("s")).thenReturn(Optional.of(entity()));
    when(cache.getSession("s")).thenReturn(Optional.of(new CachedSession(
        "s", "resume", null, questions, 1, SessionStatus.IN_PROGRESS, json)));
    InterviewSessionService service = new InterviewSessionService(null, null, persistence,
        cache, json, null, null, null, null, null, null, null,
        new InterviewQuestionCountService(json), null, null, null, null, null);

    var response = json.valueToTree(service.getSession("s"));

    assertThat(response.path("mainQuestionCount").asInt(-1)).isEqualTo(1);
    assertThat(response.path("followUpQuestionCount").asInt(-1)).isEqualTo(1);
    assertThat(response.path("totalQuestions").asInt()).isEqualTo(2);
    assertThat(response.path("currentQuestionIndex").asInt()).isEqualTo(1);
    assertThat(response.path("questions").get(1).path("questionIndex").asInt()).isEqualTo(7);
  }

  @Test
  @DisplayName("详情复用真实题目统计且不改变题目与答案索引")
  void detailExposesCounts() {
    var session = entity();
    when(persistence.findBySessionId("s")).thenReturn(Optional.of(session));
    ObjectMapper jsonSpy = spy(json);
    var service = new InterviewHistoryService(persistence, null, jsonSpy, mapper,
        new InterviewQuestionCountService(jsonSpy), dynamicSessions);

    var response = json.valueToTree(service.getInterviewDetail("s"));

    assertThat(response.path("mainQuestionCount").asInt(-1)).isEqualTo(1);
    assertThat(response.path("followUpQuestionCount").asInt(-1)).isEqualTo(1);
    assertThat(response.path("totalQuestions").asInt()).isEqualTo(99);
    assertThat(response.path("answers").get(1).path("questionIndex").asInt()).isEqualTo(7);
    verify(jsonSpy, times(1)).readValue(eq(session.getQuestionsJson()),
        ArgumentMatchers.<TypeReference<List<Object>>>any());
  }

  @Test
  @DisplayName("创建会话根据生成题目计数而非请求主问题预算")
  void createdSessionExposesActualCounts() {
    var generator = mock(InterviewQuestionService.class);
    var planning = mock(QuestionPlanningService.class);
    var planPersistence = mock(InterviewQuestionPlanPersistenceService.class);
    var currentUser = mock(CurrentUserProvider.class);
    var skill = new SkillDTO("java-backend", "Java", null, List.of(), true, null, null, null);
    var plan = new QuestionPlan(2, PlanningMode.FIXED, List.of(), null);
    when(currentUser.requireUserId()).thenReturn(UUID.randomUUID());
    when(persistence.getHistoricalQuestions("java-backend", null)).thenReturn(List.of());
    when(generator.resolveSkillForPlanning("java-backend", null, null)).thenReturn(skill);
    when(planning.plan(any())).thenReturn(plan);
    when(generator.generateFromPlan(null, skill, "mid", null, plan)).thenReturn(questions);
    var service = new InterviewSessionService(generator, null, persistence, cache, json,
        null, null, null, currentUser, planning, null, planPersistence, counts,
        null, null, null, null, null);

    var response = json.valueToTree(service.createSession(new CreateInterviewRequest(
        null, 6, null, true, null, "java-backend", "mid", null, null)));

    assertThat(response.path("mainQuestionCount").asInt(-1)).isEqualTo(1);
    assertThat(response.path("followUpQuestionCount").asInt(-1)).isEqualTo(1);
    assertThat(response.path("totalQuestions").asInt()).isEqualTo(2);
    assertThat(response.path("currentQuestionIndex").asInt()).isZero();
  }

  @Test
  @DisplayName("数据库恢复会话保留游标并统计实际题目")
  void databaseRestorationExposesCounts() {
    var session = entity();
    session.setCurrentQuestionIndex(1);
    when(persistence.findBySessionId("s")).thenReturn(Optional.of(session));
    when(persistence.findAnswersBySessionId("s")).thenReturn(List.of());
    when(cache.getSession("s")).thenReturn(Optional.empty()).thenReturn(Optional.of(new CachedSession(
        "s", "", null, questions, 1, SessionStatus.IN_PROGRESS, json)));
    var service = new InterviewSessionService(null, null, persistence, cache, json,
        null, null, null, null, null, null, null, counts, null, null, null, null, null);

    var response = json.valueToTree(service.getSession("s"));

    assertThat(response.path("mainQuestionCount").asInt(-1)).isEqualTo(1);
    assertThat(response.path("followUpQuestionCount").asInt(-1)).isEqualTo(1);
    assertThat(response.path("currentQuestionIndex").asInt()).isEqualTo(1);
    assertThat(response.path("totalQuestions").asInt()).isEqualTo(2);
    verify(cache).saveSession(eq("s"), eq(""), eq(null), any(), eq(1),
        eq(SessionStatus.IN_PROGRESS));
  }

  @Test
  @DisplayName("列表仅用已读取题目统计且损坏题目保留总项并返回未知")
  void listsWithoutAdditionalQueriesOrLazyAnswers() {
    var session = entityWithUnloadedAnswers();
    var broken = entityWithUnloadedAnswers();
    broken.setSessionId("broken");
    broken.setQuestionsJson("broken");
    when(persistence.findAll()).thenReturn(List.of(session, broken));
    var service = new InterviewHistoryService(persistence, null, json, mapper, counts, dynamicSessions);

    var response = json.valueToTree(service.listSessions());

    assertThat(response.get(0).path("mainQuestionCount").asInt(-1)).isEqualTo(1);
    assertThat(response.get(0).path("followUpQuestionCount").asInt(-1)).isEqualTo(1);
    assertThat(response.get(0).path("totalQuestions").asInt()).isEqualTo(99);
    assertThat(response.get(1).has("mainQuestionCount")).isTrue();
    assertThat(response.get(1).path("mainQuestionCount").isNull()).isTrue();
    assertThat(response.get(1).path("followUpQuestionCount").isNull()).isTrue();
    assertThat(response.get(1).path("totalQuestions").asInt()).isEqualTo(99);
    verify(persistence).findAll();
    verifyNoMoreInteractions(persistence);
  }

  @Test
  @DisplayName("简历关联历史提供主追问计数且不访问答案或追加查询")
  void resumeHistoryExposesMeasuredCounts() {
    var resumes = mock(ResumePersistenceService.class);
    var resume = new ResumeEntity();
    resume.setId(10L);
    when(resumes.findById(10L)).thenReturn(Optional.of(resume));
    when(resumes.findAnalysesByResumeId(10L)).thenReturn(List.of());
    when(persistence.findByResumeId(10L)).thenReturn(List.of(entityWithUnloadedAnswers()));
    var service = new ResumeHistoryService(resumes, persistence, null, json,
        Mappers.getMapper(ResumeMapper.class), mapper, counts, dynamicSessions);

    var response = json.valueToTree(service.getResumeDetail(10L)).path("interviews").get(0);

    assertThat(response.path("mainQuestionCount").asInt(-1)).isEqualTo(1);
    assertThat(response.path("followUpQuestionCount").asInt(-1)).isEqualTo(1);
    assertThat(response.path("totalQuestions").asInt()).isEqualTo(99);
    verify(persistence).findByResumeId(10L);
    verifyNoMoreInteractions(persistence);
  }

  private InterviewSessionEntity entityWithUnloadedAnswers() {
    var session = new InterviewSessionEntity() {
      @Override
      public List<InterviewAnswerEntity> getAnswers() {
        throw new AssertionError("列表统计不得加载答案集合");
      }
    };
    session.setSessionId("s");
    session.setTotalQuestions(99);
    session.setStatus(InterviewSessionEntity.SessionStatus.IN_PROGRESS);
    session.setQuestionsJson(json.writeValueAsString(questions));
    return session;
  }

  @Test
  @DisplayName("详情可选题目字段损坏时保留原有答案回退与总项")
  void detailRetainsFallbackForMalformedOptionalQuestionData() {
    var session = entity();
    session.setQuestionsJson("[{\"questionIndex\":4,\"question\":\"Q\",\"score\":{}}]");
    when(persistence.findBySessionId("s")).thenReturn(Optional.of(session));
    var service = new InterviewHistoryService(persistence, null, json, mapper, counts, dynamicSessions);

    var response = json.valueToTree(service.getInterviewDetail("s"));

    assertThat(response.path("totalQuestions").asInt()).isEqualTo(99);
    assertThat(response.path("questions").get(0).path("questionIndex").asInt()).isEqualTo(4);
    assertThat(response.path("answers").isEmpty()).isTrue();
  }

  @ParameterizedTest
  @ValueSource(strings = {
      "[{\"questionIndex\":\"4\",\"question\":\"旧问题\",\"isFollowUp\":false}]",
      "[{\"questionIndex\":4,\"question\":\"旧问题\",\"isFollowUp\":\"false\"}]"
  })
  @DisplayName("旧题目字段可转换时保留未回答行且严格计数仍未知")
  void legacyCoercibleQuestionsRetainUnansweredRows(String questionsJson) {
    var session = entity();
    session.setQuestionsJson(questionsJson);
    when(persistence.findBySessionId("s")).thenReturn(Optional.of(session));
    var service = new InterviewHistoryService(persistence, null, json, mapper, counts, dynamicSessions);

    var response = json.valueToTree(service.getInterviewDetail("s"));

    assertThat(response.path("answers").size()).isEqualTo(1);
    assertThat(response.path("answers").get(0).path("questionIndex").asInt()).isEqualTo(4);
    assertThat(response.path("answers").get(0).path("question").asString()).isEqualTo("旧问题");
    assertThat(response.path("answers").get(0).path("userAnswer").isNull()).isTrue();
    assertThat(response.path("mainQuestionCount").isNull()).isTrue();
    assertThat(response.path("followUpQuestionCount").isNull()).isTrue();
    assertThat(response.path("totalQuestions").asInt()).isEqualTo(99);
  }

  private InterviewSessionEntity entity() {
    var session = new InterviewSessionEntity();
    session.setSessionId("s");
    session.setTotalQuestions(99);
    session.setStatus(InterviewSessionEntity.SessionStatus.IN_PROGRESS);
    session.setQuestionsJson(json.writeValueAsString(questions));
    return session;
  }
}
