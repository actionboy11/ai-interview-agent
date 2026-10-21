package interview.guide.modules.memory.controller;

import interview.guide.common.result.Result;
import interview.guide.modules.auth.security.CurrentUserProvider;
import interview.guide.modules.memory.dto.AbilityEvidenceResponse;
import interview.guide.modules.memory.dto.AbilityProfileResponse;
import interview.guide.modules.memory.dto.AbilityProposalResponse;
import interview.guide.modules.memory.dto.AcceptProposalRequest;
import interview.guide.modules.memory.dto.AcceptProposalWithEditRequest;
import interview.guide.modules.memory.mapper.AbilityMemoryMapper;
import interview.guide.modules.memory.service.AbilityProfileCommandService;
import interview.guide.modules.memory.service.AbilityProfileQueryService;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/ability-memory")
@RequiredArgsConstructor
public class AbilityMemoryController {
  private final CurrentUserProvider currentUser;
  private final AbilityProfileCommandService commands;
  private final AbilityProfileQueryService queries;
  private final AbilityMemoryMapper mapper;

  @GetMapping("/profiles")
  public Result<List<AbilityProfileResponse>> profiles(@RequestParam(required = false) String skillId) {
    return Result.success(queries.profiles(currentUser.requireUserId(), skillId));
  }

  @GetMapping("/profiles/{id}")
  public Result<AbilityProfileResponse> profile(@PathVariable Long id) {
    return Result.success(queries.profile(currentUser.requireUserId(), id));
  }

  @GetMapping("/profiles/{id}/evidence")
  public Result<List<AbilityEvidenceResponse>> profileEvidence(@PathVariable Long id) {
    return Result.success(queries.evidenceForProfile(currentUser.requireUserId(), id));
  }

  @GetMapping("/proposals")
  public Result<List<AbilityProposalResponse>> proposals(
      @RequestParam(required = false) String sessionId) {
    return Result.success(queries.proposals(currentUser.requireUserId(), sessionId));
  }

  @GetMapping("/proposals/{id}")
  public Result<AbilityProposalResponse> proposal(@PathVariable Long id) {
    return Result.success(queries.proposal(currentUser.requireUserId(), id));
  }

  @GetMapping("/proposals/{id}/evidence")
  public Result<List<AbilityEvidenceResponse>> proposalEvidence(@PathVariable Long id) {
    return Result.success(queries.evidenceForProposal(currentUser.requireUserId(), id));
  }

  @PostMapping("/proposals/{id}/accept")
  public Result<AbilityProfileResponse> accept(@PathVariable Long id,
      @Valid @RequestBody AcceptProposalRequest request) {
    return Result.success(mapper.toResponse(commands.accept(currentUser.requireUserId(), id,
        request.version())));
  }

  @PostMapping("/proposals/{id}/accept-with-edit")
  public Result<AbilityProfileResponse> acceptWithEdit(@PathVariable Long id,
      @Valid @RequestBody AcceptProposalWithEditRequest request) {
    return Result.success(mapper.toResponse(commands.acceptWithEdit(currentUser.requireUserId(),
        id, request.version(), request.level(), request.summary())));
  }

  @PostMapping("/proposals/{id}/reject")
  public Result<Void> reject(@PathVariable Long id,
      @Valid @RequestBody AcceptProposalRequest request) {
    commands.reject(currentUser.requireUserId(), id, request.version());
    return Result.success();
  }

  @PostMapping("/profiles/{id}/disable")
  public Result<AbilityProfileResponse> disable(@PathVariable Long id,
      @Valid @RequestBody AcceptProposalRequest request) {
    return Result.success(mapper.toResponse(commands.disable(currentUser.requireUserId(), id,
        request.version())));
  }

  @PostMapping("/profiles/{id}/enable")
  public Result<AbilityProfileResponse> enable(@PathVariable Long id,
      @Valid @RequestBody AcceptProposalRequest request) {
    return Result.success(mapper.toResponse(commands.enable(currentUser.requireUserId(), id,
        request.version())));
  }
}
