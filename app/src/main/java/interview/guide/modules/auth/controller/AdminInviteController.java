package interview.guide.modules.auth.controller;

import interview.guide.common.result.Result;
import interview.guide.modules.auth.dto.CreateInviteRequest;
import interview.guide.modules.auth.dto.IssuedInviteResponse;
import interview.guide.modules.auth.mapper.AuthResponseMapper;
import interview.guide.modules.auth.security.CurrentUserProvider;
import interview.guide.modules.auth.service.InviteCodeService;
import jakarta.validation.Valid;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/invites")
@RequiredArgsConstructor
public class AdminInviteController {
  private final InviteCodeService invites;
  private final CurrentUserProvider currentUsers;
  private final AuthResponseMapper mapper;

  @PostMapping
  public Result<IssuedInviteResponse> issue(@Valid @RequestBody CreateInviteRequest body) {
    return Result.success(mapper.toInviteResponse(
        invites.issue(Duration.ofSeconds(body.ttlSeconds()), currentUsers.requireUserId())));
  }
}
