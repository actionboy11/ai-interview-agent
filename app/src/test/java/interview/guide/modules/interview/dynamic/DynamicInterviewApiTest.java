package interview.guide.modules.interview.dynamic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import interview.guide.common.exception.GlobalExceptionHandler;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.auth.model.UserEntity;
import interview.guide.modules.auth.model.UserStatus;
import interview.guide.modules.auth.security.CurrentUserProvider;
import interview.guide.modules.interview.dynamic.model.DynamicInterviewRuntimeEntity;
import interview.guide.modules.interview.dynamic.model.DynamicPhase;
import interview.guide.modules.interview.dynamic.repository.DynamicInterviewRuntimeRepository;
import interview.guide.modules.interview.dynamic.service.DynamicInterviewQueryService;
import interview.guide.modules.interview.dynamic.service.DynamicInterviewCommandService;
import interview.guide.modules.interview.dynamic.service.DynamicInterviewSessionSupport;
import interview.guide.modules.interview.model.InterviewQuestionDTO;
import interview.guide.modules.interview.model.InterviewSessionEntity;
import interview.guide.modules.interview.repository.InterviewAnswerRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

@DisplayName("动态运行快照HTTP契约")
class DynamicInterviewApiTest {
  final ObjectMapper mapper = new ObjectMapper();
  final CurrentUserProvider user = mock(CurrentUserProvider.class);
  final DynamicInterviewRuntimeRepository runtimes = mock(DynamicInterviewRuntimeRepository.class);
  final DynamicInterviewQueryService queries = new DynamicInterviewQueryService(
      runtimes, mock(InterviewAnswerRepository.class), mapper);
  final UUID ownerId = UUID.randomUUID();
  final DynamicInterviewRuntimeEntity runtime = new DynamicInterviewRuntimeEntity();
  MockMvc mvc;

  @BeforeEach
  void setup() throws Exception {
    var owner = new UserEntity();
    owner.setId(ownerId);
    owner.setStatus(UserStatus.ACTIVE);
    var session = new InterviewSessionEntity();
    session.setOwner(owner);
    session.setSessionId("s");
    session.setQuestionsJson(mapper.writeValueAsString(List.of(
        InterviewQuestionDTO.create(0, "已发布问题", "JAVA", "Java"))));
    runtime.setOwner(owner);
    runtime.setSession(session);
    runtime.setPhase(DynamicPhase.WAITING_ANSWER);
    runtime.setVersion(0L);
    ReflectionTestUtils.setField(runtime, "plannedMainQuestionCount", 3);
    when(user.requireUserId()).thenReturn(ownerId);
    when(runtimes.findByOwner_IdAndSession_SessionId(ownerId, "s"))
        .thenReturn(Optional.of(runtime));
    var controller = new DynamicInterviewController(user, queries,
        mock(DynamicInterviewCommandService.class), mock(DynamicInterviewSessionSupport.class));
    mvc = MockMvcBuilders.standaloneSetup(controller)
        .setControllerAdvice(new GlobalExceptionHandler()).build();
  }

  @Test
  @DisplayName("动态GET直接读取数据库且重复刷新没有推进副作用")
  void getRuntimeHasNoSideEffects() throws Exception {
    for (int i = 0; i < 2; i++) {
      mvc.perform(get("/api/interview/sessions/s/dynamic"))
          .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(200))
          .andExpect(jsonPath("$.data.plannedMainQuestionCount").value(3))
          .andExpect(jsonPath("$.data.currentQuestion.question").value("已发布问题"))
          .andExpect(jsonPath("$.data.mainQuestionsJson").doesNotExist());
    }
    assertThat(runtime.getPhase()).isEqualTo(DynamicPhase.WAITING_ANSWER);
    assertThat(runtime.getCurrentQuestionIndex()).isZero();
    assertThat(runtime.getPendingDecisionId()).isNull();
  }

  @Test
  @DisplayName("动态快照越权访问统一返回业务未找到")
  void ownershipEnforced() throws Exception {
    when(user.requireUserId()).thenReturn(UUID.randomUUID());
    mvc.perform(get("/api/interview/sessions/s/dynamic"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.code")
            .value(ErrorCode.INTERVIEW_SESSION_NOT_FOUND.getCode()));
  }
}
