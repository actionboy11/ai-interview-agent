package interview.guide.modules.memory.mapper;

import interview.guide.modules.memory.dto.AbilityEvidenceResponse;
import interview.guide.modules.memory.dto.AbilityProfileResponse;
import interview.guide.modules.memory.dto.AbilityProposalResponse;
import interview.guide.modules.memory.model.AbilityProfileProposalEntity;
import interview.guide.modules.memory.model.UserAbilityEvidenceEntity;
import interview.guide.modules.memory.model.UserAbilityProfileEntity;
import interview.guide.modules.memory.service.AbilityProfileSnapshot;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface AbilityMemoryMapper {
  AbilityProfileResponse toResponse(UserAbilityProfileEntity profile);

  AbilityProfileResponse toResponse(AbilityProfileSnapshot profile);

  @Mapping(target = "interviewSessionId", source = "interviewSession.sessionId")
  AbilityProposalResponse toResponse(AbilityProfileProposalEntity proposal);

  @Mapping(target = "interviewSessionId", source = "interviewSession.sessionId")
  @Mapping(target = "proposalId", source = "proposal.id")
  @Mapping(target = "proposalStatus", source = "proposal.status")
  AbilityEvidenceResponse toResponse(UserAbilityEvidenceEntity evidence);
}
