package interview.guide.modules.ownership;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import interview.guide.modules.interview.model.CreateInterviewRequest;
import interview.guide.modules.interview.model.InterviewQuestionDTO;
import interview.guide.modules.interview.model.InterviewSessionEntity;
import interview.guide.modules.interview.model.SubmitAnswerRequest;
import interview.guide.modules.interview.planning.AdaptiveAllocationPolicy;
import interview.guide.modules.interview.planning.QuestionPlanValidator;
import interview.guide.modules.interview.planning.QuestionPlanningService;
import interview.guide.modules.interview.planning.model.InterviewQuestionPlanEntity;
import interview.guide.modules.interview.planning.model.PlanningMode;
import interview.guide.modules.interview.planning.model.QuestionPlan;
import interview.guide.modules.interview.planning.model.QuestionPlanningCommand;
import interview.guide.modules.interview.planning.model.QuestionPurpose;
import interview.guide.modules.interview.planning.model.QuestionSlot;
import interview.guide.modules.interview.planning.model.QuestionSourceMode;
import interview.guide.modules.interview.planning.repository.InterviewQuestionPlanRepository;
import interview.guide.modules.interview.skill.InterviewSkillService;
import interview.guide.modules.interview.skill.InterviewSkillService.SkillCategoryDTO;
import interview.guide.modules.interview.skill.InterviewSkillService.SkillDTO;
import interview.guide.modules.memory.dto.AbilityProfileResponse;
import interview.guide.modules.memory.model.AbilityLevel;
import interview.guide.modules.memory.model.AbilityProfileStatus;
import interview.guide.modules.memory.service.AbilityProfileQueryService;
import java.io.IOException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.DefaultResourceLoader;

@DisplayName("覆盖优先规划与历史会话的跨层回归")
class CoverageFirstInterviewRegressionIntegrationTest extends WorkflowOwnershipTestSupport {
  private static final String SKILL = "java-backend";
  @Autowired InterviewQuestionPlanRepository plans;

  @Test
  @DisplayName("V1缺类计划冷恢复、历史统计和继续作答保持原JSON、题序与追问索引")
  void legacyPlanSurvivesCountsAndContinuedAnswer() {
    var originalQuestions = fixtureQuestions("legacy", List.of("PROJECT", "JAVA", "MYSQL",
        "PROJECT", "SYSTEM_DESIGN_SCENARIO", "PROJECT", "SYSTEM_DESIGN_SCENARIO",
        "SYSTEM_DESIGN_SCENARIO"));
    var session = historicalSession(originalQuestions, "2026-10-27T10:00:00");
    session.setStatus(InterviewSessionEntity.SessionStatus.IN_PROGRESS);
    session.setCurrentQuestionIndex(1);
    String originalJson = "\n" + session.getQuestionsJson() + "\n";
    session.setQuestionsJson(originalJson);
    sessions.saveAndFlush(session);
    interviewPersistence.saveAnswer(session.getSessionId(), 0, "legacy-0", "PROJECT",
        "PROJECT", "synthetic saved answer", 0, null);
    var slots = new ArrayList<QuestionSlot>();
    for (var question : originalQuestions) {
      if (!question.isFollowUp()) {
        slots.add(new QuestionSlot(question.slotId(), question.type(), QuestionPurpose.BASELINE,
            "mid", QuestionSourceMode.DIRECTION, List.of(), List.of(), null));
      }
    }
    var legacyPlan = new InterviewQuestionPlanEntity();
    legacyPlan.setSession(session);
    legacyPlan.setOwner(first);
    legacyPlan.setPlanVersion(1);
    legacyPlan.setPlanningMode(PlanningMode.ADAPTIVE);
    String originalPlanJson = "\n" + mapper.writeValueAsString(
        new QuestionPlan(1, PlanningMode.ADAPTIVE, slots, null)) + "\n";
    legacyPlan.setPlanJson(originalPlanJson);
    plans.saveAndFlush(legacyPlan);
    when(planning.plan(any())).thenThrow(new AssertionError("历史会话不得重新规划"));
    cachedValues.clear();

    var restored = interviewService.getSession(session.getSessionId());
    assertThat(restored.totalQuestions()).isEqualTo(16);
    assertThat(restored.mainQuestionCount()).isEqualTo(8);
    assertThat(restored.followUpQuestionCount()).isEqualTo(8);
    assertThat(restored.currentQuestionIndex()).isEqualTo(1);
    assertThat(restored.questions()).extracting(InterviewQuestionDTO::type).containsExactly(
        "PROJECT", "PROJECT", "JAVA", "JAVA", "MYSQL", "MYSQL", "PROJECT", "PROJECT",
        "SYSTEM_DESIGN_SCENARIO", "SYSTEM_DESIGN_SCENARIO", "PROJECT", "PROJECT",
        "SYSTEM_DESIGN_SCENARIO", "SYSTEM_DESIGN_SCENARIO",
        "SYSTEM_DESIGN_SCENARIO", "SYSTEM_DESIGN_SCENARIO");
    assertThat(restored.questions().getFirst().userAnswer()).isEqualTo("synthetic saved answer");
    assertThat(interviewService.getCurrentQuestion(session.getSessionId()).parentQuestionIndex())
        .isZero();
    var summary = interviewHistory.listSessions().getFirst();
    assertThat(summary.totalQuestions()).isEqualTo(16);
    assertThat(summary.mainQuestionCount()).isEqualTo(8);
    assertThat(summary.followUpQuestionCount()).isEqualTo(8);
    var detail = interviewHistory.getInterviewDetail(session.getSessionId());
    assertThat(detail.answers()).extracting(answer -> answer.questionIndex())
        .containsExactly(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15);

    var submitted = interviewService.submitAnswer(new SubmitAnswerRequest(
        session.getSessionId(), 1, "synthetic continued answer"));
    assertThat(submitted.currentIndex()).isEqualTo(2);
    assertThat(submitted.totalQuestions()).isEqualTo(16);
    assertThat(submitted.nextQuestion().question()).isEqualTo("legacy-1");
    assertThat(interviewPersistence.findAnswersBySessionId(session.getSessionId()))
        .extracting(answer -> answer.getUserAnswer())
        .containsExactly("synthetic saved answer", "synthetic continued answer");
    assertThat(sessions.findBySessionId(session.getSessionId()).orElseThrow().getQuestionsJson())
        .isEqualTo(originalJson);
    assertThat(plans.findByOwnerIdAndSessionSessionId(first.getId(), session.getSessionId()))
        .singleElement().satisfies(saved -> {
          assertThat(saved.getPlanVersion()).isEqualTo(1);
          assertThat(saved.getPlanJson()).isEqualTo(originalPlanJson);
        });
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  @DisplayName("第三场使用创建时最小画像和两场历史，V2自适应及故障回退均持久化六类覆盖")
  void thirdCreationUsesDatedSnapshotAndPersistsCoverage(boolean memoryFailure) throws IOException {
    // Synthetic reproduction of the category histogram; no original questions or user answers.
    historicalSession(fixtureQuestions("first", List.of("PROJECT", "JAVA", "MYSQL",
        "REDIS", "SPRING", "PROJECT")), "2026-10-26T10:00:00");
    historicalSession(fixtureQuestions("second", List.of("PROJECT", "JAVA", "MYSQL",
        "REDIS", "SPRING", "SYSTEM_DESIGN_SCENARIO")), "2026-10-27T10:00:00");
    var skillService = new InterviewSkillService(null, null, new DefaultResourceLoader(), null);
    var memory = mock(AbilityProfileQueryService.class);
    var realPlanner = new QuestionPlanningService(currentUser, memory,
        new AdaptiveAllocationPolicy(skillService), new QuestionPlanValidator());
    var createdAt = Instant.parse("2026-10-28T01:00:00Z");
    var creationSnapshot = List.of(
        profile(1L, "PROJECT", AbilityLevel.WEAK, 35, createdAt.minusSeconds(3600)),
        profile(2L, "MYSQL", AbilityLevel.STRONG, 85, createdAt.minusSeconds(3600)),
        profile(3L, "REDIS", AbilityLevel.PROFICIENT, 75, createdAt.minusSeconds(3600)));
    if (memoryFailure) {
      when(memory.profiles(first.getId(), SKILL))
          .thenThrow(new IllegalStateException("synthetic memory outage"));
    } else {
      when(memory.profiles(first.getId(), SKILL)).thenReturn(creationSnapshot);
      when(memory.confirmedSessionCounts(first.getId(), SKILL))
          .thenReturn(Map.of("PROJECT", 2L, "MYSQL", 2L, "REDIS", 2L));
    }
    when(questions.resolveSkillForPlanning(eq(SKILL), any(), any())).thenReturn(new SkillDTO(
        SKILL, "Java", "", categories(), true, null, null, null));
    when(planning.plan(any())).thenAnswer(invocation -> {
      QuestionPlanningCommand command = invocation.getArgument(0);
      assertThat(command.recentCoverage()).containsExactlyInAnyOrderEntriesOf(Map.of(
          "PROJECT", 3, "JAVA", 2, "MYSQL", 2, "REDIS", 2, "SPRING", 2,
          "SYSTEM_DESIGN_SCENARIO", 1));
      assertThat(command.historicalQuestions()).hasSize(12);
      return realPlanner.plan(command);
    });
    when(questions.generateFromPlan(any(), any(), any(), any(), any()))
        .thenAnswer(invocation -> {
          QuestionPlan plan = invocation.getArgument(4);
          return fixtureQuestions("third", plan.slots().stream()
              .map(QuestionSlot::categoryKey).toList());
        });

    var created = interviewService.createSession(new CreateInterviewRequest(
        null, 8, null, true, null, SKILL, "mid", null, null));
    var persisted = plans.findByOwnerIdAndSessionSessionId(first.getId(), created.sessionId());
    assertThat(persisted).singleElement().satisfies(saved -> {
      assertThat(saved.getPlanVersion()).isEqualTo(2);
      assertThat(saved.getPlanningMode())
          .isEqualTo(memoryFailure ? PlanningMode.FIXED : PlanningMode.ADAPTIVE);
      assertThat(saved.getFallbackReason())
          .isEqualTo(memoryFailure ? "MEMORY_OR_PLANNING_FAILURE" : null);
    });
    var savedPlan = mapper.readValue(persisted.getFirst().getPlanJson(), QuestionPlan.class);
    assertThat(savedPlan.slots()).hasSize(8);
    assertThat(savedPlan.slots().subList(0, 6)).extracting(QuestionSlot::categoryKey)
        .containsExactly("PROJECT", "JAVA", "MYSQL", "REDIS", "SPRING",
            "SYSTEM_DESIGN_SCENARIO");
    assertThat(created.questions().stream().filter(question -> !question.isFollowUp()))
        .extracting(InterviewQuestionDTO::type)
        .containsExactlyElementsOf(savedPlan.slots().stream().map(QuestionSlot::categoryKey).toList());
    assertThat(created.totalQuestions()).isEqualTo(16);
    assertThat(created.mainQuestionCount()).isEqualTo(8);
    assertThat(created.followUpQuestionCount()).isEqualTo(8);

    // A later profile must never be consulted when resuming this saved creation-time plan.
    doReturn(List.of(
        profile(1L, "PROJECT", AbilityLevel.STRONG, 90, createdAt.plusSeconds(3600))))
        .when(memory).profiles(first.getId(), SKILL);
    doThrow(new AssertionError("恢复时不得读取后来的画像重新规划")).when(planning).plan(any());
    cachedValues.clear();
    assertThat(interviewService.getSession(created.sessionId()).questions()).isEqualTo(created.questions());
    assertThat(interviewHistory.getInterviewDetail(created.sessionId()).mainQuestionCount())
        .isEqualTo(8);
    assertThat(plans.findByOwnerIdAndSessionSessionId(first.getId(), created.sessionId()))
        .singleElement().extracting(InterviewQuestionPlanEntity::getPlanJson)
        .isEqualTo(persisted.getFirst().getPlanJson());
  }

  private InterviewSessionEntity historicalSession(List<InterviewQuestionDTO> values, String time) {
    var session = new InterviewSessionEntity();
    session.setOwner(first);
    session.setSessionId(UUID.randomUUID().toString());
    session.setSkillId(SKILL);
    session.setCreatedAt(LocalDateTime.parse(time));
    session.setStatus(InterviewSessionEntity.SessionStatus.COMPLETED);
    session.setQuestionsJson(mapper.writeValueAsString(values));
    session.setTotalQuestions(values.size());
    return sessions.saveAndFlush(session);
  }

  private static List<InterviewQuestionDTO> fixtureQuestions(String prefix, List<String> keys) {
    var values = new ArrayList<InterviewQuestionDTO>();
    for (int i = 0; i < keys.size(); i++) {
      int index = values.size();
      String slot = "slot-%02d".formatted(i + 1);
      values.add(InterviewQuestionDTO.planned(index, prefix + "-" + i, keys.get(i),
          keys.get(i), null, false, null, slot));
      values.add(InterviewQuestionDTO.planned(index + 1, prefix + "-" + i + "-follow-up",
          keys.get(i), keys.get(i), null, true, index, slot));
    }
    return values;
  }

  private static AbilityProfileResponse profile(Long id, String key, AbilityLevel level,
      int score, Instant time) {
    return new AbilityProfileResponse(id, SKILL, key, level, score, .9, null, 2,
        AbilityProfileStatus.ACTIVE, false, time, time, 0L);
  }

  private static List<SkillCategoryDTO> categories() {
    return List.of(new SkillCategoryDTO("PROJECT", "Project", "ALWAYS_ONE", null, false),
        new SkillCategoryDTO("JAVA", "Java", "CORE", null, false),
        new SkillCategoryDTO("MYSQL", "MySQL", "CORE", null, false),
        new SkillCategoryDTO("REDIS", "Redis", "CORE", null, false),
        new SkillCategoryDTO("SPRING", "Spring", "CORE", null, false),
        new SkillCategoryDTO("SYSTEM_DESIGN_SCENARIO", "Design", "NORMAL", null, false));
  }
}
