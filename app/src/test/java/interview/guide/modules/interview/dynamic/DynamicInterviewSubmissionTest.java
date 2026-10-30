package interview.guide.modules.interview.dynamic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import interview.guide.common.exception.BusinessException;
import interview.guide.modules.auth.model.UserEntity;
import interview.guide.modules.auth.model.UserRole;
import interview.guide.modules.auth.model.UserStatus;
import interview.guide.modules.interview.dynamic.dto.DynamicSubmitRequest;
import interview.guide.modules.interview.dynamic.dto.DynamicSubmissionReceipt;
import interview.guide.modules.interview.dynamic.model.AssessmentTarget;
import interview.guide.modules.interview.dynamic.model.DynamicInterviewRuntimeEntity;
import interview.guide.modules.interview.dynamic.model.DynamicMainQuestion;
import interview.guide.modules.interview.dynamic.model.DynamicPhase;
import interview.guide.modules.interview.dynamic.model.DynamicGroupEndReason;
import interview.guide.modules.interview.dynamic.model.DynamicEvaluationOutboxEntity;
import interview.guide.modules.interview.dynamic.model.FollowUpDecisionEntity;
import interview.guide.modules.interview.dynamic.model.FollowUpDecisionEntity.Action;
import interview.guide.modules.interview.dynamic.model.FollowUpDecisionEntity.Reason;
import interview.guide.modules.interview.dynamic.model.FollowUpDecisionEntity.Status;
import interview.guide.modules.interview.dynamic.model.InterviewExecutionMode;
import interview.guide.modules.interview.dynamic.model.SubmissionIntent;
import interview.guide.modules.interview.dynamic.repository.DynamicInterviewRuntimeRepository;
import interview.guide.modules.interview.dynamic.repository.FollowUpDecisionRepository;
import interview.guide.modules.interview.dynamic.repository.DynamicEvaluationOutboxRepository;
import interview.guide.modules.interview.dynamic.service.DynamicAdvancePolicy;
import interview.guide.modules.interview.dynamic.service.DynamicInterviewCommandService;
import interview.guide.modules.interview.dynamic.service.DynamicInterviewQueryService;
import interview.guide.modules.interview.dynamic.service.DynamicInterviewProperties;
import interview.guide.modules.interview.dynamic.service.DynamicInterviewTransitionService;
import interview.guide.modules.interview.dynamic.service.DynamicInterviewTransitionService.ApplicationResult;
import interview.guide.modules.interview.dynamic.service.DynamicInterviewTransitionService.DecisionApplication;
import interview.guide.modules.interview.dynamic.service.DynamicInterviewTransitionService.FrozenSnapshot;
import interview.guide.modules.interview.model.InterviewAnswerEntity;
import interview.guide.modules.interview.model.InterviewQuestionDTO;
import interview.guide.modules.interview.model.InterviewSessionEntity;
import interview.guide.modules.interview.repository.InterviewAnswerRepository;
import interview.guide.modules.resume.model.ResumeEntity;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.transaction.autoconfigure.TransactionAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(classes = DynamicInterviewSubmissionTest.JpaApplication.class, properties = {
    "spring.datasource.url=jdbc:h2:mem:dynamic-submission;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    "spring.datasource.driver-class-name=org.h2.Driver",
    "spring.datasource.username=sa", "spring.datasource.password=",
    "spring.jpa.hibernate.ddl-auto=create-drop", "spring.jpa.show-sql=false"
})
@DisplayName("动态作答事务状态机")
class DynamicInterviewSubmissionTest {
  static final Instant NOW = Instant.parse("2026-10-29T08:00:00Z");

  @Configuration(proxyBeanMethods = false)
  @EntityScan("interview.guide.modules")
  @EnableJpaRepositories(basePackageClasses = {
      DynamicInterviewRuntimeRepository.class, InterviewAnswerRepository.class})
  @ImportAutoConfiguration({DataSourceAutoConfiguration.class, HibernateJpaAutoConfiguration.class,
      TransactionAutoConfiguration.class})
  @Import({DynamicAdvancePolicy.class, DynamicInterviewCommandService.class,
      DynamicInterviewQueryService.class, DynamicInterviewTransitionService.class})
  static class JpaApplication {
    @Bean DynamicInterviewProperties properties() { return new DynamicInterviewProperties(); }
    @Bean ObjectMapper mapper() { return JsonMapper.builder().build(); }
    @Bean MutableClock clock() { return new MutableClock(); }
  }

  @Autowired EntityManager em;
  @Autowired TransactionTemplate transactions;
  @Autowired ObjectMapper mapper;
  @Autowired DynamicInterviewCommandService commands;
  @Autowired DynamicInterviewQueryService queries;
  @Autowired DynamicInterviewRuntimeRepository runtimes;
  @Autowired FollowUpDecisionRepository decisions;
  @Autowired DynamicEvaluationOutboxRepository outboxes;
  @Autowired InterviewAnswerRepository answers;
  @Autowired DynamicInterviewTransitionService transitions;
  @Autowired MutableClock clock;
  UserEntity owner;
  InterviewSessionEntity session;

  @BeforeEach
  void setup() {
    clock.now = NOW;
    String key = UUID.randomUUID().toString();
    owner = UserEntity.builder().username(key).normalizedUsername(key).displayName(key)
        .passwordHash("test-only").role(UserRole.USER).status(UserStatus.ACTIVE)
        .createdAt(NOW).updatedAt(NOW).build();
    transactions.executeWithoutResult(tx -> em.persist(owner));
    session = createSession(3);
  }

  @Test
  @DisplayName("答案和唯一待决策任务原子持久化并保留30秒绝对期限")
  void answerCreatesSinglePendingDecision() {
    var receipt = commands.submit(owner.getId(), session.getSessionId(), request(0, 0, SubmissionIntent.ANSWER, "原文"));
    assertThat(receipt).isNotNull();
    assertThat(receipt.runtime().phase()).isEqualTo(DynamicPhase.DECISION_PENDING);
    assertThat(receipt.runtime().version()).isEqualTo(1);
    assertThat(receipt.runtime().usedFollowUps()).isZero();
    var task = decisions.findById(receipt.decisionId()).orElseThrow();
    assertThat(task.getDeadline()).isEqualTo(NOW.plusSeconds(30));
    assertThat(task.getExpectedRuntimeVersion()).isEqualTo(1);
    assertThat(task.getContextJson()).contains("原文", "target-a");
    assertThat(formalAnswers()).hasSize(1);
    assertThat(queries.get(owner.getId(), session.getSessionId())).isEqualTo(receipt.runtime());
  }

  @Test
  @DisplayName("不会和跳过保留空文本真实意图且下一主问题只追加一次")
  void dontKnowAndSkipEndGroupWithoutModel() {
    var first = commands.submit(owner.getId(), session.getSessionId(), request(0, 0, SubmissionIntent.DONT_KNOW, ""));
    assertThat(first).isNotNull();
    assertThat(first.decisionId()).isNull();
    assertThat(first.runtime().currentQuestion().questionIndex()).isEqualTo(1);
    var second = commands.submit(owner.getId(), session.getSessionId(), request(1, first.runtime().version(), SubmissionIntent.SKIP, ""));
    assertThat(second.runtime().currentMainOrdinal()).isEqualTo(3);
    assertThat(second.runtime().currentQuestion().questionIndex()).isEqualTo(2);
    assertThat(second.runtime().totalQuestions()).isEqualTo(3);
    assertThat(formalAnswers()).extracting(a -> a.getSubmissionIntent())
        .containsExactly(SubmissionIntent.DONT_KNOW, SubmissionIntent.SKIP);
    assertThat(formalAnswers()).allSatisfy(a -> assertThat(a.getUserAnswer()).isEmpty());
    assertThat(runtime().getGroupEndReasonsJson()).contains("DONT_KNOW", "SKIP");
  }

  @Test
  @DisplayName("最后主问题普通回答仍等待决策不能按数量直接结束")
  void lastMainWaitsForDecision() {
    session = createSession(1);
    var receipt = commands.submit(owner.getId(), session.getSessionId(), request(0, 0, SubmissionIntent.ANSWER, "回答"));
    assertThat(receipt).isNotNull();
    assertThat(receipt.runtime().phase()).isEqualTo(DynamicPhase.DECISION_PENDING);
    assertThat(receipt.runtime().completedMainCount()).isZero();
    assertThat(runtime().getFrozenVersion()).isNull();
  }

  @Test
  @DisplayName("合法网络重试优先请求指纹返回原标识及最新运行状态")
  void duplicateRequestReturnsLatestRuntime() {
    var request = request(0, 0, SubmissionIntent.SKIP, "");
    var first = commands.submit(owner.getId(), session.getSessionId(), request);
    assertThat(first).isNotNull();
    var second = commands.submit(owner.getId(), session.getSessionId(), request(1, first.runtime().version(), SubmissionIntent.SKIP, ""));
    var retry = commands.submit(owner.getId(), session.getSessionId(), request);
    assertThat(retry.requestId()).isEqualTo(first.requestId());
    assertThat(retry.decisionId()).isEqualTo(first.decisionId());
    assertThat(retry.runtime()).isEqualTo(second.runtime());
    assertThat(formalAnswers()).hasSize(2);
  }

  @Test
  @DisplayName("陈旧草稿不能覆盖正式提交正文或元数据")
  void staleDraftCannotOverwriteSubmittedAnswer() {
    commands.saveDraft(owner.getId(), session.getSessionId(), 0, 0, "草稿");
    assertThat(answers.findBySession_SessionIdAndQuestionIndex(session.getSessionId(), 0))
        .hasValueSatisfying(a -> assertThat(a.getRequestId()).isNull());
    var receipt = commands.submit(owner.getId(), session.getSessionId(), request(0, 0, SubmissionIntent.ANSWER, "正式"));
    assertThatThrownBy(() -> commands.saveDraft(owner.getId(), session.getSessionId(), 0, 0, "迟到"))
        .isInstanceOf(BusinessException.class);
    assertThat(formalAnswers()).singleElement().satisfies(a -> {
      assertThat(a.getUserAnswer()).isEqualTo("正式");
      assertThat(a.getRequestId()).isEqualTo(receipt.requestId());
    });
  }

  @Test
  @DisplayName("相同请求ID不同内容和跨会话复用被拒绝")
  void sameRequestDifferentPayloadRejected() {
    var request = request(0, 0, SubmissionIntent.ANSWER, "原文");
    commands.submit(owner.getId(), session.getSessionId(), request);
    assertThatThrownBy(() -> commands.submit(owner.getId(), session.getSessionId(),
        new DynamicSubmitRequest(request.requestId(), 0, 0, SubmissionIntent.ANSWER, "改变")))
        .isInstanceOf(BusinessException.class);
    var other = createSession(1);
    assertThatThrownBy(() -> commands.submit(owner.getId(), other.getSessionId(), request))
        .isInstanceOf(BusinessException.class);
  }

  @Test
  @DisplayName("两次追问始终指向主问题，下一组追加且全场N预算只在发布后消耗")
  void appendOnlyAndBudgetConsumedOnPublication() {
    String originalPool = runtime().getMainQuestionsJson();
    var first = submitAnswer();
    assertThat(apply(first)).isEqualTo(ApplicationResult.APPLIED);
    var current = queries.get(owner.getId(), session.getSessionId());
    assertThat(current.currentQuestion().questionIndex()).isEqualTo(1);
    assertThat(current.currentQuestion().parentQuestionIndex()).isZero();
    assertThat(current.usedFollowUps()).isEqualTo(1);
    assertThat(apply(submitAnswer())).isEqualTo(ApplicationResult.APPLIED);
    current = queries.get(owner.getId(), session.getSessionId());
    assertThat(current.currentQuestion().questionIndex()).isEqualTo(2);
    assertThat(current.currentQuestion().parentQuestionIndex()).isZero();
    var groupEnd = commands.submit(owner.getId(), session.getSessionId(), request(2,
        current.version(), SubmissionIntent.ANSWER, "回答第二追问"));
    assertThat(groupEnd.decisionId()).isNull();
    assertThat(groupEnd.runtime().currentQuestion().questionIndex()).isEqualTo(3);
    assertThat(groupEnd.runtime().currentMainOrdinal()).isEqualTo(2);
    assertThat(apply(submitAnswer())).isEqualTo(ApplicationResult.APPLIED);
    current = queries.get(owner.getId(), session.getSessionId());
    assertThat(current.currentQuestion().questionIndex()).isEqualTo(4);
    assertThat(current.currentQuestion().parentQuestionIndex()).isEqualTo(3);
    assertThat(current.usedFollowUps()).isEqualTo(3);
    var totalEnd = commands.submit(owner.getId(), session.getSessionId(), request(4,
        current.version(), SubmissionIntent.ANSWER, "最后预算"));
    assertThat(totalEnd.decisionId()).isNull();
    assertThat(totalEnd.runtime().currentQuestion().questionIndex()).isEqualTo(5);
    assertThat(runtime().getMainQuestionsJson()).isEqualTo(originalPool);
    var actual = transactions.execute(tx -> mapper.readTree(
        em.find(InterviewSessionEntity.class, session.getId()).getQuestionsJson()));
    assertThat(actual.get(0).get("question").asString()).isEqualTo("主问题0");
    assertThat(actual.get(3).get("question").asString()).isEqualTo("主问题1");
    assertThat(actual.get(5).get("question").asString()).isEqualTo("主问题2");
  }

  @Test
  @DisplayName("等待决策时提前交卷取消任务并冻结一次，迟到结果不可追加")
  void completeWhileDecisionPendingFencesLateAppend() {
    var receipt = submitAnswer();
    UUID token = claim(receipt.decisionId());
    var completed = commands.complete(owner.getId(), session.getSessionId(), receipt.runtime().version());
    assertThat(completed.phase()).isEqualTo(DynamicPhase.COMPLETED);
    assertThat(completed.currentQuestion()).isNull();
    assertThat(decisions.findById(receipt.decisionId()).orElseThrow().getStatus()).isEqualTo(Status.CANCELLED);
    assertThat(transitions.applyDecision(session.getSessionId(), receipt.decisionId(), token, followUp("迟到")))
        .isEqualTo(ApplicationResult.IGNORED);
    assertThat(commands.complete(owner.getId(), session.getSessionId(), 0)).isEqualTo(completed);
    var outbox = outboxes.findBySession_IdAndFrozenVersion(session.getId(), completed.version()).orElseThrow();
    var frozen = mapper.readValue(outbox.getFrozenSnapshotJson(), FrozenSnapshot.class);
    assertThat(frozen.questions()).hasSize(1);
    assertThat(frozen.answers()).hasSize(1);
    assertThat(frozen.mainQuestions()).hasSize(3);
    assertThat(frozen.groupEnds().groups()).containsEntry("slot-0", DynamicGroupEndReason.EARLY_COMPLETE);
    assertThat(runtime().getVersion()).isEqualTo(completed.version());
  }

  @Test
  @DisplayName("最后主问题仍可追问，明确不会后只冻结正式答案并生成一个评价任务")
  void lastMainFollowUpThenFreeze() {
    session = createSession(1);
    var receipt = submitAnswer();
    assertThat(apply(receipt)).isEqualTo(ApplicationResult.APPLIED);
    var latest = queries.get(owner.getId(), session.getSessionId());
    var done = commands.submit(owner.getId(), session.getSessionId(), request(1,
        latest.version(), SubmissionIntent.DONT_KNOW, ""));
    assertThat(done.runtime().phase()).isEqualTo(DynamicPhase.COMPLETED);
    assertThat(done.runtime().completedMainCount()).isEqualTo(1);
    var frozen = mapper.readValue(outboxes.findBySession_IdAndFrozenVersion(session.getId(),
        done.runtime().version()).orElseThrow().getFrozenSnapshotJson(), FrozenSnapshot.class);
    assertThat(frozen.answers()).hasSize(2);
    assertThat(frozen.answers().getLast().answer()).isEmpty();
    assertThat(frozen.answers().getLast().submissionIntent()).isEqualTo(SubmissionIntent.DONT_KNOW);
  }

  @Test
  @DisplayName("提前交卷不把草稿当正式证据，未发布主问题不进入实际题目")
  void earlyCompletionExcludesDraftEvidence() {
    commands.saveDraft(owner.getId(), session.getSessionId(), 0, 0, "尚未提交");
    var done = commands.complete(owner.getId(), session.getSessionId(), 0);
    var frozen = mapper.readValue(outboxes.findBySession_IdAndFrozenVersion(session.getId(),
        done.version()).orElseThrow().getFrozenSnapshotJson(), FrozenSnapshot.class);
    assertThat(frozen.answers()).isEmpty();
    assertThat(frozen.questions()).hasSize(1);
    assertThat(frozen.questions().getFirst().userAnswer()).isNull();
  }

  @Test
  @DisplayName("他人不能查询、草稿、提交、交卷或借合法请求ID绕过归属校验")
  void crossOwnerRejectedBeforeIdempotency() {
    var request = request(0, 0, SubmissionIntent.ANSWER, "原文");
    commands.submit(owner.getId(), session.getSessionId(), request);
    UUID other = UUID.randomUUID();
    assertThatThrownBy(() -> commands.submit(other, session.getSessionId(), request))
        .isInstanceOf(BusinessException.class);
    assertThatThrownBy(() -> commands.saveDraft(other, session.getSessionId(), 0, 0, "越权"))
        .isInstanceOf(BusinessException.class);
    assertThatThrownBy(() -> commands.complete(other, session.getSessionId(), 1))
        .isInstanceOf(BusinessException.class);
    assertThatThrownBy(() -> queries.get(other, session.getSessionId()))
        .isInstanceOf(BusinessException.class);
    assertThat(formalAnswers()).hasSize(1);
  }

  @Test
  @DisplayName("超时拒绝模型结果，恢复器无需认领令牌可失败关闭并保存答案")
  void expireUnclaimedDecisionAndRejectLateModel() {
    var receipt = submitAnswer();
    assertThat(transitions.expireDecision(session.getSessionId(), receipt.decisionId()))
        .isEqualTo(ApplicationResult.IGNORED);
    clock.now = NOW.plusSeconds(30);
    assertThat(transitions.applyDecision(session.getSessionId(), receipt.decisionId(), null, followUp("晚了")))
        .isEqualTo(ApplicationResult.IGNORED);
    assertThat(transitions.expireDecision(session.getSessionId(), receipt.decisionId()))
        .isEqualTo(ApplicationResult.APPLIED);
    var task = decisions.findById(receipt.decisionId()).orElseThrow();
    assertThat(task.getStatus()).isEqualTo(Status.FAILED);
    assertThat(task.getEndReason()).isEqualTo(DynamicGroupEndReason.SYSTEM_UNAVAILABLE);
    assertThat(task.getDeadline()).isEqualTo(NOW.plusSeconds(30));
    var latest = queries.get(owner.getId(), session.getSessionId());
    assertThat(latest.noticeCode()).isEqualTo("DECISION_TIMEOUT");
    assertThat(latest.currentQuestion().questionIndex()).isEqualTo(1);
    assertThat(latest.usedFollowUps()).isZero();
    assertThat(formalAnswers()).hasSize(1);
    assertThat(transitions.expireDecision(session.getSessionId(), receipt.decisionId()))
        .isEqualTo(ApplicationResult.IGNORED);
  }

  @Test
  @DisplayName("决策应用检查认领令牌和版本，成功后审计与推进一并持久化")
  void applicationFencesAndAuditAreAtomic() {
    var receipt = submitAnswer();
    UUID token = claim(receipt.decisionId());
    assertThat(transitions.applyDecision(session.getSessionId(), receipt.decisionId(), UUID.randomUUID(), followUp("追问")))
        .isEqualTo(ApplicationResult.IGNORED);
    var application = new DecisionApplication(Action.NEXT_MAIN, Reason.SUFFICIENT, "目标已覆盖",
        "{\"coveredTargetIds\":[\"target-a\",\"target-b\"]}", null,
        DynamicGroupEndReason.SUFFICIENT, null);
    assertThat(transitions.applyDecision(session.getSessionId(), receipt.decisionId(), token, application))
        .isEqualTo(ApplicationResult.APPLIED);
    var task = decisions.findById(receipt.decisionId()).orElseThrow();
    assertThat(task.getModelAction()).isEqualTo(Action.NEXT_MAIN);
    assertThat(task.getModelReason()).isEqualTo(Reason.SUFFICIENT);
    assertThat(task.getReasonSummary()).isEqualTo("目标已覆盖");
    assertThat(task.getModelDecisionJson()).contains("target-a");
    assertThat(task.getFinalAction()).isEqualTo(Action.NEXT_MAIN);
    assertThat(task.getEndReason()).isEqualTo(DynamicGroupEndReason.SUFFICIENT);
    assertThat(task.getAppliedRuntimeVersion()).isEqualTo(runtime().getVersion());
    assertThat(transitions.applyDecision(session.getSessionId(), receipt.decisionId(), token, application))
        .isEqualTo(ApplicationResult.IGNORED);
  }

  @Test
  @DisplayName("读取即使超过截止时间仍纯读，不推进也不重置任务期限")
  void queryNeverAdvancesExpiredDecision() {
    var receipt = submitAnswer();
    clock.now = NOW.plusSeconds(60);
    assertThat(queries.get(owner.getId(), session.getSessionId())).isEqualTo(receipt.runtime());
    assertThat(runtime().getVersion()).isEqualTo(receipt.runtime().version());
    assertThat(decisions.findById(receipt.decisionId()).orElseThrow().getStatus()).isEqualTo(Status.PENDING);
  }

  @Test
  @DisplayName("被禁用用户的异步结果不能发布问题")
  void disabledOwnerCannotAppend() {
    var receipt = submitAnswer();
    UUID token = claim(receipt.decisionId());
    transactions.executeWithoutResult(tx -> em.find(UserEntity.class, owner.getId()).setStatus(UserStatus.DISABLED));
    assertThat(transitions.applyDecision(session.getSessionId(), receipt.decisionId(), token, followUp("追问")))
        .isEqualTo(ApplicationResult.IGNORED);
    assertThat(runtime().getUsedFollowUps()).isZero();
  }

  @Test
  @DisplayName("应用拒绝租约失效和运行版本变化的结果")
  void expiredLeaseAndStaleRuntimeCannotAppend() {
    var receipt = submitAnswer();
    UUID token = claim(receipt.decisionId());
    clock.now = NOW.plusSeconds(20);
    assertThat(transitions.applyDecision(session.getSessionId(), receipt.decisionId(), token, followUp("租约过期")))
        .isEqualTo(ApplicationResult.IGNORED);
    UUID newerToken = claim(receipt.decisionId());
    transactions.executeWithoutResult(tx -> runtimes.findBySessionIdForUpdate(session.getSessionId())
        .orElseThrow().setNoticeCode("VERSION_CHANGED"));
    assertThat(transitions.applyDecision(session.getSessionId(), receipt.decisionId(), newerToken, followUp("旧版本")))
        .isEqualTo(ApplicationResult.IGNORED);
    assertThat(runtime().getUsedFollowUps()).isZero();
  }

  @Test
  @DisplayName("有效租约仍不能绕过决策绝对截止时间")
  void deadlineFencesEvenValidLease() {
    var receipt = submitAnswer();
    clock.now = NOW.plusSeconds(25);
    UUID token = claim(receipt.decisionId());
    clock.now = NOW.plusSeconds(30);
    assertThat(transitions.applyDecision(session.getSessionId(), receipt.decisionId(), token, followUp("超过总期限")))
        .isEqualTo(ApplicationResult.IGNORED);
    assertThat(transitions.expireDecision(session.getSessionId(), receipt.decisionId()))
        .isEqualTo(ApplicationResult.APPLIED);
  }

  @Test
  @DisplayName("简历归属变化后旧异步结果不能追加")
  void movedResumeCannotAppend() {
    var receipt = submitAnswer();
    UUID token = claim(receipt.decisionId());
    transactions.executeWithoutResult(tx -> {
      String key = UUID.randomUUID().toString();
      var other = UserEntity.builder().username(key).normalizedUsername(key).displayName(key)
          .passwordHash("test-only").role(UserRole.USER).status(UserStatus.ACTIVE)
          .createdAt(NOW).updatedAt(NOW).build();
      em.persist(other);
      var resume = new ResumeEntity();
      resume.setOwner(other);
      resume.setFileHash("a".repeat(64));
      resume.setOriginalFilename("fixture.txt");
      em.persist(resume);
      em.find(InterviewSessionEntity.class, session.getId()).setResume(resume);
    });
    assertThat(transitions.applyDecision(session.getSessionId(), receipt.decisionId(), token, followUp("归属改变")))
        .isEqualTo(ApplicationResult.IGNORED);
    assertThat(runtime().getUsedFollowUps()).isZero();
  }

  @Test
  @DisplayName("已应用决策的提交重试保留原决策ID和最新快照")
  void pendingRequestRetryAfterAppliedReturnsOriginalDecision() {
    var request = request(0, 0, SubmissionIntent.ANSWER, "原文");
    var first = commands.submit(owner.getId(), session.getSessionId(), request);
    apply(first);
    var retry = commands.submit(owner.getId(), session.getSessionId(), request);
    assertThat(retry.decisionId()).isEqualTo(first.decisionId());
    assertThat(retry.runtime()).isEqualTo(queries.get(owner.getId(), session.getSessionId()));
    assertThat(retry.runtime().phase()).isEqualTo(DynamicPhase.WAITING_ANSWER);
    assertThat(formalAnswers()).hasSize(1);
  }

  @Test
  @DisplayName("评价outbox约束失败时答案、组关闭和完成状态全部回滚")
  void outboxFailureRollsBackSubmissionAndCompletion() {
    session = createSession(1);
    outboxes.saveAndFlush(new DynamicEvaluationOutboxEntity(runtime(), 1, "{}", NOW));
    assertThatThrownBy(() -> commands.submit(owner.getId(), session.getSessionId(),
        request(0, 0, SubmissionIntent.SKIP, ""))).isInstanceOf(DataIntegrityViolationException.class);
    assertThat(runtime().getPhase()).isEqualTo(DynamicPhase.WAITING_ANSWER);
    assertThat(runtime().getVersion()).isZero();
    assertThat(runtime().getFrozenVersion()).isNull();
    assertThat(runtime().getCompletedMainCount()).isZero();
    assertThat(formalAnswers()).isEmpty();
  }

  @Test
  @DisplayName("模型失败保留真实答案及故障原因且不消耗追问预算")
  void systemFailurePreservesAnswerWithoutBudgetCharge() {
    var receipt = submitAnswer();
    var application = new DecisionApplication(null, null, null, null, null,
        DynamicGroupEndReason.SYSTEM_UNAVAILABLE, "MODEL_UNAVAILABLE");
    assertThat(transitions.applyDecision(session.getSessionId(), receipt.decisionId(), claim(receipt.decisionId()), application))
        .isEqualTo(ApplicationResult.APPLIED);
    assertThat(runtime().getUsedFollowUps()).isZero();
    assertThat(runtime().getNoticeCode()).isEqualTo("MODEL_UNAVAILABLE");
    assertThat(formalAnswers()).singleElement().satisfies(a -> {
      assertThat(a.getUserAnswer()).isEqualTo("正式回答");
      assertThat(a.getScore()).isNull();
    });
    assertThat(decisions.findById(receipt.decisionId()).orElseThrow().getStatus()).isEqualTo(Status.FAILED);
  }

  @Test
  @DisplayName("空普通回答不能提交，显式跳过允许保留null文本")
  void blankAnswerRejectedAndNullSkipPreserved() {
    assertThatThrownBy(() -> commands.submit(owner.getId(), session.getSessionId(),
        request(0, 0, SubmissionIntent.ANSWER, "  "))).isInstanceOf(BusinessException.class);
    commands.submit(owner.getId(), session.getSessionId(), request(0, 0, SubmissionIntent.SKIP, null));
    assertThat(formalAnswers()).singleElement().satisfies(a -> {
      assertThat(a.getUserAnswer()).isNull();
      assertThat(a.getSubmissionIntent()).isEqualTo(SubmissionIntent.SKIP);
    });
  }

  private DynamicSubmissionReceipt submitAnswer() {
    var current = queries.get(owner.getId(), session.getSessionId());
    return commands.submit(owner.getId(), session.getSessionId(), request(
        current.currentQuestion().questionIndex(), current.version(), SubmissionIntent.ANSWER, "正式回答"));
  }

  private ApplicationResult apply(DynamicSubmissionReceipt receipt) {
    return transitions.applyDecision(session.getSessionId(), receipt.decisionId(), claim(receipt.decisionId()),
        followUp("追问" + receipt.runtime().totalQuestions()));
  }

  private UUID claim(UUID id) {
    UUID token = UUID.randomUUID();
    transactions.executeWithoutResult(tx -> {
      var decision = decisions.findByIdForUpdate(id).orElseThrow();
      decision.setStatus(Status.PROCESSING);
      decision.setAttemptToken(token);
      decision.setLeaseUntil(clock.instant().plusSeconds(20));
    });
    return token;
  }

  private DecisionApplication followUp(String question) {
    return new DecisionApplication(Action.FOLLOW_UP, Reason.IMPORTANT_GAP, "缺少边界", "{}",
        question, null, null);
  }

  static class MutableClock extends Clock {
    Instant now = NOW;
    @Override public ZoneId getZone() { return ZoneOffset.UTC; }
    @Override public Clock withZone(ZoneId zone) { return this; }
    @Override public Instant instant() { return now; }
  }

  private List<InterviewAnswerEntity> formalAnswers() {
    return answers.findBySession_SessionIdOrderByQuestionIndex(session.getSessionId())
        .stream().filter(a -> a.getRequestId() != null).toList();
  }

  private DynamicInterviewRuntimeEntity runtime() {
    return runtimes.findBySession_SessionId(session.getSessionId()).orElseThrow();
  }

  private DynamicSubmitRequest request(int index, long version, SubmissionIntent intent, String text) {
    return new DynamicSubmitRequest(UUID.randomUUID(), index, version, intent, text);
  }

  private InterviewSessionEntity createSession(int count) {
    var pool = IntStream.range(0, count).mapToObj(i -> new DynamicMainQuestion("slot-" + i,
        "主问题" + i, "JAVA", "Java", "事务", List.of(new AssessmentTarget("target-a", "代理"),
        new AssessmentTarget("target-b", "边界")), "mid", List.of())).toList();
    var created = new InterviewSessionEntity();
    created.setOwner(owner);
    created.setSessionId(UUID.randomUUID().toString());
    created.setExecutionMode(InterviewExecutionMode.DYNAMIC_FOLLOW_UP);
    created.setQuestionsJson(mapper.writeValueAsString(List.of(InterviewQuestionDTO.planned(0,
        "主问题0", "JAVA", "Java", "事务", false, null, "slot-0"))));
    created.setTotalQuestions(1);
    transactions.executeWithoutResult(tx -> em.persist(created));
    runtimes.saveAndFlush(new DynamicInterviewRuntimeEntity(created, mapper.writeValueAsString(pool),
        mapper.writeValueAsString(new DynamicInterviewProperties().snapshot(count)), count, "slot-0"));
    return created;
  }
}
