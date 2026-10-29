package interview.guide.modules.interview.dynamic.service;

import interview.guide.modules.interview.dynamic.dto.DynamicRuntimeSnapshot;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.auth.model.UserStatus;
import interview.guide.modules.interview.dynamic.model.DynamicInterviewRuntimeEntity;
import interview.guide.modules.interview.dynamic.model.DynamicPhase;
import interview.guide.modules.interview.dynamic.repository.DynamicInterviewRuntimeRepository;
import interview.guide.modules.interview.model.InterviewQuestionDTO;
import interview.guide.modules.interview.repository.InterviewAnswerRepository;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
@RequiredArgsConstructor
public class DynamicInterviewQueryService {
  private final DynamicInterviewRuntimeRepository runtimes;
  private final InterviewAnswerRepository answers;
  private final ObjectMapper mapper;

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public DynamicRuntimeSnapshot get(UUID ownerId, String sessionId) {
    var runtime = runtimes.findByOwner_IdAndSession_SessionId(ownerId, sessionId)
        .orElseThrow(() -> new BusinessException(ErrorCode.INTERVIEW_SESSION_NOT_FOUND));
    requireOwner(runtime, ownerId);
    return snapshot(runtime);
  }

  DynamicRuntimeSnapshot snapshot(DynamicInterviewRuntimeEntity runtime) {
    var questions = questions(runtime);
    int mains = (int) questions.stream().filter(q -> !q.isFollowUp()).count();
    InterviewQuestionDTO current = runtime.getPhase() == DynamicPhase.COMPLETED ? null
        : questions.get(runtime.getCurrentQuestionIndex());
    if (current != null) {
      var answer = answers.findBySession_SessionIdAndQuestionIndex(
          runtime.getSession().getSessionId(), current.questionIndex());
      if (answer.isPresent()) { current = current.withAnswer(answer.get().getUserAnswer()); }
    }
    return new DynamicRuntimeSnapshot(runtime.getSession().getSessionId(), runtime.getVersion(),
        runtime.getPhase(), runtime.getPlannedMainQuestionCount(), runtime.getCurrentMainOrdinal() + 1,
        runtime.getCompletedMainCount(), runtime.getUsedFollowUps(), questions.size(), mains,
        questions.size() - mains, current, runtime.getPendingDecisionId(), runtime.getNoticeCode());
  }

  List<InterviewQuestionDTO> questions(DynamicInterviewRuntimeEntity runtime) {
    return mapper.readValue(runtime.getSession().getQuestionsJson(), mapper.getTypeFactory()
        .constructCollectionType(List.class, InterviewQuestionDTO.class));
  }

  static boolean ownerActive(DynamicInterviewRuntimeEntity runtime) {
    var session = runtime.getSession();
    return runtime.getOwner().getStatus() == UserStatus.ACTIVE
        && runtime.getOwner().getId().equals(session.getOwner().getId())
        && (session.getResume() == null
            || session.getResume().getOwner().getId().equals(runtime.getOwner().getId()));
  }

  static void requireOwner(DynamicInterviewRuntimeEntity runtime, UUID ownerId) {
    if (ownerId == null || !runtime.getOwner().getId().equals(ownerId) || !ownerActive(runtime)) {
      throw new BusinessException(ErrorCode.INTERVIEW_SESSION_NOT_FOUND);
    }
  }
}
