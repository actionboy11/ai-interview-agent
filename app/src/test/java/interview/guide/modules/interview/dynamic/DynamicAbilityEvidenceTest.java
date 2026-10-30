package interview.guide.modules.interview.dynamic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.model.AsyncTaskStatus;
import interview.guide.modules.interview.dynamic.model.SubmissionIntent;
import interview.guide.modules.interview.model.InterviewAnswerEntity;
import interview.guide.modules.interview.model.InterviewSessionEntity;
import interview.guide.modules.memory.service.DerivationResult;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.node.ObjectNode;

@DisplayName("动态正式主问题组派生可追溯能力证据")
class DynamicAbilityEvidenceTest extends DynamicAbilityTestSupport {
  @Test
  @DisplayName("三条回答只生成一份组证据并保留全部支持来源")
  void threeAnswersCreateOneEvidence() {
    var f = fixture(new GroupSpec(3, 80, SubmissionIntent.ANSWER));
    derivation.derive(f.sessionId(), UUID.randomUUID());
    assertThat(items(f)).singleElement().satisfies(item -> {
      assertThat(item.getQuestionScore()).isEqualTo(80);
      assertThat(item.getInterviewAnswer().getId()).isEqualTo(f.answerIds().getFirst());
      assertThat(item.getFeedbackSummary()).isEqualTo("线程安全的具体观察 0");
    });
    assertThat(responses(f).getFirst()).containsEntry("supportingAnswerIds", f.answerIds())
        .containsEntry("slotId", "slot-0");
  }

  @Test
  @DisplayName("空文本明确不会仍保留正式观测分数和不足证据")
  void emptyDontKnowCreatesGapEvidence() {
    var f = fixture(new GroupSpec(1, 35, SubmissionIntent.DONT_KNOW));
    derivation.derive(f.sessionId(), UUID.randomUUID());
    assertThat(items(f)).singleElement().satisfies(item ->
        assertThat(item.getQuestionScore()).isEqualTo(35));
    assertThat(responses(f).getFirst()).containsEntry("supportingAnswerIds", f.answerIds());
  }

  @Test
  @DisplayName("跳过与系统未收集的目标不伪造弱项，已观测回答仍保留")
  void skipAndSystemMissingTargetsCreateNoWeakEvidence() {
    var f = fixture(new GroupSpec(1, null, SubmissionIntent.SKIP),
        new GroupSpec(0, null, SubmissionIntent.ANSWER),
        new GroupSpec(1, 75, SubmissionIntent.ANSWER));
    derivation.derive(f.sessionId(), UUID.randomUUID());
    assertThat(items(f)).singleElement().satisfies(item -> {
      assertThat(item.getQuestionScore()).isEqualTo(75);
      assertThat(item.getFeedbackSummary()).isEqualTo("线程安全的具体观察 2");
    });
  }

  @Test
  @DisplayName("同类别候选按主问题组等权平均而非追问数量")
  void categoryProposalAveragesGroups() {
    var f = fixture(new GroupSpec(3, 90, SubmissionIntent.ANSWER),
        new GroupSpec(1, 50, SubmissionIntent.ANSWER));
    derivation.derive(f.sessionId(), UUID.randomUUID());
    assertThat(proposals.findByOwnerIdAndInterviewSessionId(f.ownerId(), f.sessionDbId()))
        .singleElement().satisfies(item -> assertThat(item.getProposedScore()).isEqualTo(70));
    assertThat(items(f)).hasSize(2);
  }

  @Test
  @DisplayName("重复投递不重复组证据、支持来源或候选")
  void retryDoesNotDuplicate() {
    var f = fixture(new GroupSpec(3, 80, SubmissionIntent.ANSWER));
    derivation.derive(f.sessionId(), UUID.randomUUID());
    assertThat(derivation.derive(f.sessionId(), UUID.randomUUID()))
        .isEqualTo(DerivationResult.ALREADY_COMPLETED);
    assertThat(items(f)).hasSize(1);
    assertThat(responses(f).getFirst()).containsEntry("supportingAnswerIds", f.answerIds());
  }

  @Test
  @DisplayName("冻结后来源归属被改写时拒绝生成任何能力证据")
  void foreignAnswerSourceRejected() {
    var f = fixture(new GroupSpec(2, 80, SubmissionIntent.ANSWER));
    tx.executeWithoutResult(status -> em.find(InterviewAnswerEntity.class,
        f.answerIds().getLast()).setSubmissionOwnerId(UUID.randomUUID()));
    assertThatThrownBy(() -> derivation.derive(f.sessionId(), UUID.randomUUID()))
        .isInstanceOf(BusinessException.class);
    assertThat(items(f)).isEmpty();
  }

  @Test
  @DisplayName("报告未正式完成时不得从答案或导航状态派生证据")
  void incompleteReportIsIgnored() {
    var f = fixture(new GroupSpec(2, 80, SubmissionIntent.ANSWER));
    tx.executeWithoutResult(status -> em.find(InterviewSessionEntity.class, f.sessionDbId())
        .setEvaluateStatus(AsyncTaskStatus.PROCESSING));
    assertThat(derivation.derive(f.sessionId(), UUID.randomUUID())).isEqualTo(DerivationResult.DISCARDED);
    assertThat(items(f)).isEmpty();
  }

  @ParameterizedTest
  @ValueSource(strings = {"schema", "frozenVersion", "missingReport", "messageId",
      "answerText", "answerCategory", "answerIntent", "answerRequest"})
  @DisplayName("正式报告版本、稳定任务身份和当前正式来源不一致时拒绝派生")
  void formalSourceContractIsRechecked(String changed) {
    var f = fixture(new GroupSpec(2, 80, SubmissionIntent.ANSWER));
    tx.executeWithoutResult(status -> {
      var session = em.find(InterviewSessionEntity.class, f.sessionDbId());
      var answer = em.find(InterviewAnswerEntity.class, f.answerIds().getLast());
      switch (changed) {
        case "schema", "frozenVersion" -> {
          var report = (ObjectNode) mapper.readTree(session.getReportJson());
          report.put(changed.equals("schema") ? "groupEvaluationSchemaVersion" : "frozenVersion", 2);
          session.setReportJson(mapper.writeValueAsString(report));
        }
        case "missingReport" -> session.setReportJson(null);
        case "messageId" -> session.setEvaluationMessageId(UUID.randomUUID().toString());
        case "answerText" -> answer.setUserAnswer("冻结后替换文本");
        case "answerCategory" -> answer.setCategoryKey("MYSQL");
        case "answerIntent" -> answer.setSubmissionIntent(SubmissionIntent.SKIP);
        case "answerRequest" -> answer.setRequestId(UUID.randomUUID());
        default -> throw new AssertionError(changed);
      }
    });
    assertThatThrownBy(() -> derivation.derive(f.sessionId(), UUID.randomUUID()))
        .isInstanceOf(BusinessException.class);
    assertThat(items(f)).isEmpty();
  }

  @Test
  @DisplayName("另一用户真实存在的评价任务也不能替代本场冻结来源")
  void foreignOutboxRejected() {
    var f = fixture(new GroupSpec(1, 80, SubmissionIntent.ANSWER));
    var other = fixture(new GroupSpec(1, 50, SubmissionIntent.ANSWER));
    tx.executeWithoutResult(status -> {
      var foreignSession = em.find(InterviewSessionEntity.class, other.sessionDbId());
      String foreignMessageId = foreignSession.getEvaluationMessageId();
      foreignSession.setEvaluationMessageId(null);
      em.flush();
      em.find(InterviewSessionEntity.class, f.sessionDbId()).setEvaluationMessageId(foreignMessageId);
    });
    assertThatThrownBy(() -> derivation.derive(f.sessionId(), UUID.randomUUID()))
        .isInstanceOf(BusinessException.class);
    assertThat(items(f)).isEmpty();
  }
}
