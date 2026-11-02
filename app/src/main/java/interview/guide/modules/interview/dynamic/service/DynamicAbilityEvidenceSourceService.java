package interview.guide.modules.interview.dynamic.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.common.model.AsyncTaskStatus;
import interview.guide.modules.interview.dynamic.model.DynamicGroupEvidenceSource;
import interview.guide.modules.interview.dynamic.model.DynamicInterviewEvaluationSnapshot;
import interview.guide.modules.interview.dynamic.model.InterviewExecutionMode;
import interview.guide.modules.interview.dynamic.repository.DynamicEvaluationOutboxRepository;
import interview.guide.modules.interview.dynamic.service.DynamicInterviewTransitionService.FrozenSnapshot;
import interview.guide.modules.interview.model.InterviewAnswerEntity;
import interview.guide.modules.interview.model.InterviewReportDTO;
import interview.guide.modules.interview.model.InterviewSessionEntity;
import interview.guide.modules.interview.repository.InterviewAnswerRepository;
import interview.guide.modules.interview.repository.InterviewSessionRepository;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
@RequiredArgsConstructor
public class DynamicAbilityEvidenceSourceService {
  private final InterviewSessionRepository sessions;
  private final InterviewAnswerRepository answers;
  private final DynamicEvaluationOutboxRepository outboxes;
  private final ObjectMapper mapper;

  @Transactional(readOnly = true)
  public List<DynamicGroupEvidenceSource> sources(String sessionId) {
    var session = sessions.findBySessionId(sessionId).orElse(null);
    if (session == null || session.getExecutionMode() != InterviewExecutionMode.DYNAMIC_FOLLOW_UP
        || session.getStatus() != InterviewSessionEntity.SessionStatus.EVALUATED
        || session.getEvaluateStatus() != AsyncTaskStatus.COMPLETED) { return List.of(); }
    if (session.getOwner() == null || session.getEvaluationMessageId() == null
        || session.getReportJson() == null) { throw invalid(); }
    UUID messageId;
    try { messageId = UUID.fromString(session.getEvaluationMessageId()); }
    catch (IllegalArgumentException ex) { throw invalid(); }
    var outbox = outboxes.findByMessageId(messageId).orElseThrow(
        DynamicAbilityEvidenceSourceService::invalid);
    if (!session.getId().equals(outbox.getSession().getId())
        || !session.getOwner().getId().equals(outbox.getOwner().getId())) { throw invalid(); }
    var frozen = mapper.readValue(outbox.getFrozenSnapshotJson(), FrozenSnapshot.class);
    if (!sessionId.equals(frozen.sessionId())
        || !session.getOwner().getId().equals(frozen.ownerId())
        || outbox.getFrozenVersion() != frozen.frozenVersion()) { throw invalid(); }
    var snapshot = DynamicInterviewEvaluationSnapshot.fromFrozen(frozen);
    var report = mapper.readValue(session.getReportJson(), InterviewReportDTO.class);
    DynamicInterviewEvaluationService.validateReport(snapshot, report);
    var current = answers.findBySession_SessionIdOrderByQuestionIndex(sessionId).stream()
        // Drafts and unobserved report placeholders are not frozen formal submissions.
        // Partial submission metadata must still fail the strict source comparison below.
        .filter(answer -> answer.getRequestId() != null || answer.getSubmissionOwnerId() != null
            || answer.getSubmissionIntent() != null || answer.getRequestFingerprint() != null)
        .collect(Collectors.toMap(InterviewAnswerEntity::getId, Function.identity()));
    if (current.size() != frozen.answers().size()) { throw invalid(); }
    for (var source : frozen.answers()) {
      var answer = current.get(source.answerId());
      if (answer == null || !session.getId().equals(answer.getSession().getId())
          || !session.getOwner().getId().equals(answer.getSession().getOwner().getId())
          || !session.getOwner().getId().equals(answer.getSubmissionOwnerId())
          || !Objects.equals(source.questionIndex(), answer.getQuestionIndex())
          || !Objects.equals(source.question(), answer.getQuestion())
          || !Objects.equals(source.categoryKey(), answer.getCategoryKey())
          || !Objects.equals(source.categoryLabel(), answer.getCategoryLabel())
          || !Objects.equals(source.answer(), answer.getUserAnswer())
          || source.submissionIntent() != answer.getSubmissionIntent()
          || !Objects.equals(source.requestId(), answer.getRequestId())
          || answer.getRequestFingerprint() == null || answer.getAnsweredAt() == null) {
        throw invalid();
      }
    }
    var evaluations = report.groupEvaluations().stream().collect(Collectors.toMap(
        value -> value.slotId(), Function.identity()));
    var result = new ArrayList<DynamicGroupEvidenceSource>();
    for (var group : snapshot.groups()) {
      var evaluation = evaluations.get(group.mainQuestion().slotId());
      if (evaluation.score() == null) { continue; }
      var byIndex = group.answers().stream().collect(Collectors.toMap(
          value -> value.questionIndex(), Function.identity()));
      var main = byIndex.get(group.questions().getFirst().questionIndex());
      if (main == null) { throw invalid(); }
      var supporting = evaluation.supportingQuestionIndexes().stream()
          .map(index -> byIndex.get(index).answerId()).toList();
      var occurredAt = supporting.stream().map(current::get)
          .map(answer -> answer.getAnsweredAt().atZone(ZoneId.systemDefault()).toInstant())
          .max(Instant::compareTo).orElseThrow();
      result.add(new DynamicGroupEvidenceSource(group.mainQuestion().slotId(), main.answerId(),
          supporting, main.categoryKey(), evaluation.score(), group.mainQuestion().topicSummary(),
          evaluation.evidenceSummary(), group.mainQuestion().difficulty(), occurredAt));
    }
    return List.copyOf(result);
  }

  private static BusinessException invalid() {
    return new BusinessException(ErrorCode.INTERVIEW_EVALUATION_FAILED,
        "能力证据来源与正式冻结报告不一致");
  }
}
