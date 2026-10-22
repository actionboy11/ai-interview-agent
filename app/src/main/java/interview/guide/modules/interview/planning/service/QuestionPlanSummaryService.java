package interview.guide.modules.interview.planning.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.auth.security.CurrentUserProvider;
import interview.guide.modules.interview.model.InterviewQuestionDTO;
import interview.guide.modules.interview.planning.dto.QuestionPlanSummaryResponse;
import interview.guide.modules.interview.planning.dto.QuestionPlanSummaryResponse.QuestionReasonResponse;
import interview.guide.modules.interview.planning.model.PlanningMode;
import interview.guide.modules.interview.planning.model.QuestionPlan;
import interview.guide.modules.interview.planning.model.QuestionPurpose;
import interview.guide.modules.interview.planning.model.QuestionSlot;
import interview.guide.modules.interview.planning.repository.InterviewQuestionPlanRepository;
import interview.guide.modules.interview.repository.InterviewSessionRepository;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Service
@RequiredArgsConstructor
public class QuestionPlanSummaryService {
  private final CurrentUserProvider currentUser;
  private final InterviewSessionRepository sessions;
  private final InterviewQuestionPlanRepository plans;
  private final ObjectMapper mapper;

  @Transactional(readOnly = true)
  public QuestionPlanSummaryResponse summary(String sessionId) {
    var ownerId = currentUser.requireUserId();
    var session = sessions.findByOwnerIdAndSessionId(ownerId, sessionId)
        .orElseThrow(() -> new BusinessException(ErrorCode.INTERVIEW_SESSION_NOT_FOUND));
    var snapshot = plans.findFirstByOwnerIdAndSessionSessionIdOrderByIdDesc(ownerId, sessionId);
    if (snapshot.isEmpty()) {
      return QuestionPlanSummaryResponse.unavailable();
    }
    QuestionPlan plan = mapper.readValue(snapshot.get().getPlanJson(), QuestionPlan.class);
    Map<QuestionPurpose, Integer> counts = new EnumMap<>(QuestionPurpose.class);
    for (QuestionPurpose purpose : QuestionPurpose.values()) {
      counts.put(purpose, 0);
    }
    for (QuestionSlot slot : plan.slots()) {
      counts.merge(slot.purpose(), 1, Integer::sum);
    }
    Map<String, QuestionSlot> bySlot = plan.slots().stream()
        .collect(Collectors.toMap(QuestionSlot::slotId, Function.identity(),
            (first, ignored) -> first));
    List<InterviewQuestionDTO> questions = session.getQuestionsJson() == null
        ? List.of() : mapper.readValue(session.getQuestionsJson(), new TypeReference<>() {});
    List<QuestionReasonResponse> reasons = questions.stream()
        .filter(question -> question.slotId() != null && bySlot.containsKey(question.slotId()))
        .map(question -> {
          QuestionSlot slot = bySlot.get(question.slotId());
          return new QuestionReasonResponse(question.questionIndex(), slot.slotId(),
              slot.categoryKey(), slot.purpose(), reason(plan.mode(), slot.purpose()));
        }).toList();
    return new QuestionPlanSummaryResponse(true, plan.mode(), counts, reasons);
  }

  private String reason(PlanningMode mode, QuestionPurpose purpose) {
    if (mode == PlanningMode.FIXED) {
      return purpose == QuestionPurpose.MANDATORY ? "覆盖该方向的必考内容"
          : "按固定题量覆盖该方向的基础内容";
    }
    return switch (purpose) {
      case MANDATORY -> "覆盖该方向的必考内容";
      case BASELINE -> "保持该方向的基础覆盖";
      case WEAKNESS_REINFORCEMENT -> "巩固已确认的薄弱项";
      case STRENGTH_CALIBRATION -> "校准已确认的优势";
      case EXPLORATION -> "探索尚未确认的能力方向";
    };
  }
}
