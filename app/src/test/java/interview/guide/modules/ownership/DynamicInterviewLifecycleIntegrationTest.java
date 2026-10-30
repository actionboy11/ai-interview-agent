package interview.guide.modules.ownership;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

import interview.guide.common.exception.BusinessException;
import interview.guide.modules.interview.dynamic.dto.DynamicSubmitRequest;
import interview.guide.modules.interview.dynamic.dto.DynamicSubmissionReceipt;
import interview.guide.modules.interview.dynamic.DynamicInterviewController;
import interview.guide.modules.interview.dynamic.service.DynamicInterviewSessionSupport;
import interview.guide.common.exception.GlobalExceptionHandler;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import interview.guide.modules.interview.dynamic.model.AssessmentTarget;
import interview.guide.modules.interview.dynamic.model.DynamicInterviewRuntimeEntity;
import interview.guide.modules.interview.dynamic.model.DynamicMainQuestion;
import interview.guide.modules.interview.dynamic.model.DynamicPhase;
import interview.guide.modules.interview.dynamic.model.FollowUpDecisionEntity.Status;
import interview.guide.modules.interview.dynamic.model.InterviewExecutionMode;
import interview.guide.modules.interview.dynamic.model.SubmissionIntent;
import interview.guide.modules.interview.dynamic.repository.DynamicInterviewRuntimeRepository;
import interview.guide.modules.interview.dynamic.repository.DynamicEvaluationOutboxRepository;
import interview.guide.modules.interview.dynamic.repository.FollowUpDecisionRepository;
import interview.guide.modules.interview.dynamic.service.DynamicInterviewCommandService;
import interview.guide.modules.interview.dynamic.service.DynamicInterviewProperties;
import interview.guide.modules.interview.dynamic.service.DynamicInterviewQueryService;
import interview.guide.modules.interview.model.CreateInterviewRequest;
import interview.guide.modules.interview.model.InterviewQuestionDTO;
import interview.guide.modules.interview.model.InterviewSessionEntity;
import interview.guide.modules.interview.repository.InterviewAnswerRepository;
import interview.guide.modules.memory.service.AbilityMemoryDeletionService;
import interview.guide.modules.resume.model.ResumeEntity;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.hibernate.resource.jdbc.spi.StatementInspector;
import java.util.ArrayList;
import interview.guide.modules.interview.planning.model.QuestionPlan;
import interview.guide.modules.interview.planning.model.QuestionSlot;
import interview.guide.modules.interview.planning.model.QuestionPurpose;
import interview.guide.modules.interview.planning.model.QuestionSourceMode;
import interview.guide.modules.interview.planning.model.PlanningMode;

@DisplayName("动态面试API必要生命周期边界")
@TestPropertySource(properties = "spring.jpa.properties.hibernate.session_factory.statement_inspector="
    + "interview.guide.modules.ownership.DynamicInterviewLifecycleIntegrationTest$LockSql")
class DynamicInterviewLifecycleIntegrationTest extends WorkflowOwnershipTestSupport {
  public static class LockSql implements StatementInspector {
    static final ThreadLocal<List<String>> SQL = ThreadLocal.withInitial(ArrayList::new);
    @Override public String inspect(String sql) { SQL.get().add(sql); return sql; }
  }
  @Autowired DynamicInterviewRuntimeRepository runtimes;
  @Autowired DynamicEvaluationOutboxRepository outboxes;
  @Autowired FollowUpDecisionRepository decisions;
  @Autowired InterviewAnswerRepository answers;
  @Autowired DynamicInterviewCommandService commands;
  @Autowired DynamicInterviewQueryService queries;
  @Autowired DynamicInterviewProperties properties;
  @Autowired AbilityMemoryDeletionService deletions;
  @Autowired DynamicInterviewSessionSupport sessionSupport;

  @BeforeEach
  void dynamicPolicy() {
    properties.setEnabled(false);
    properties.setMaxAnswerLength(20);
  }

  @Test
  @DisplayName("启用时显式动态创建只暴露已发布题并保留未完成会话语义")
  void declaredDynamicUsesNewFlow() {
    properties.setEnabled(true);
    when(planning.plan(any())).thenReturn(new QuestionPlan(1, PlanningMode.FIXED,
        pool().stream().map(main -> new QuestionSlot(main.slotId(), "java", QuestionPurpose.BASELINE,
            "mid", QuestionSourceMode.DIRECTION, List.of(), List.of(), null)).toList(), null));
    when(dynamicGenerator.generate(any(), any(), any(), any())).thenReturn(pool());
    var resume = resume(first, UUID.randomUUID().toString());
    var request = new CreateInterviewRequest(null, 3, resume.getId(), true, null,
        "java", "mid", null, null, InterviewExecutionMode.DYNAMIC_FOLLOW_UP);
    var created = interviewService.createSession(request);
    var json = mapper.valueToTree(created);
    assertThat(json.path("executionMode").asString()).isEqualTo("DYNAMIC_FOLLOW_UP");
    assertThat(json.path("totalQuestions").asInt()).isEqualTo(1);
    assertThat(json.path("questions")).hasSize(1);
    assertThat(json.toString()).doesNotContain("assessmentTargets", "mainQuestionsJson", "未发布主问题");
    var resumed = interviewService.createSession(new CreateInterviewRequest(null, 3, resume.getId(),
        false, null, "java", "mid", null, null, InterviewExecutionMode.DYNAMIC_FOLLOW_UP));
    assertThat(resumed.sessionId()).isEqualTo(created.sessionId());
  }

  @Test
  @DisplayName("配置输入上限在正式答案与草稿写库前生效，等长允许")
  void inputBoundsBeforePersistence() {
    var session = dynamic(null);
    assertThatThrownBy(() -> commands.saveDraft(first.getId(), session.getSessionId(), 0, 0,
        "x".repeat(21))).isInstanceOf(BusinessException.class);
    assertThatThrownBy(() -> submit(session, SubmissionIntent.ANSWER, "x".repeat(21)))
        .isInstanceOf(BusinessException.class);
    assertThat(answers.findBySession_SessionIdOrderByQuestionIndex(session.getSessionId())).isEmpty();
    commands.saveDraft(first.getId(), session.getSessionId(), 0, 0, "x".repeat(20));
    submit(session, SubmissionIntent.ANSWER, "x".repeat(20));
    assertThat(answers.findBySession_SessionIdOrderByQuestionIndex(session.getSessionId()))
        .singleElement().satisfies(answer -> assertThat(answer.getRequestId()).isNotNull());
  }

  @Test
  @DisplayName("空文本不会与跳过在刷新和详情后保留正式意图，草稿仍未提交")
  void intentsSurviveRestoreAndDetail() {
    for (var intent : List.of(SubmissionIntent.DONT_KNOW, SubmissionIntent.SKIP)) {
      var session = dynamic(null);
      submit(session, intent, null);
      var restored = mapper.valueToTree(interviewService.getSession(session.getSessionId()));
      assertThat(restored.path("questions").get(0).path("submissionIntent").asString())
          .isEqualTo(intent.name());
      assertThat(restored.path("questions").get(0).path("userAnswer").isNull()).isTrue();
      var detail = mapper.valueToTree(interviewHistory.getInterviewDetail(session.getSessionId()));
      assertThat(detail.path("answers").get(0).path("submissionIntent").asString())
          .isEqualTo(intent.name());
      assertThat(detail.path("questions").get(0).path("submissionIntent").asString())
          .isEqualTo(intent.name());
    }
    var draft = dynamic(null);
    commands.saveDraft(first.getId(), draft.getSessionId(), 0, 0, "草稿");
    var restored = mapper.valueToTree(interviewService.getSession(draft.getSessionId()));
    assertThat(restored.path("questions").get(0).path("submissionIntent").isNull()).isTrue();
  }

  @Test
  @DisplayName("过期动态会话通过标准冻结取消决策并生成唯一评价outbox")
  void cleanupUsesCanonicalCompletion() {
    var session = dynamic(null);
    var receipt = submit(session, SubmissionIntent.ANSWER, "回答");
    var saved = sessions.findById(session.getId()).orElseThrow();
    saved.setCreatedAt(LocalDateTime.now().minusHours(30));
    sessions.saveAndFlush(saved);
    interviewService.cleanupStaleSessions();
    var runtime = runtimes.findBySession_SessionId(session.getSessionId()).orElseThrow();
    assertThat(runtime.getPhase()).isEqualTo(DynamicPhase.COMPLETED);
    assertThat(runtime.getFrozenVersion()).isNotNull();
    assertThat(decisions.findById(receipt.decisionId()).orElseThrow().getStatus()).isEqualTo(Status.CANCELLED);
    assertThat(outboxes.findBySession_IdAndFrozenVersion(session.getId(), runtime.getFrozenVersion()))
        .isPresent();
    interviewService.cleanupStaleSessions();
    assertThat(queries.get(first.getId(), session.getSessionId()).version()).isEqualTo(runtime.getVersion());
  }

  @Test
  @DisplayName("直接记忆删除、单删、简历批删和简历持久化直删均清除动态子记录")
  void everyActualDeletePathRemovesDynamicChildren() {
    var direct = dynamic(null);
    var decision = submit(direct, SubmissionIntent.ANSWER, "回答").decisionId();
    deletions.deleteOwnedSessions(first.getId(), List.of(direct.getId()));
    assertDeleted(direct, decision);
    var single = dynamic(null);
    decision = submit(single, SubmissionIntent.ANSWER, "回答").decisionId();
    interviewService.deleteSession(single.getSessionId());
    assertDeleted(single, decision);
    var resume = resume(first, UUID.randomUUID().toString());
    var bulk = dynamic(resume);
    decision = submit(bulk, SubmissionIntent.ANSWER, "回答").decisionId();
    interviewService.deleteSessionsByResumeId(resume.getId());
    assertDeleted(bulk, decision);
    var resumeDirect = resume(first, UUID.randomUUID().toString());
    var last = dynamic(resumeDirect);
    decision = submit(last, SubmissionIntent.ANSWER, "回答").decisionId();
    resumePersistence.deleteResume(resumeDirect.getId());
    assertDeleted(last, decision);
  }

  @Test
  @DisplayName("真实删除事务先锁动态runtime，再锁decision和会话，防并发推进绕过")
  void deletionLocksRuntimeBeforeDecisionAndSession() {
    var session = dynamic(null);
    submit(session, SubmissionIntent.ANSWER, "回答");
    LockSql.SQL.get().clear();
    deletions.deleteOwnedSessions(first.getId(), List.of(session.getId()));
    var locks = LockSql.SQL.get().stream().filter(sql -> sql.contains("for update")).toList();
    assertThat(locks).anyMatch(sql -> sql.contains("interview_dynamic_runtime"));
    assertThat(locks.get(1)).contains("interview_dynamic_runtime");
    assertThat(locks.get(1)).doesNotContain(" join ");
    assertThat(locks.get(2)).contains("interview_follow_up_decisions");
    LockSql.SQL.remove();
  }

  @Test
  @DisplayName("动态模式和计划主问题数在会话、简历历史、详情一致")
  void historyMetadataIsConsistent() {
    var resume = resume(first, UUID.randomUUID().toString());
    var session = dynamic(resume);
    var list = mapper.valueToTree(interviewHistory.listSessions());
    var item = list.get(0);
    assertThat(item.path("executionMode").asString()).isEqualTo("DYNAMIC_FOLLOW_UP");
    assertThat(item.path("plannedMainQuestionCount").asInt()).isEqualTo(3);
    var history = mapper.valueToTree(resumeHistory.getResumeDetail(resume.getId()))
        .path("interviews").get(0);
    assertThat(history.path("plannedMainQuestionCount").asInt()).isEqualTo(3);
    var detail = mapper.valueToTree(interviewHistory.getInterviewDetail(session.getSessionId()));
    assertThat(detail.path("plannedMainQuestionCount").asInt()).isEqualTo(3);
  }

  @Test
  @DisplayName("动态写HTTP校验版本并在Redis故障后返回已提交的成功，缓存网络在事务外")
  void durableHttpWritesSurviveCacheFailure() throws Exception {
    var session = dynamic(null);
    var mvc = MockMvcBuilders.standaloneSetup(new DynamicInterviewController(currentUser,
        queries, commands, sessionSupport)).setControllerAdvice(new GlobalExceptionHandler()).build();
    String route = "/api/interview/sessions/" + session.getSessionId() + "/dynamic";
    doAnswer(call -> {
      assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
      throw new IllegalStateException("test-only Redis outage");
    }).when(redis).delete(any());
    mvc.perform(put(route + "/draft").contentType("application/json")
        .content("{\"questionIndex\":0,\"expectedVersion\":0,\"answer\":\"草稿\"}"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(200));
    assertThat(queries.get(first.getId(), session.getSessionId()).currentQuestion().userAnswer())
        .isEqualTo("草稿");
    mvc.perform(post(route + "/complete").contentType("application/json").content("{}"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(400));
    assertThat(queries.get(first.getId(), session.getSessionId()).phase()).isEqualTo(DynamicPhase.WAITING_ANSWER);
    mvc.perform(post(route + "/answers").contentType("application/json")
        .content(mapper.writeValueAsString(new DynamicSubmitRequest(UUID.randomUUID(), 0, 0,
            SubmissionIntent.DONT_KNOW, null))))
        .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(200))
        .andExpect(jsonPath("$.data.runtime.currentQuestion.questionIndex").value(1));
    long version = queries.get(first.getId(), session.getSessionId()).version();
    mvc.perform(post(route + "/complete").contentType("application/json")
        .content("{\"expectedVersion\":" + version + "}"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(200))
        .andExpect(jsonPath("$.data.phase").value("COMPLETED"));
    assertThat(queries.get(first.getId(), session.getSessionId()).phase()).isEqualTo(DynamicPhase.COMPLETED);
  }

  @Test
  @DisplayName("会话和简历历史每个列表只批量查询一次动态元数据")
  void metadataBatchUsesOneQueryPerList() {
    var resume = resume(first, UUID.randomUUID().toString());
    dynamic(resume);
    dynamic(resume);
    dynamic(resume);
    LockSql.SQL.get().clear();
    assertThat(interviewHistory.listSessions()).hasSize(3);
    assertThat(LockSql.SQL.get().stream().filter(sql -> sql.contains("from interview_dynamic_runtime")))
        .hasSize(1);
    LockSql.SQL.get().clear();
    assertThat(resumeHistory.getResumeDetail(resume.getId()).interviews()).hasSize(3);
    assertThat(LockSql.SQL.get().stream().filter(sql -> sql.contains("from interview_dynamic_runtime")))
        .hasSize(1);
    LockSql.SQL.remove();
  }

  private void assertDeleted(InterviewSessionEntity session, UUID decision) {
    assertThat(sessions.findById(session.getId())).isEmpty();
    assertThat(runtimes.findBySession_SessionId(session.getSessionId())).isEmpty();
    assertThat(decisions.findById(decision)).isEmpty();
  }

  private DynamicSubmissionReceipt submit(
      InterviewSessionEntity session, SubmissionIntent intent, String answer) {
    return commands.submit(first.getId(), session.getSessionId(), new DynamicSubmitRequest(
        UUID.randomUUID(), 0, 0, intent, answer));
  }

  private InterviewSessionEntity dynamic(ResumeEntity resume) {
    var session = new InterviewSessionEntity();
    session.setOwner(first);
    session.setResume(resume);
    session.setSessionId(UUID.randomUUID().toString());
    session.setExecutionMode(InterviewExecutionMode.DYNAMIC_FOLLOW_UP);
    session.setTotalQuestions(1);
    session.setQuestionsJson(mapper.writeValueAsString(List.of(InterviewQuestionDTO.planned(0,
        "已发布主问题", "java", "Java", "事务", false, null, "slot-01"))));
    session = sessions.saveAndFlush(session);
    runtimes.saveAndFlush(new DynamicInterviewRuntimeEntity(session, mapper.writeValueAsString(pool()),
        mapper.writeValueAsString(properties.snapshot(3)), 3, "slot-01"));
    return session;
  }

  private List<DynamicMainQuestion> pool() {
    return java.util.stream.IntStream.range(0, 3).mapToObj(i -> new DynamicMainQuestion(
        i == 0 ? "slot-01" : "slot-" + (i + 1), i == 0 ? "已发布主问题" : "未发布主问题" + i,
        "java", "Java", "事务", List.of(new AssessmentTarget("a", "原理"),
        new AssessmentTarget("b", "边界")), "mid", List.of())).toList();
  }
}
