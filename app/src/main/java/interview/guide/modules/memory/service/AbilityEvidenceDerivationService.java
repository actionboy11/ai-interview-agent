package interview.guide.modules.memory.service;

import interview.guide.common.model.AsyncTaskStatus;
import interview.guide.modules.auth.model.UserStatus;
import interview.guide.modules.interview.dynamic.model.InterviewExecutionMode;
import interview.guide.modules.interview.dynamic.service.DynamicAbilityEvidenceSourceService;
import interview.guide.modules.interview.model.InterviewAnswerEntity;
import interview.guide.modules.interview.model.InterviewQuestionDTO;
import interview.guide.modules.interview.model.InterviewSessionEntity;
import interview.guide.modules.interview.repository.InterviewAnswerRepository;
import interview.guide.modules.interview.repository.InterviewSessionRepository;
import interview.guide.modules.interview.planning.model.QuestionPlan;
import interview.guide.modules.interview.planning.model.QuestionSlot;
import interview.guide.modules.interview.planning.repository.InterviewQuestionPlanRepository;
import interview.guide.modules.memory.model.AbilityProfileProposalEntity;
import interview.guide.modules.memory.model.AbilityProfileStatus;
import interview.guide.modules.memory.model.AbilityProposalStatus;
import interview.guide.modules.memory.model.UserAbilityEvidenceEntity;
import interview.guide.modules.memory.model.UserAbilityEvidenceSourceEntity;
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
  private final InterviewQuestionPlanRepository plans;
  private final AbilityProfileProposalRepository proposals;
  private final UserAbilityEvidenceRepository evidence;
  private final UserAbilityProfileRepository profiles;
  private final AbilityScorePolicy scores;
  private final ObjectMapper objectMapper;
  private final DynamicAbilityEvidenceSourceService dynamicSources;

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

    List<Observation> candidates = observations(session);
    Map<String, List<Observation>> byCategory = candidates.stream()
        .collect(Collectors.groupingBy(item -> item.anchor().getCategoryKey(),
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
    List<UserAbilityEvidenceEntity> newEvidence = new ArrayList<>();
    List<AbilityProfileProposalEntity> newProposals = new ArrayList<>();

    for (var group : byCategory.entrySet()) {
      String categoryKey = group.getKey();
      AbilityProfileProposalEntity proposal = byProposal.get(categoryKey);
      if (proposal == null) {
        int sessionScore = (int) Math.round(group.getValue().stream()
            .mapToInt(Observation::score).average().orElseThrow());
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
      for (Observation observation : group.getValue()) {
        InterviewAnswerEntity answer = observation.anchor();
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
        item.setTopicSummary(observation.topic());
        item.setQuestionScore(observation.score());
        item.setDifficulty(observation.difficulty());
        item.setFeedbackSummary(observation.summary());
        item.setOccurredAt(observation.occurredAt());
        item.setSlotId(observation.slotId());
        observation.supportingAnswerIds().forEach(answerId -> item.getSources().add(
            new UserAbilityEvidenceSourceEntity(item, answerId)));
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

  private List<Observation> observations(InterviewSessionEntity session) {
    var current = answers.findBySession_SessionIdOrderByQuestionIndex(session.getSessionId());
    if (session.getExecutionMode() == InterviewExecutionMode.DYNAMIC_FOLLOW_UP) {
      var byId = current.stream().collect(Collectors.toMap(InterviewAnswerEntity::getId,
          Function.identity()));
      return dynamicSources.sources(session.getSessionId()).stream()
          .filter(source -> validCategory(source.categoryKey()))
          .map(source -> new Observation(byId.get(source.mainAnswerId()), source.score(),
              source.topicSummary(), source.evidenceSummary(), source.difficulty(),
              source.occurredAt(), source.slotId(), source.supportingAnswerIds())).toList();
    }
    var questions = questionsByIndex(session);
    var difficulties = slotDifficulties(session);
    return current.stream().filter(this::validEvidence).map(answer -> {
      var question = questions.get(answer.getQuestionIndex());
      String slot = question == null ? null : question.slotId();
      return new Observation(answer, answer.getScore(),
          question == null ? null : question.topicSummary(), answer.getFeedback(),
          slot == null ? session.getDifficulty()
              : difficulties.getOrDefault(slot, session.getDifficulty()),
          answer.getAnsweredAt().atZone(ZoneId.systemDefault()).toInstant(), null, List.of());
    }).toList();
  }

  private boolean validCategory(String key) {
    return key != null && !"OTHER".equals(key) && key.length() <= 255
        && key.matches("[A-Za-z][A-Za-z0-9_-]*");
  }

  private record Observation(InterviewAnswerEntity anchor, int score, String topic,
      String summary, String difficulty, Instant occurredAt, String slotId,
      List<Long> supportingAnswerIds) {}

  private Map<Integer, InterviewQuestionDTO> questionsByIndex(InterviewSessionEntity session) {
    if (session.getQuestionsJson() == null || session.getQuestionsJson().isBlank()) {
      return Map.of();
    }
    List<InterviewQuestionDTO> questions = objectMapper.readValue(session.getQuestionsJson(),
        new TypeReference<>() {});
    return questions.stream().collect(Collectors.toMap(InterviewQuestionDTO::questionIndex,
        Function.identity(), (first, ignored) -> first));
  }

  private Map<String, String> slotDifficulties(InterviewSessionEntity session) {
    return plans.findFirstByOwnerIdAndSessionSessionIdOrderByIdDesc(
            session.getOwner().getId(), session.getSessionId())
        .filter(plan -> plan.getSession().getId().equals(session.getId())
            && plan.getOwner().getId().equals(session.getOwner().getId()))
        .map(plan -> objectMapper.readValue(plan.getPlanJson(), QuestionPlan.class))
        .map(plan -> plan.slots().stream()
            .filter(slot -> slot.slotId() != null && slot.targetDifficulty() != null
                && !slot.targetDifficulty().isBlank())
            .collect(Collectors.toMap(QuestionSlot::slotId, QuestionSlot::targetDifficulty,
                (first, ignored) -> first)))
        .orElseGet(Map::of);
  }

  private String summary(List<Observation> categoryAnswers) {
    return categoryAnswers.stream().map(Observation::summary)
        .filter(value -> value != null && !value.isBlank()).limit(3)
        .collect(Collectors.joining("；"));
  }
}
