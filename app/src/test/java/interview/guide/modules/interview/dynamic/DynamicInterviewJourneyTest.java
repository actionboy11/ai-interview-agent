package interview.guide.modules.interview.dynamic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import interview.guide.common.ai.LlmProviderRegistry;
import interview.guide.common.ai.PromptSanitizer;
import interview.guide.common.ai.StructuredOutputDeadlineExecutor;
import interview.guide.common.ai.StructuredOutputInvoker;
import interview.guide.common.ai.StructuredOutputProperties;
import interview.guide.common.ai.telemetry.AiCallTelemetryRecorder;
import interview.guide.common.config.LlmProviderProperties;
import interview.guide.common.model.AsyncTaskStatus;
import interview.guide.common.exception.BusinessException;
import org.springframework.dao.DataIntegrityViolationException;
import interview.guide.modules.interview.dynamic.dto.DynamicSubmitRequest;
import interview.guide.modules.interview.dynamic.model.AssessmentTarget;
import interview.guide.modules.interview.dynamic.model.DynamicPhase;
import interview.guide.modules.interview.dynamic.model.FollowUpModelDecision;
import interview.guide.modules.interview.dynamic.model.InterviewExecutionMode;
import interview.guide.modules.interview.dynamic.model.SubmissionIntent;
import interview.guide.modules.interview.dynamic.service.DynamicDecisionWorker;
import interview.guide.modules.interview.dynamic.service.DynamicEvaluationOrchestrationService;
import interview.guide.modules.interview.dynamic.service.DynamicInterviewCreationService;
import interview.guide.modules.interview.dynamic.service.DynamicInterviewEvaluationService;
import interview.guide.modules.interview.dynamic.service.DynamicMainQuestionGenerator;
import interview.guide.modules.interview.dynamic.service.DynamicMainQuestionGenerator.MainOutput;
import interview.guide.modules.interview.dynamic.service.DynamicMainQuestionGenerator.MainPoolOutput;
import interview.guide.modules.interview.dynamic.service.FollowUpDecisionService;
import interview.guide.modules.interview.dynamic.service.FollowUpDecisionValidator;
import interview.guide.modules.interview.model.CreateInterviewRequest;
import interview.guide.modules.interview.planning.AdaptiveAllocationPolicy;
import interview.guide.modules.interview.planning.QuestionPlanValidator;
import interview.guide.modules.interview.planning.QuestionPlanningService;
import interview.guide.modules.interview.planning.model.QuestionPlan;
import interview.guide.modules.interview.service.InterviewQuestionProperties;
import interview.guide.modules.interview.service.InterviewQuestionService;
import interview.guide.modules.interview.service.InterviewPersistenceService.CompletionResult;
import interview.guide.modules.interview.skill.InterviewSkillService;
import interview.guide.modules.interview.skill.InterviewSkillService.CategoryDTO;
import interview.guide.modules.memory.service.DerivationResult;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@DisplayName("真实PG跨层动态面试与正式评价记忆完整旅程")
class DynamicInterviewJourneyTest extends DynamicPostgresTestSupport {
  @ParameterizedTest(name = "N={0}, followUps={1}, final={2}")
  @CsvSource({"3,0,SUFFICIENT", "8,0,SUFFICIENT", "8,8,SUFFICIENT",
      "3,0,DONT_KNOW", "3,0,FAILURE"})
  @DisplayName("真实规划至确认画像：计划不变、实际索引稳定、预算正确、每组等权")
  void completeJourney(int mains, int followUps, String last) throws Exception {
    try (var pipeline = new Pipeline()) {
      pipeline.create(mains);
      String planBefore = planJson();
      String poolBefore = runtime().getMainQuestionsJson();
      assertThat(queries.get(owner.getId(), session.getSessionId()).totalQuestions()).isOne();
      assertThat(mapper.writeValueAsString(queries.get(owner.getId(), session.getSessionId())))
          .doesNotContain("assessmentTargets", "主问题2");
      var publishedIndexes = new ArrayList<Integer>();
      int emitted = 0;
      while (runtime().getPhase() != DynamicPhase.COMPLETED) {
        var before = queries.get(owner.getId(), session.getSessionId());
        publishedIndexes.add(before.currentQuestion().questionIndex());
        boolean isLast = runtime().getCurrentMainOrdinal() == mains - 1;
        var intent = isLast && last.equals("DONT_KNOW") ? SubmissionIntent.DONT_KNOW : SubmissionIntent.ANSWER;
        var request = new DynamicSubmitRequest(UUID.randomUUID(), before.currentQuestion().questionIndex(),
            before.version(), intent, intent == SubmissionIntent.ANSWER ? "正式答案" : null);
        commands.saveDraft(owner.getId(), session.getSessionId(), request.questionIndex(),
            request.expectedVersion(), "未提交草稿");
        var receipt = commands.submit(owner.getId(), session.getSessionId(), request);
        assertThat(commands.submit(owner.getId(), session.getSessionId(), request).decisionId())
            .isEqualTo(receipt.decisionId());
        if (receipt.decisionId() != null) {
          assertThat(receipt.runtime().phase()).isEqualTo(DynamicPhase.DECISION_PENDING);
          assertThat(outboxes.findBySession_IdAndFrozenVersion(session.getId(), receipt.runtime().version()))
              .isEmpty();
          assertThat(decisions.findById(receipt.decisionId()).orElseThrow().getDeadline())
              .isEqualTo(NOW.plusSeconds(30));
          boolean append = emitted < followUps && runtime().getGroupUsedFollowUps() < 2;
          int serial = emitted;
          pipeline.script = prompt -> {
            if (isLast && last.equals("FAILURE")) { throw new IllegalStateException("synthetic model outage"); }
            return append ? new FollowUpModelDecision(FollowUpModelDecision.Action.FOLLOW_UP,
                FollowUpModelDecision.Reason.IMPORTANT_GAP, "缺少边界观察", List.of("t1"),
                List.of("t2"), "边界追问" + serial) : sufficient();
          };
          pipeline.worker.process(receipt.decisionId());
          if (append) { emitted++; }
        }
        assertThat(runtime().getMainQuestionsJson()).isEqualTo(poolBefore);
        assertThat(planJson()).isEqualTo(planBefore);
      }
      assertThat(publishedIndexes).containsExactlyElementsOf(
          java.util.stream.IntStream.range(0, mains + followUps).boxed().toList());
      assertThat(runtime().getUsedFollowUps()).isEqualTo(followUps);
      assertThat(runtime().getCompletedMainCount()).isEqualTo(mains);
      assertThat(runtime().getPhase()).isEqualTo(DynamicPhase.COMPLETED);
      var id = freeze();
      var scope = persistence.loadDynamicEvaluation(session.getSessionId(), id).orElseThrow().snapshot();
      pipeline.script = prompt -> evaluationOutput(scope);
      var orchestration = new DynamicEvaluationOrchestrationService(persistence, pipeline.evaluator);
      assertThat(orchestration.evaluate(session.getSessionId(), id)).isEqualTo(CompletionResult.CREATED);
      String reportJson = sessions.findById(session.getId()).orElseThrow().getReportJson();
      pipeline.script = prompt -> { throw new AssertionError("completed reports must never call a model"); };
      assertThat(orchestration.evaluate(session.getSessionId(), id)).isEqualTo(CompletionResult.ALREADY_COMPLETED);
      assertThat(sessions.findById(session.getId()).orElseThrow().getReportJson()).isEqualTo(reportJson);
      var saved = persistence.loadDynamicEvaluation(session.getSessionId(), id).orElseThrow().completedReport();
      assertThat(saved.overallScore()).isEqualTo(60);
      assertThat(saved.totalQuestions()).isEqualTo(mains + followUps);
      assertThat(saved.categoryScores().getFirst().questionCount()).isEqualTo(mains);
      assertThat(derivation.derive(session.getSessionId(), UUID.randomUUID())).isEqualTo(DerivationResult.CREATED);
      assertThat(derivation.derive(session.getSessionId(), UUID.randomUUID()))
          .isEqualTo(DerivationResult.ALREADY_COMPLETED);
      var items = evidence.findByOwnerIdAndInterviewSessionId(owner.getId(), session.getId());
      assertThat(items).hasSize(mains);
      assertThat(items.stream().mapToInt(item -> item.getSources().size()).sum()).isEqualTo(mains + followUps);
      var proposal = proposals.findByOwnerIdAndInterviewSessionId(owner.getId(), session.getId()).getFirst();
      assertThat(proposal.getProposedScore()).isEqualTo(60);
      var profile = profileCommands.accept(owner.getId(), proposal.getId(), proposal.getVersion());
      assertThat(profile.score()).isEqualTo(60);
      assertThat(profile.confirmedEvidenceCount()).isEqualTo(mains);
      assertThat(profile.confidence()).isEqualTo(0.5);
      assertThat(sessions.findById(session.getId()).orElseThrow().getEvaluateStatus())
          .isEqualTo(AsyncTaskStatus.COMPLETED);
      assertThat(planJson()).isEqualTo(planBefore);
      if (last.equals("DONT_KNOW")) {
        assertThat(answers.findBySessionIdOrderByQuestionIndex(session.getId()).getLast().getUserAnswer()).isNull();
        assertThat(items).hasSize(mains);
      }
      if (last.equals("FAILURE")) {
        assertThat(runtime().getNoticeCode()).isEqualTo("MODEL_UNAVAILABLE");
        assertThat(saved.questionDetails().getLast().userAnswer()).isEqualTo("正式答案");
      }
    }
  }

  @Test
  @DisplayName("全部跳过或草稿提前交卷无观测分数为null且不派生记忆")
  void unobservedIsNullAndDoesNotCreateEvidence() throws Exception {
    try (var pipeline = new Pipeline()) {
      pipeline.create(3);
      for (int i = 0; i < 2; i++) {
        var before = queries.get(owner.getId(), session.getSessionId());
        commands.submit(owner.getId(), session.getSessionId(), new DynamicSubmitRequest(UUID.randomUUID(),
            i, before.version(), SubmissionIntent.SKIP, null));
      }
      var before = queries.get(owner.getId(), session.getSessionId());
      commands.saveDraft(owner.getId(), session.getSessionId(), 2, before.version(), "仍是草稿");
      var id = freeze();
      pipeline.script = prompt -> { throw new AssertionError("unobserved must not call model"); };
      var orchestration = new DynamicEvaluationOrchestrationService(persistence, pipeline.evaluator);
      assertThat(orchestration.evaluate(session.getSessionId(), id)).isEqualTo(CompletionResult.CREATED);
      var report = persistence.loadDynamicEvaluation(session.getSessionId(), id).orElseThrow().completedReport();
      assertThat(report.overallScore()).isNull();
      assertThat(report.groupEvaluations()).allSatisfy(group -> assertThat(group.score()).isNull());
      derivation.derive(session.getSessionId(), UUID.randomUUID());
      assertThat(sessions.findById(session.getId()).orElseThrow().getMemoryDerivationStatus())
          .isEqualTo(AsyncTaskStatus.COMPLETED);
      assertThat(evidence.findByOwnerIdAndInterviewSessionId(owner.getId(), session.getId())).isEmpty();
      assertThat(proposals.findByOwnerIdAndInterviewSessionId(owner.getId(), session.getId())).isEmpty();
    }
  }

  @Test
  @DisplayName("有效主问题组与未提交草稿共存时只派生正式组证据")
  void observedGroupAndDraftRemainSeparate() throws Exception {
    try (var pipeline = new Pipeline()) {
      pipeline.create(3);
      var submitted = submitAnswer();
      pipeline.script = prompt -> sufficient();
      pipeline.worker.process(submitted.decisionId());
      var before = queries.get(owner.getId(), session.getSessionId());
      commands.saveDraft(owner.getId(), session.getSessionId(), 1, before.version(), "仍未提交的想法");
      var id = freeze();
      var scope = persistence.loadDynamicEvaluation(session.getSessionId(), id).orElseThrow().snapshot();
      pipeline.script = prompt -> evaluationOutput(scope);
      new DynamicEvaluationOrchestrationService(persistence, pipeline.evaluator).evaluate(session.getSessionId(), id);
      assertThat(derivation.derive(session.getSessionId(), UUID.randomUUID())).isEqualTo(DerivationResult.CREATED);
      assertThat(evidence.findByOwnerIdAndInterviewSessionId(owner.getId(), session.getId())).hasSize(1);
      assertThat(proposals.findByOwnerIdAndInterviewSessionId(owner.getId(), session.getId()).getFirst()
          .getProposedScore()).isEqualTo(90);
      var draft = answers.findBySessionIdOrderByQuestionIndex(session.getId()).getLast();
      assertThat(draft.getUserAnswer()).isEqualTo("仍未提交的想法");
      assertThat(draft.getRequestId()).isNull();
      assertThat(draft.getScore()).isNull();
    }
  }

  @ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {"cleared", "extra", "partial"})
  @DisplayName("真正草稿排除不能掩盖清空、额外或部分正式来源元数据")
  void sourceTamperingStillRejected(String mutation) throws Exception {
    var before = queries.get(owner.getId(), session.getSessionId());
    commands.submit(owner.getId(), session.getSessionId(), new DynamicSubmitRequest(UUID.randomUUID(),
        0, before.version(), SubmissionIntent.DONT_KNOW, null));
    var id = freeze();
    var report = report(id);
    persistence.completeDynamicReport(session.getSessionId(), id, report.frozenVersion(), report);
    if (mutation.equals("partial")) {
      assertThatThrownBy(() -> jdbc().update("update interview_answers set request_fingerprint = null "
          + "where session_id = ?", session.getId())).isInstanceOf(DataIntegrityViolationException.class);
      return;
    }
    if (mutation.equals("cleared")) {
      jdbc().update("update interview_answers set request_id = null, submission_owner_id = null, "
          + "submission_intent = null, request_fingerprint = null where session_id = ?", session.getId());
    } else {
      jdbc().update("update interview_answers set submission_intent = 'DONT_KNOW', request_id = ?, "
          + "request_fingerprint = ?, submission_owner_id = ? where session_id = ? and question_index = 1",
          UUID.randomUUID(), "a".repeat(64), owner.getId(), session.getId());
    }
    assertThatThrownBy(() -> derivation.derive(session.getSessionId(), UUID.randomUUID()))
        .isInstanceOf(BusinessException.class);
    assertThat(evidence.findByOwnerIdAndInterviewSessionId(owner.getId(), session.getId())).isEmpty();
  }

  private String planJson() {
    return jdbc().queryForObject("select plan_json from interview_question_plans where session_id = ?",
        String.class, session.getId());
  }

  private FollowUpModelDecision sufficient() {
    return new FollowUpModelDecision(FollowUpModelDecision.Action.NEXT_MAIN,
        FollowUpModelDecision.Reason.SUFFICIENT, "证据充分", List.of("t1", "t2"), List.of(), null);
  }

  /** Only ChatModel/registry are scripted: planner, prompts, conversion, worker and persistence are real. */
  private class Pipeline implements AutoCloseable {
    Function<Prompt, Object> script;
    final StructuredOutputDeadlineExecutor deadlines;
    final InterviewQuestionService questions;
    final DynamicInterviewCreationService creation;
    final DynamicDecisionWorker worker;
    final DynamicInterviewEvaluationService evaluator;

    Pipeline() throws Exception {
      var settings = new StructuredOutputProperties();
      settings.setStructuredSchemaValidationEnabled(false);
      settings.setStructuredMaxAttempts(1);
      deadlines = new StructuredOutputDeadlineExecutor(settings, clock);
      var invoker = new StructuredOutputInvoker(settings,
          new AiCallTelemetryRecorder(settings, null, List.of()), deadlines);
      var registry = mock(LlmProviderRegistry.class);
      ChatModel chatModel = prompt -> {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        return new ChatResponse(List.of(new Generation(new AssistantMessage(
            mapper.writeValueAsString(script.apply(prompt))))));
      };
      when(registry.getChatClientOrDefault("scripted")).thenReturn(ChatClient.create(chatModel));
      var loader = new DefaultResourceLoader();
      var sanitizer = new PromptSanitizer(new LlmProviderProperties());
      var skills = new InterviewSkillService(registry, invoker, loader, sanitizer);
      questions = new InterviewQuestionService(invoker, skills, new InterviewQuestionProperties(),
          loader, registry, sanitizer);
      var planning = new QuestionPlanningService(currentUser, profileQueries,
          new AdaptiveAllocationPolicy(skills), new QuestionPlanValidator());
      var generator = new DynamicMainQuestionGenerator(registry, invoker, sanitizer, loader);
      creation = new DynamicInterviewCreationService(currentUser, persistence, questions, planning,
          planPersistence, generator, properties);
      var followUpModel = new FollowUpDecisionService(registry, invoker,
          new FollowUpDecisionValidator(properties), sanitizer, loader, clock);
      worker = new DynamicDecisionWorker(store, followUpModel, transitions, mapper, clock);
      evaluator = new DynamicInterviewEvaluationService(registry, invoker, sanitizer, loader);
    }

    void create(int count) {
      script = prompt -> {
        String json = jdbc().queryForObject("select p.plan_json from interview_question_plans p "
            + "join interview_sessions s on s.id = p.session_id where s.owner_user_id = ? "
            + "order by p.id desc limit 1", String.class, owner.getId());
        var plan = mapper.readValue(json, QuestionPlan.class);
        return new MainPoolOutput(plan.slots().stream().map(slot -> new MainOutput(slot.slotId(),
            "主问题" + slot.slotId(), slot.categoryKey(), "Java", slot.sourceMode().name(), "事务观察",
            List.of(new AssessmentTarget("t1", "原理"), new AssessmentTarget("t2", "边界")))).toList());
      };
      var initial = creation.create(new CreateInterviewRequest(null, count, null, true, "scripted",
          "custom", "mid", List.of(new CategoryDTO("JAVA", "Java", "CORE", null, false)),
          null, InterviewExecutionMode.DYNAMIC_FOLLOW_UP));
      session = sessions.findBySessionId(initial.sessionId()).orElseThrow();
      assertThat(initial.plannedMainQuestionCount()).isEqualTo(count);
    }

    @Override public void close() { deadlines.close(); questions.destroy(); }
  }
}
