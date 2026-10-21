package interview.guide.modules.memory.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.auth.repository.UserRepository;
import interview.guide.modules.memory.model.AbilityLevel;
import interview.guide.modules.memory.model.AbilityProfileProposalEntity;
import interview.guide.modules.memory.model.AbilityProfileStatus;
import interview.guide.modules.memory.model.AbilityProposalStatus;
import interview.guide.modules.memory.model.UserAbilityProfileEntity;
import interview.guide.modules.memory.repository.AbilityProfileProposalRepository;
import interview.guide.modules.memory.repository.UserAbilityEvidenceRepository;
import interview.guide.modules.memory.repository.UserAbilityProfileRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AbilityProfileCommandService {
  private final AbilityProfileProposalRepository proposals;
  private final UserAbilityProfileRepository profiles;
  private final UserAbilityEvidenceRepository evidence;
  private final AbilityScorePolicy scores;
  private final UserRepository users;

  @Transactional
  public AbilityProfileSnapshot accept(UUID ownerId, Long proposalId, long version) {
    return decide(ownerId, proposalId, version, null, null, false);
  }

  @Transactional
  public AbilityProfileSnapshot acceptWithEdit(UUID ownerId, Long proposalId, long version,
      AbilityLevel level, String summary) {
    if (level == null || summary == null || summary.isBlank() || summary.length() > 2000) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "等级和摘要不能为空，摘要最多2000字");
    }
    return decide(ownerId, proposalId, version, level, summary.trim(), true);
  }

  @Transactional
  public void reject(UUID ownerId, Long proposalId, long version) {
    lockOwner(ownerId, ErrorCode.ABILITY_PROPOSAL_NOT_FOUND);
    var proposal = requireProposal(ownerId, proposalId, version);
    proposal.setStatus(AbilityProposalStatus.REJECTED);
    proposal.setDecidedAt(Instant.now());
    flushProposal(proposal);
  }

  @Transactional
  public AbilityProfileSnapshot disable(UUID ownerId, Long profileId, long version) {
    lockOwner(ownerId, ErrorCode.ABILITY_PROFILE_NOT_FOUND);
    var profile = requireProfile(ownerId, profileId, version);
    if (profile.getStatus() == AbilityProfileStatus.DISABLED) {
      throw new BusinessException(ErrorCode.ABILITY_INVALID_STATE);
    }
    profile.setStatusBeforeDisable(profile.getStatus());
    profile.setStatus(AbilityProfileStatus.DISABLED);
    return flushProfile(profile);
  }

  @Transactional
  public AbilityProfileSnapshot enable(UUID ownerId, Long profileId, long version) {
    lockOwner(ownerId, ErrorCode.ABILITY_PROFILE_NOT_FOUND);
    var profile = requireProfile(ownerId, profileId, version);
    if (profile.getStatus() != AbilityProfileStatus.DISABLED
        || profile.getConfirmedEvidenceCount() == 0) {
      throw new BusinessException(ErrorCode.ABILITY_INVALID_STATE);
    }
    profile.setStatus(profile.getStatusBeforeDisable() == AbilityProfileStatus.NEEDS_REVIEW
        ? AbilityProfileStatus.NEEDS_REVIEW : AbilityProfileStatus.ACTIVE);
    profile.setStatusBeforeDisable(null);
    return flushProfile(profile);
  }

  private AbilityProfileSnapshot decide(UUID ownerId, Long proposalId, long version,
      AbilityLevel editedLevel, String editedSummary, boolean edited) {
    lockOwner(ownerId, ErrorCode.ABILITY_PROPOSAL_NOT_FOUND);
    var proposal = requireProposal(ownerId, proposalId, version);
    var profile = profiles.findByOwnerIdAndSkillIdAndCategoryKey(ownerId,
        proposal.getSkillId(), proposal.getCategoryKey()).orElseGet(() -> {
          var created = new UserAbilityProfileEntity();
          created.setOwner(proposal.getOwner());
          created.setSkillId(proposal.getSkillId());
          created.setCategoryKey(proposal.getCategoryKey());
          created.setStatus(AbilityProfileStatus.ACTIVE);
          return created;
        });
    boolean hasAcceptedEvidence = profile.getId() != null
        && profile.getConfirmedEvidenceCount() > 0;
    int merged = !hasAcceptedEvidence ? proposal.getProposedScore()
        : scores.mergeScore(profile.getScore(), proposal.getProposedScore());
    var items = evidence.findByOwnerIdAndProposalId(ownerId, proposalId);
    if (items.isEmpty()) {
      throw new BusinessException(ErrorCode.ABILITY_INVALID_STATE, "候选缺少有效证据");
    }
    Instant now = Instant.now();
    profile.setScore(merged);
    profile.setLevel(edited ? editedLevel : scores.levelFor(merged));
    profile.setSummary(edited ? editedSummary : proposal.getProposedSummary());
    profile.setManuallyEdited((hasAcceptedEvidence && profile.isManuallyEdited()) || edited);
    profile.setConfirmedEvidenceCount(profile.getConfirmedEvidenceCount() + items.size());
    long sessions = evidence.countDistinctAcceptedSessions(ownerId, proposal.getSkillId(),
        proposal.getCategoryKey(),
        List.of(AbilityProposalStatus.ACCEPTED, AbilityProposalStatus.ACCEPTED_WITH_EDIT)) + 1;
    profile.setConfidence(Math.min(1.0, 0.25 + sessions * 0.25));
    profile.setLastEvaluatedAt(items.stream().map(item -> item.getOccurredAt())
        .max(Instant::compareTo).orElse(now));
    profile.setLastConfirmedAt(now);
    if (profile.getStatus() == AbilityProfileStatus.DISABLED
        && profile.getStatusBeforeDisable() != null) {
      profile.setStatusBeforeDisable(AbilityProfileStatus.ACTIVE);
    } else {
      profile.setStatus(AbilityProfileStatus.ACTIVE);
    }
    proposal.setFinalScore(merged);
    proposal.setFinalLevel(profile.getLevel());
    proposal.setFinalSummary(profile.getSummary());
    proposal.setDecisionOrder(proposals.maxDecisionOrder(ownerId, proposal.getSkillId(),
        proposal.getCategoryKey()) + 1);
    proposal.setStatus(edited ? AbilityProposalStatus.ACCEPTED_WITH_EDIT
        : AbilityProposalStatus.ACCEPTED);
    proposal.setDecidedAt(now);
    flushProposal(proposal);
    return flushProfile(profile);
  }

  private AbilityProfileProposalEntity requireProposal(UUID ownerId, Long id, long version) {
    var proposal = proposals.findByOwnerIdAndId(ownerId, id)
        .orElseThrow(() -> new BusinessException(ErrorCode.ABILITY_PROPOSAL_NOT_FOUND));
    if (proposal.getVersion() != version) {
      throw new BusinessException(ErrorCode.ABILITY_DECISION_CONFLICT);
    }
    if (proposal.getStatus() != AbilityProposalStatus.PROPOSED) {
      throw new BusinessException(ErrorCode.ABILITY_INVALID_STATE);
    }
    return proposal;
  }

  private void lockOwner(UUID ownerId, ErrorCode missing) {
    users.findByIdForAbilityDecision(ownerId)
        .orElseThrow(() -> new BusinessException(missing));
  }

  private UserAbilityProfileEntity requireProfile(UUID ownerId, Long id, long version) {
    var profile = profiles.findByOwnerIdAndId(ownerId, id)
        .orElseThrow(() -> new BusinessException(ErrorCode.ABILITY_PROFILE_NOT_FOUND));
    if (profile.getVersion() != version) {
      throw new BusinessException(ErrorCode.ABILITY_DECISION_CONFLICT);
    }
    return profile;
  }

  private void flushProposal(AbilityProfileProposalEntity proposal) {
    try {
      proposals.saveAndFlush(proposal);
    } catch (OptimisticLockingFailureException conflict) {
      throw new BusinessException(ErrorCode.ABILITY_DECISION_CONFLICT);
    }
  }

  private AbilityProfileSnapshot flushProfile(UserAbilityProfileEntity profile) {
    try {
      return snapshot(profiles.saveAndFlush(profile));
    } catch (OptimisticLockingFailureException conflict) {
      throw new BusinessException(ErrorCode.ABILITY_DECISION_CONFLICT);
    }
  }

  public static AbilityProfileSnapshot snapshot(UserAbilityProfileEntity profile) {
    return new AbilityProfileSnapshot(profile.getId(), profile.getSkillId(),
        profile.getCategoryKey(), profile.getLevel(), profile.getScore(),
        profile.getConfidence(), profile.getSummary(), profile.getConfirmedEvidenceCount(),
        profile.getStatus(), profile.isManuallyEdited(), profile.getLastEvaluatedAt(),
        profile.getLastConfirmedAt(), profile.getVersion());
  }
}
