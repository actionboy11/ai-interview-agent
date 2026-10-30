package interview.guide.modules.interview.dynamic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verifyNoInteractions;

import interview.guide.common.exception.BusinessException;
import interview.guide.infrastructure.redis.InterviewSessionCache;
import interview.guide.infrastructure.redis.InterviewSessionCache.CachedSession;
import interview.guide.modules.auth.security.CurrentUserProvider;
import interview.guide.modules.interview.dynamic.model.InterviewExecutionMode;
import interview.guide.modules.interview.dynamic.service.DynamicInterviewProperties;
import interview.guide.modules.interview.dynamic.service.DynamicInterviewSessionSupport;
import interview.guide.modules.interview.dynamic.service.DynamicInterviewCreationService;
import interview.guide.modules.interview.dynamic.dto.DynamicRuntimeSnapshot;
import interview.guide.modules.interview.dynamic.model.DynamicPhase;
import interview.guide.modules.interview.repository.InterviewAnswerRepository;
import interview.guide.modules.interview.dynamic.repository.DynamicInterviewRuntimeRepository;
import interview.guide.modules.interview.planning.model.QuestionPlan;
import interview.guide.modules.interview.planning.model.PlanningMode;
import interview.guide.modules.interview.skill.InterviewSkillService.SkillDTO;
import interview.guide.modules.interview.model.CreateInterviewRequest;
import interview.guide.modules.interview.model.InterviewQuestionDTO;
import interview.guide.modules.interview.model.InterviewSessionDTO.SessionStatus;
import interview.guide.modules.interview.model.InterviewSessionEntity;
import interview.guide.modules.interview.model.InterviewReportDTO;
import interview.guide.common.model.AsyncTaskStatus;
import interview.guide.modules.interview.model.SubmitAnswerRequest;
import interview.guide.modules.interview.planning.QuestionPlanningService;
import interview.guide.modules.interview.planning.service.InterviewQuestionPlanPersistenceService;
import interview.guide.modules.interview.service.InterviewPersistenceService;
import interview.guide.modules.interview.service.InterviewQuestionCountService;
import interview.guide.modules.interview.service.InterviewQuestionService;
import interview.guide.modules.interview.service.InterviewSessionService;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("动态执行模式与旧接口隔离")
class DynamicLegacyCompatibilityTest {
  @Mock InterviewPersistenceService persistence;
  @Mock InterviewSessionCache cache;
  @Mock CurrentUserProvider currentUser;
  @Mock InterviewQuestionService questions;
  @Mock QuestionPlanningService planning;
  @Mock InterviewQuestionPlanPersistenceService plans;
  @Mock DynamicInterviewCreationService dynamicCreation;
  @InjectMocks InterviewSessionService service;
  final ObjectMapper mapper = new ObjectMapper();
  final UUID ownerId = UUID.randomUUID();
  final InterviewSessionEntity entity = new InterviewSessionEntity();

  @BeforeEach
  void setup() {
    ReflectionTestUtils.setField(service, "objectMapper", mapper);
    ReflectionTestUtils.setField(service, "questionCounts", new InterviewQuestionCountService(mapper));
    ReflectionTestUtils.setField(service, "dynamicProperties", new DynamicInterviewProperties());
    ReflectionTestUtils.setField(service, "dynamicSessions", new DynamicInterviewSessionSupport(
        org.mockito.Mockito.mock(InterviewAnswerRepository.class), mapper, cache,
        org.mockito.Mockito.mock(DynamicInterviewRuntimeRepository.class)));
    when(currentUser.requireUserId()).thenReturn(ownerId);
    when(planning.plan(any())).thenReturn(new QuestionPlan(1, PlanningMode.FIXED, List.of(), null));
    when(questions.resolveSkillForPlanning(any(), any(), any())).thenReturn(
        new SkillDTO("java", "Java", null, List.of(), true, null, null, null));
    when(questions.generateFromPlan(any(), any(), any(), any(), any())).thenReturn(List.of(
        InterviewQuestionDTO.create(0, "固定问题", "JAVA", "Java")));
    entity.setSessionId("s");
    entity.setExecutionMode(InterviewExecutionMode.DYNAMIC_FOLLOW_UP);
    entity.setQuestionsJson(mapper.writeValueAsString(List.of(InterviewQuestionDTO.create(
        0, "已发布动态问题", "JAVA", "Java"))));
    when(persistence.findBySessionId("s")).thenReturn(Optional.of(entity));
    when(cache.getSession("s")).thenReturn(Optional.of(new CachedSession("s", "", null,
        List.of(InterviewQuestionDTO.create(0, "旧缓存问题", "JAVA", "Java")), 0,
        SessionStatus.CREATED, mapper)));
    when(dynamicCreation.create(any())).thenReturn(new DynamicRuntimeSnapshot("s", 0,
        DynamicPhase.WAITING_ANSWER, 3, 1, 0, 0, 1, 1, 0,
        InterviewQuestionDTO.create(0, "已发布动态问题", "JAVA", "Java"), null, null));
  }

  @Test
  @DisplayName("旧Java构造器和缺省模式仍创建固定面试")
  void missingModeCreatesFixed() {
    when(questions.generateFromPlan(any(), any(), any(), any(), any())).thenReturn(List.of(
        InterviewQuestionDTO.create(0, "固定问题", "JAVA", "Java")));
    var created = service.createSession(new CreateInterviewRequest(null, 3, null, true,
        null, "java", "mid", null, null));
    assertThat(mapper.valueToTree(created).path("executionMode").asString())
        .isEqualTo("LEGACY_FIXED");
    var withoutMode = mapper.readValue("{\"questionCount\":3,\"skillId\":\"java\",\"forceCreate\":true}",
        CreateInterviewRequest.class);
    assertThat(mapper.valueToTree(service.createSession(withoutMode)).path("executionMode").asString())
        .isEqualTo("LEGACY_FIXED");
  }

  @Test
  @DisplayName("动态功能关闭时显式模式不能降级成固定面试")
  void declaredDynamicUsesNewFlow() {
    var request = mapper.readValue("""
        {"questionCount":3,"skillId":"java","forceCreate":true,
        "executionMode":"DYNAMIC_FOLLOW_UP"}
        """, CreateInterviewRequest.class);
    assertThatThrownBy(() -> service.createSession(request)).isInstanceOf(BusinessException.class);
  }

  @Test
  @DisplayName("旧答案提交不能用index加一推进动态会话")
  void oldAnswerEndpointRejectsDynamic() {
    assertThatThrownBy(() -> service.submitAnswer(new SubmitAnswerRequest("s", 0, "旧提交")))
        .isInstanceOf(BusinessException.class);
  }

  @Test
  @DisplayName("旧草稿交卷问题和报告入口均拒绝动态会话")
  void oldMutationAndGenerationEndpointsRejectDynamic() {
    assertThatThrownBy(() -> service.saveAnswer(new SubmitAnswerRequest("s", 0, "草稿")))
        .isInstanceOf(BusinessException.class);
    assertThatThrownBy(() -> service.completeInterview("s")).isInstanceOf(BusinessException.class);
    assertThatThrownBy(() -> service.getCurrentQuestion("s")).isInstanceOf(BusinessException.class);
    assertThatThrownBy(() -> service.generateReport("s")).isInstanceOf(BusinessException.class);
  }

  @Test
  @DisplayName("动态报告完成后旧读取地址只读取已存报告")
  void completedDynamicReportIsReadOnly() {
    entity.setStatus(InterviewSessionEntity.SessionStatus.EVALUATED);
    entity.setEvaluateStatus(AsyncTaskStatus.COMPLETED);
    var saved = new InterviewReportDTO("s", 1, null, List.of(), List.of(), "未评估",
        List.of(), List.of(), List.of(), List.of(), InterviewExecutionMode.DYNAMIC_FOLLOW_UP, 1, 2L);
    when(persistence.findCompletedReport("s")).thenReturn(Optional.of(saved));
    assertThat(service.generateReport("s")).isSameAs(saved);
    verifyNoInteractions(cache);
  }

  @Test
  @DisplayName("持久化动态模式必须先于旧缓存判定")
  void cachedLegacyDtoCannotDriveDynamic() {
    var restored = mapper.valueToTree(service.getSession("s"));
    assertThat(restored.path("executionMode").asString()).isEqualTo("DYNAMIC_FOLLOW_UP");
    assertThat(restored.path("questions").get(0).path("question").asString())
        .isEqualTo("已发布动态问题");
  }
}
