package interview.guide.modules.memory.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.interview.repository.InterviewSessionRepository;
import interview.guide.modules.memory.dto.AbilityEvidenceResponse;
import interview.guide.modules.memory.dto.AbilityProfileResponse;
import interview.guide.modules.memory.dto.AbilityProposalResponse;
import interview.guide.modules.memory.mapper.AbilityMemoryMapper;
import interview.guide.modules.memory.repository.AbilityProfileProposalRepository;
import interview.guide.modules.memory.repository.UserAbilityEvidenceRepository;
import interview.guide.modules.memory.repository.UserAbilityProfileRepository;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AbilityProfileQueryService {
  private final UserAbilityProfileRepository profiles;
  private final AbilityProfileProposalRepository proposals;
  private final UserAbilityEvidenceRepository evidence;
  private final InterviewSessionRepository sessions;
  private final AbilityMemoryMapper mapper;

  @Transactional(readOnly = true)
  public List<AbilityProfileResponse> profiles(UUID ownerId, String skillId) {
    var found = skillId == null ? profiles.findByOwnerIdOrderBySkillIdAscCategoryKeyAsc(ownerId)
        : profiles.findByOwnerIdAndSkillIdOrderByCategoryKeyAsc(ownerId, skillId);
    return found.stream().map(mapper::toResponse).toList();
  }

  @Transactional(readOnly = true)
  public AbilityProfileResponse profile(UUID ownerId, Long id) {
    return mapper.toResponse(profiles.findByOwnerIdAndId(ownerId, id)
        .orElseThrow(() -> new BusinessException(ErrorCode.ABILITY_PROFILE_NOT_FOUND)));
  }

  @Transactional(readOnly = true)
  public List<AbilityProposalResponse> proposals(UUID ownerId, String sessionId) {
    var found = sessionId == null ? proposals.findByOwnerIdOrderByCreatedAtDescIdDesc(ownerId)
        : proposals.findByOwnerIdAndInterviewSessionId(ownerId, sessions
            .findByOwnerIdAndSessionId(ownerId, sessionId)
            .orElseThrow(() -> new BusinessException(ErrorCode.INTERVIEW_SESSION_NOT_FOUND))
            .getId());
    return found.stream().map(mapper::toResponse).toList();
  }

  @Transactional(readOnly = true)
  public AbilityProposalResponse proposal(UUID ownerId, Long id) {
    return mapper.toResponse(proposals.findByOwnerIdAndId(ownerId, id)
        .orElseThrow(() -> new BusinessException(ErrorCode.ABILITY_PROPOSAL_NOT_FOUND)));
  }

  @Transactional(readOnly = true)
  public List<AbilityEvidenceResponse> evidenceForProposal(UUID ownerId, Long proposalId) {
    proposal(ownerId, proposalId);
    return evidence.findByOwnerIdAndProposalId(ownerId, proposalId).stream()
        .map(mapper::toResponse).toList();
  }

  @Transactional(readOnly = true)
  public List<AbilityEvidenceResponse> evidenceForProfile(UUID ownerId, Long profileId) {
    var profile = profiles.findByOwnerIdAndId(ownerId, profileId)
        .orElseThrow(() -> new BusinessException(ErrorCode.ABILITY_PROFILE_NOT_FOUND));
    return evidence.findByOwnerIdAndSkillIdAndCategoryKey(ownerId, profile.getSkillId(),
        profile.getCategoryKey()).stream().map(mapper::toResponse).toList();
  }
}
