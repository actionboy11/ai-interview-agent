package interview.guide.modules.ownership;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.interview.model.CreateInterviewRequest;
import interview.guide.modules.interview.model.InterviewQuestionDTO;
import interview.guide.modules.interview.planning.model.PlanningMode;
import interview.guide.modules.interview.planning.model.QuestionPlan;
import interview.guide.modules.interview.planning.model.QuestionPurpose;
import interview.guide.modules.interview.planning.model.QuestionSlot;
import interview.guide.modules.interview.planning.model.QuestionSourceMode;
import interview.guide.modules.interview.planning.repository.InterviewQuestionPlanRepository;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@DisplayName("持久化出题计划")
class AdaptiveQuestionGenerationTest extends WorkflowOwnershipTestSupport {
  @Autowired InterviewQuestionPlanRepository plans;

  @Test
  @DisplayName("模型调用前计划与会话已经提交且模型调用无事务")
  void persistsBeforeModelCall() {
    QuestionPlan plan = plan(PlanningMode.ADAPTIVE, null);
    when(planning.plan(any())).thenReturn(plan);
    when(questions.generateFromPlan(any(), any(), any(), any(), any())).thenAnswer(call -> {
      assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
      assertThat(ownedPlans()).singleElement().satisfies(saved -> {
        assertThat(saved.getOwner().getId()).isEqualTo(first.getId());
        assertThat(saved.getPlanningMode()).isEqualTo(PlanningMode.ADAPTIVE);
        assertThat(saved.getPlanJson()).contains("slot-01", "slot-02", "slot-03");
        assertThat(sessions.findByOwnerIdOrderByCreatedAtDesc(first.getId()))
            .singleElement().satisfies(session ->
                assertThat(session.getQuestionsJson()).isNull());
      });
      return generated();
    });

    var created = interviewService.createSession(request());
    assertThat(created.questions()).hasSize(3);
    assertThat(plans.findFirstByOwnerIdAndSessionSessionIdOrderByIdDesc(
        first.getId(), created.sessionId())).isPresent();
    assertThat(plans.findFirstByOwnerIdAndSessionSessionIdOrderByIdDesc(
        second.getId(), created.sessionId())).isEmpty();
  }

  @Test
  @DisplayName("自适应结果无效时先保存固定计划再重新出题")
  void persistsFallbackBeforeRegeneration() {
    QuestionPlan adaptive = plan(PlanningMode.ADAPTIVE, null);
    QuestionPlan fixed = plan(PlanningMode.FIXED, "INVALID_ADAPTIVE_OUTPUT");
    when(planning.plan(any())).thenReturn(adaptive);
    when(allocation.fallback(any(), any())).thenReturn(fixed);
    when(questions.generateFromPlan(any(), any(), any(), any(), any())).thenAnswer(call -> {
      QuestionPlan selected = call.getArgument(4);
      if (selected.mode() == PlanningMode.ADAPTIVE) {
        throw new BusinessException(ErrorCode.INTERVIEW_QUESTION_GENERATION_FAILED);
      }
      assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
      assertThat(ownedPlans()).hasSize(2);
      assertThat(ownedPlans().stream().max(java.util.Comparator.comparingLong(
          value -> value.getId())).orElseThrow().getPlanningMode())
          .isEqualTo(PlanningMode.FIXED);
      return generated();
    });

    interviewService.createSession(request());
    verify(questions, times(2)).generateFromPlan(any(), any(), any(), any(), any());
    assertThat(ownedPlans()).extracting(value -> value.getPlanningMode())
        .containsExactlyInAnyOrder(PlanningMode.ADAPTIVE, PlanningMode.FIXED);
  }

  @Test
  @DisplayName("固定计划出题失败时显式报错且清除未完成会话")
  void fixedFailureDiscardsShell() {
    when(planning.plan(any())).thenReturn(plan(PlanningMode.FIXED, null));
    when(questions.generateFromPlan(any(), any(), any(), any(), any()))
        .thenThrow(new BusinessException(ErrorCode.INTERVIEW_QUESTION_GENERATION_FAILED));
    assertThatThrownBy(() -> interviewService.createSession(request()))
        .isInstanceOf(BusinessException.class);
    assertThat(ownedPlans()).isEmpty();
    assertThat(sessions.findByOwnerIdOrderByCreatedAtDesc(first.getId())).isEmpty();
  }

  @Test
  @DisplayName("无简历不分配简历槽位，提交的简历仅以实际出现的分类作为依据")
  void groundsResumeCategoriesFromSubmittedFacts() {
    when(planning.plan(any())).thenAnswer(call -> {
      interview.guide.modules.interview.planning.model.QuestionPlanningCommand command =
          call.getArgument(0);
      assertThat(command.hasResume()).isTrue();
      assertThat(command.groundedResumeCategoryKeys()).containsExactly("java");
      return plan(PlanningMode.FIXED, null);
    });
    interviewService.createSession(new CreateInterviewRequest("Java project experience", 3,
        null, false, null, "java", "mid", null, null));
  }

  private CreateInterviewRequest request() {
    return new CreateInterviewRequest(null, 3, null, false, null,
        "java", "mid", null, null);
  }

  private QuestionPlan plan(PlanningMode mode, String reason) {
    return new QuestionPlan(1, mode, List.of(
        slot("slot-01"), slot("slot-02"), slot("slot-03")), reason);
  }

  private QuestionSlot slot(String id) {
    return new QuestionSlot(id, "java", QuestionPurpose.BASELINE, "mid",
        QuestionSourceMode.DIRECTION, List.of(), List.of(), null);
  }

  private List<InterviewQuestionDTO> generated() {
    return List.of(InterviewQuestionDTO.planned(0, "Question 1", "java", "Java", null,
        false, null, "slot-01"),
        InterviewQuestionDTO.planned(1, "Question 2", "java", "Java", null,
            false, null, "slot-02"),
        InterviewQuestionDTO.planned(2, "Question 3", "java", "Java", null,
            false, null, "slot-03"));
  }

  private List<interview.guide.modules.interview.planning.model.InterviewQuestionPlanEntity>
      ownedPlans() {
    return plans.findAll().stream()
        .filter(plan -> first.getId().equals(plan.getOwner().getId())).toList();
  }
}
