package interview.guide.modules.memory.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.auth.repository.UserRepository;
import interview.guide.modules.interview.repository.InterviewSessionRepository;
import interview.guide.modules.interview.dynamic.service.DynamicInterviewLifecycle;
import interview.guide.modules.memory.model.AbilityProfileProposalEntity;
import interview.guide.modules.memory.model.AbilityProfileStatus;
import interview.guide.modules.memory.model.AbilityProposalStatus;
import interview.guide.modules.memory.model.UserAbilityEvidenceEntity;
import interview.guide.modules.memory.model.UserAbilityProfileEntity;
import interview.guide.modules.memory.repository.AbilityProfileProposalRepository;
import interview.guide.modules.memory.repository.UserAbilityEvidenceRepository;
import interview.guide.modules.memory.repository.UserAbilityProfileRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AbilityMemoryDeletionService {
  private final InterviewSessionRepository sessions;
  private final AbilityProfileProposalRepository proposals;
  private final UserAbilityEvidenceRepository evidence;
  private final UserAbilityProfileRepository profiles;
  private final AbilityScorePolicy scores;
  private final UserRepository users;
  private final DynamicInterviewLifecycle dynamicLifecycle;

  /** Removes memory before answers and sessions, then replays only surviving accepted evidence. */
  @Transactional
  public void deleteOwnedSessions(UUID ownerId, List<Long> sessionIds) {
    if (sessionIds.isEmpty()) {
      return;
    }
    users.findByIdForAbilityDecision(ownerId)
        .orElseThrow(() -> new BusinessException(ErrorCode.INTERVIEW_SESSION_NOT_FOUND));
    List<Long> uniqueIds = sessionIds.stream().distinct().sorted().toList();
    dynamicLifecycle.prepareDeletion(ownerId, uniqueIds);
    var owned = sessions.findByOwnerIdAndIdInOrderById(ownerId, uniqueIds);
    if (owned.size() != uniqueIds.size()) {
      throw new BusinessException(ErrorCode.INTERVIEW_SESSION_NOT_FOUND);
    }
    List<AbilityProfileProposalEntity> removedProposals = new ArrayList<>();
    List<UserAbilityEvidenceEntity> removedEvidence = new ArrayList<>();
    Map<String, Set<String>> affected = new HashMap<>();
    for (var session : owned) {
      var sourceProposals = proposals.findByOwnerIdAndInterviewSessionId(ownerId, session.getId());
      removedProposals.addAll(sourceProposals);
      removedEvidence.addAll(evidence.findByOwnerIdAndInterviewSessionId(ownerId,
          session.getId()));
      sourceProposals.stream().filter(this::accepted).forEach(proposal ->
          affected.computeIfAbsent(proposal.getSkillId(), ignored -> new HashSet<>())
              .add(proposal.getCategoryKey()));
    }
    evidence.deleteAll(removedEvidence);
    proposals.deleteAll(removedProposals);
    evidence.flush();
    proposals.flush();
    recalculate(ownerId, affected);
    sessions.deleteAll(owned);
    sessions.flush();
  }

  private void recalculate(UUID ownerId, Map<String, Set<String>> affected) {
    for (var skill : affected.entrySet()) {
      List<String> keys = List.copyOf(skill.getValue());
      Map<String, UserAbilityProfileEntity> existing = profiles
          .findByOwnerIdAndSkillIdAndCategoryKeyIn(ownerId, skill.getKey(), keys).stream()
          .collect(Collectors.toMap(UserAbilityProfileEntity::getCategoryKey, item -> item));
      Map<String, List<UserAbilityEvidenceEntity>> remaining = evidence
          .findByOwnerIdAndSkillIdAndCategoryKeyIn(ownerId, skill.getKey(), keys).stream()
          .filter(item -> accepted(item.getProposal()))
          .collect(Collectors.groupingBy(UserAbilityEvidenceEntity::getCategoryKey));
      for (String key : keys) {
        var profile = existing.get(key);
        if (profile == null) {
          continue;
        }
        var items = remaining.getOrDefault(key, List.of());
        if (items.isEmpty()) {
          profile.setConfirmedEvidenceCount(0);
          profile.setConfidence(0);
          profile.setStatus(AbilityProfileStatus.DISABLED);
          profile.setManuallyEdited(false);
          profile.setLastEvaluatedAt(null);
          continue;
        }
        Map<Long, List<UserAbilityEvidenceEntity>> byProposal = items.stream()
            .collect(Collectors.groupingBy(item -> item.getProposal().getId()));
        var groups = byProposal.values().stream().sorted(Comparator
            .comparing((List<UserAbilityEvidenceEntity> group) ->
                group.getFirst().getProposal().getDecisionOrder(),
                Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(group -> group.getFirst().getProposal().getId())).toList();
        Integer replayed = null;
        for (var group : groups) {
          int sessionScore = (int) Math.round(group.stream()
              .mapToInt(UserAbilityEvidenceEntity::getQuestionScore).average().orElseThrow());
          replayed = replayed == null ? sessionScore : scores.mergeScore(replayed, sessionScore);
        }
        profile.setConfirmedEvidenceCount(items.size());
        profile.setScore(replayed);
        profile.setConfidence(Math.min(1.0, 0.25 + groups.size() * 0.25));
        profile.setLastEvaluatedAt(items.stream().map(UserAbilityEvidenceEntity::getOccurredAt)
            .max(Instant::compareTo).orElse(null));
        var manualDecisions = groups.stream().map(group -> group.getFirst().getProposal())
            .filter(proposal -> proposal.getStatus() == AbilityProposalStatus.ACCEPTED_WITH_EDIT)
            .toList();
        profile.setManuallyEdited(!manualDecisions.isEmpty());
        if (!manualDecisions.isEmpty()) {
          var latest = manualDecisions.getLast();
          profile.setLevel(latest.getFinalLevel());
          profile.setSummary(latest.getFinalSummary());
          if (profile.getStatus() == AbilityProfileStatus.DISABLED) {
            if (profile.getStatusBeforeDisable() != null) {
              profile.setStatusBeforeDisable(AbilityProfileStatus.NEEDS_REVIEW);
            } else {
              profile.setStatus(AbilityProfileStatus.NEEDS_REVIEW);
            }
          } else {
            profile.setStatus(AbilityProfileStatus.NEEDS_REVIEW);
          }
        } else {
          var latest = groups.getLast().getFirst().getProposal();
          profile.setLevel(scores.levelFor(replayed));
          profile.setSummary(latest.getFinalSummary());
          if (profile.getStatus() != AbilityProfileStatus.DISABLED
              || profile.getStatusBeforeDisable() == null) {
            profile.setStatus(AbilityProfileStatus.ACTIVE);
          }
        }
      }
      profiles.saveAllAndFlush(existing.values());
    }
  }

  private boolean accepted(AbilityProfileProposalEntity proposal) {
    return proposal.getStatus() == AbilityProposalStatus.ACCEPTED
        || proposal.getStatus() == AbilityProposalStatus.ACCEPTED_WITH_EDIT;
  }
}
