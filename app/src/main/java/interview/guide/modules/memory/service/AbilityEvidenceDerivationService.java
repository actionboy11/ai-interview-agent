package interview.guide.modules.memory.service;

import interview.guide.common.model.AsyncTaskStatus;
import interview.guide.modules.auth.model.UserStatus;
import interview.guide.modules.interview.model.InterviewAnswerEntity;
import interview.guide.modules.interview.model.InterviewQuestionDTO;
import interview.guide.modules.interview.model.InterviewSessionEntity;
import interview.guide.modules.interview.repository.InterviewAnswerRepository;
import interview.guide.modules.interview.repository.InterviewSessionRepository;
import interview.guide.modules.memory.model.AbilityProfileProposalEntity;
import interview.guide.modules.memory.model.AbilityProfileStatus;
import interview.guide.modules.memory.model.AbilityProposalStatus;
import interview.guide.modules.memory.model.UserAbilityEvidenceEntity;
import interview.guide.modules.memory.model.UserAbilityProfileEntity;
import interview.guide.modules.memory.repository.AbilityProfileProposalRepository;
import interview.guide.modules.memory.repository.UserAbilityEvidenceRepository;
import interview.guide.modules.memory.repository.UserAbilityProfileRepository;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Service
@RequiredArgsConstructor
public class AbilityEvidenceDerivationService {
  private final InterviewSessionRepository sessions;
  private final InterviewAnswerRepository answers;
  private final AbilityProfileProposalRepository proposals;
  private final UserAbilityEvidenceRepository evidence;
  private final UserAbilityProfileRepository profiles;
  private final AbilityScorePolicy scores;
  private final ObjectMapper objectMapper;

  @Transactional
  public DerivationResult derive(String sessionId, UUID messageId) {
    var found = sessions.findBySessionIdForDerivation(sessionId);
    if (found.isEmpty()) {
      return DerivationResult.DISCARDED;
    }
    InterviewSessionEntity session = found.get();
    if (!eligible(session)) {
      return DerivationResult.DISCARDED;
    }
    if (session.getMemoryDerivationStatus() == AsyncTaskStatus.COMPLETED) {
      return DerivationResult.ALREADY_COMPLETED;
    }

    session.setMemoryDerivationStatus(AsyncTaskStatus.PROCESSING);
    session.setMemoryDerivationMessageId(messageId.toString());
    session.setMemoryDerivationError(null);

    List<InterviewAnswerEntity> candidates = answers.findBySession_SessionIdOrderByQuestionIndex(
        sessionId).stream().filter(this::validEvidence).toList();
    Map<String, List<InterviewAnswerEntity>> byCategory = candidates.stream()
        .collect(Collectors.groupingBy(InterviewAnswerEntity::getCategoryKey,
            LinkedHashMap::new, Collectors.toList()));
    var ownerId = session.getOwner().getId();
    Map<String, AbilityProfileProposalEntity> byProposal = proposals
        .findByOwnerIdAndInterviewSessionId(ownerId, session.getId()).stream()
        .collect(Collectors.toMap(AbilityProfileProposalEntity::getCategoryKey,
            Function.identity()));
    Map<String, UserAbilityProfileEntity> byProfile = byCategory.isEmpty() ? Map.of()
        : profiles.findByOwnerIdAndSkillIdAndCategoryKeyIn(ownerId, session.getSkillId(),
            new ArrayList<>(byCategory.keySet())).stream()
            .filter(profile -> profile.getStatus() == AbilityProfileStatus.ACTIVE)
            .collect(Collectors.toMap(UserAbilityProfileEntity::getCategoryKey,
                Function.identity()));
    Map<Long, UserAbilityEvidenceEntity> existingEvidence = evidence
        .findByOwnerIdAndInterviewSessionId(ownerId, session.getId()).stream()
        .collect(Collectors.toMap(item -> item.getInterviewAnswer().getId(), Function.identity()));
    Map<Integer, InterviewQuestionDTO> questions = questionsByIndex(session);
    List<UserAbilityEvidenceEntity> newEvidence = new ArrayList<>();
    List<AbilityProfileProposalEntity> newProposals = new ArrayList<>();

    for (var group : byCategory.entrySet()) {
      String categoryKey = group.getKey();
      AbilityProfileProposalEntity proposal = byProposal.get(categoryKey);
      if (proposal == null) {
        int sessionScore = (int) Math.round(group.getValue().stream()
            .mapToInt(InterviewAnswerEntity::getScore).average().orElseThrow());
        UserAbilityProfileEntity profile = byProfile.get(categoryKey);
        proposal = new AbilityProfileProposalEntity();
        proposal.setOwner(session.getOwner());
        proposal.setInterviewSession(session);
        proposal.setSkillId(session.getSkillId());
        proposal.setCategoryKey(categoryKey);
        if (profile != null) {
          proposal.setPreviousLevel(profile.getLevel());
          proposal.setPreviousScore(profile.getScore());
        }
        proposal.setProposedScore(sessionScore);
        proposal.setProposedLevel(scores.levelFor(sessionScore));
        proposal.setProposedSummary(summary(group.getValue()));
        proposal.setStatus(AbilityProposalStatus.PROPOSED);
        proposal.setCreatedAt(Instant.now());
        newProposals.add(proposal);
        byProposal.put(categoryKey, proposal);
      }
    }
    proposals.saveAllAndFlush(newProposals);

    for (var group : byCategory.entrySet()) {
      String categoryKey = group.getKey();
      AbilityProfileProposalEntity proposal = byProposal.get(categoryKey);
      for (InterviewAnswerEntity answer : group.getValue()) {
        if (existingEvidence.containsKey(answer.getId())) {
          continue;
        }
        UserAbilityEvidenceEntity item = new UserAbilityEvidenceEntity();
        item.setOwner(session.getOwner());
        item.setInterviewSession(session);
        item.setInterviewAnswer(answer);
        item.setProposal(proposal);
        item.setSkillId(session.getSkillId());
        item.setCategoryKey(categoryKey);
        InterviewQuestionDTO question = questions.get(answer.getQuestionIndex());
        item.setTopicSummary(question == null ? null : question.topicSummary());
        item.setQuestionScore(answer.getScore());
        item.setDifficulty(session.getDifficulty());
        item.setFeedbackSummary(answer.getFeedback());
        item.setOccurredAt(answer.getAnsweredAt().atZone(ZoneId.systemDefault()).toInstant());
        newEvidence.add(item);
      }
    }
    evidence.saveAllAndFlush(newEvidence);
    session.setMemoryDerivationStatus(AsyncTaskStatus.COMPLETED);
    session.setMemoryDerivationError(null);
    sessions.save(session);
    return DerivationResult.CREATED;
  }

  @Transactional
  public void recordFailure(String sessionId, UUID messageId, String reason) {
    sessions.findBySessionIdForDerivation(sessionId).ifPresent(session -> {
      if (session.getMemoryDerivationStatus() != AsyncTaskStatus.COMPLETED
          && eligible(session)) {
        session.setMemoryDerivationStatus(AsyncTaskStatus.FAILED);
        session.setMemoryDerivationMessageId(messageId.toString());
        session.setMemoryDerivationError(reason == null ? "Memory derivation failed"
            : reason.substring(0, Math.min(500, reason.length())));
        sessions.save(session);
      }
    });
  }

  private boolean eligible(InterviewSessionEntity session) {
    return session.getStatus() == InterviewSessionEntity.SessionStatus.EVALUATED
        && session.getEvaluateStatus() == AsyncTaskStatus.COMPLETED
        && session.getOwner() != null
        && session.getOwner().getStatus() == UserStatus.ACTIVE
        && (session.getResume() == null || (session.getResume().getOwner() != null
            && session.getOwner().getId().equals(session.getResume().getOwner().getId())))
        && session.getSkillId() != null && !session.getSkillId().isBlank();
  }

  private boolean validEvidence(InterviewAnswerEntity answer) {
    String key = answer.getCategoryKey();
    return answer.getUserAnswer() != null && !answer.getUserAnswer().isBlank()
        && key != null && !key.isBlank() && !"OTHER".equals(key)
        && key.length() <= 255 && key.matches("[A-Za-z][A-Za-z0-9_-]*")
        && answer.getScore() != null && answer.getScore() >= 0 && answer.getScore() <= 100;
  }

  private Map<Integer, InterviewQuestionDTO> questionsByIndex(InterviewSessionEntity session) {
    if (session.getQuestionsJson() == null || session.getQuestionsJson().isBlank()) {
      return Map.of();
    }
    List<InterviewQuestionDTO> questions = objectMapper.readValue(session.getQuestionsJson(),
        new TypeReference<>() {});
    return questions.stream().collect(Collectors.toMap(InterviewQuestionDTO::questionIndex,
        Function.identity(), (first, ignored) -> first));
  }

  private String summary(List<InterviewAnswerEntity> categoryAnswers) {
    return categoryAnswers.stream().map(InterviewAnswerEntity::getFeedback)
        .filter(value -> value != null && !value.isBlank()).limit(3)
        .collect(Collectors.joining("；"));
  }
}
