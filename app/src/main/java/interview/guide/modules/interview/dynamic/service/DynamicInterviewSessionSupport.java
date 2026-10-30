package interview.guide.modules.interview.dynamic.service;

import interview.guide.infrastructure.redis.InterviewSessionCache;
import interview.guide.modules.interview.model.InterviewQuestionDTO;
import interview.guide.modules.interview.model.InterviewSessionDTO;
import interview.guide.modules.interview.model.InterviewSessionEntity;
import interview.guide.modules.interview.model.InterviewAnswerEntity;
import interview.guide.modules.interview.repository.InterviewAnswerRepository;
import interview.guide.modules.interview.dynamic.model.InterviewExecutionMode;
import interview.guide.modules.interview.dynamic.repository.DynamicInterviewRuntimeRepository;
import java.util.Map;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** Read only published questions; the legacy cache never supplies dynamic state. */
@Service
@RequiredArgsConstructor
public class DynamicInterviewSessionSupport {
  private final InterviewAnswerRepository answers;
  private final ObjectMapper mapper;
  private final InterviewSessionCache cache;
  private final DynamicInterviewRuntimeRepository runtimes;

  @Transactional(readOnly = true)
  public InterviewSessionDTO restore(InterviewSessionEntity session) {
    List<InterviewQuestionDTO> questions = mapper.readValue(session.getQuestionsJson(),
        mapper.getTypeFactory().constructCollectionType(List.class, InterviewQuestionDTO.class));
    var restored = restoreAnswers(questions,
        answers.findBySession_SessionIdOrderByQuestionIndex(session.getSessionId()));
    int mains = (int) restored.stream().filter(q -> !q.isFollowUp()).count();
    return new InterviewSessionDTO(session.getSessionId(),
        session.getResume() == null ? "" : session.getResume().getResumeText(), restored.size(),
        session.getCurrentQuestionIndex(), restored,
        InterviewSessionDTO.SessionStatus.valueOf(session.getStatus().name()), mains,
        restored.size() - mains, session.getExecutionMode());
  }

  public void invalidate(UUID ownerId, String sessionId) {
    cache.invalidateSession(ownerId, sessionId);
  }

  public List<InterviewQuestionDTO> restoreAnswers(List<InterviewQuestionDTO> questions,
      List<InterviewAnswerEntity> savedAnswers) {
    var saved = savedAnswers.stream().collect(Collectors.toMap(
        InterviewAnswerEntity::getQuestionIndex, Function.identity()));
    return questions.stream().map(q -> saved.containsKey(q.questionIndex())
        ? q.withSubmission(saved.get(q.questionIndex()).getUserAnswer(),
            saved.get(q.questionIndex()).getSubmissionIntent()) : q).toList();
  }

  public Map<String, Integer> plannedCounts(List<InterviewSessionEntity> sessions) {
    var ids = sessions.stream()
        .filter(s -> s.getExecutionMode() == InterviewExecutionMode.DYNAMIC_FOLLOW_UP)
        .map(InterviewSessionEntity::getSessionId).toList();
    if (ids.isEmpty()) { return Map.of(); }
    return runtimes.findMetadataBySessionIds(ids).stream().collect(Collectors.toMap(
        DynamicInterviewRuntimeRepository.RuntimeMetadata::getSessionId,
        DynamicInterviewRuntimeRepository.RuntimeMetadata::getPlannedMainQuestionCount));
  }
}
