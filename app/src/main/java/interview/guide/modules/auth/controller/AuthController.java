package interview.guide.modules.auth.controller;

import interview.guide.common.result.Result;
import interview.guide.modules.auth.dto.ChangePasswordRequest;
import interview.guide.modules.auth.dto.CurrentUserResponse;
import interview.guide.modules.auth.dto.InviteRegistrationRequest;
import interview.guide.modules.auth.dto.LoginRequest;
import interview.guide.modules.auth.mapper.AuthResponseMapper;
import interview.guide.modules.auth.service.AuthSessionService;
import interview.guide.modules.auth.service.InviteCodeService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {
  private final AuthSessionService sessions;
  private final InviteCodeService invites;
  private final AuthResponseMapper mapper;

  @PostMapping("/login")
  public Result<CurrentUserResponse> login(@Valid @RequestBody LoginRequest body,
      HttpServletRequest request, HttpServletResponse response) {
    return Result.success(sessions.login(body.username(), body.password(), request, response));
  }

  @PostMapping("/register-by-invite")
  public Result<CurrentUserResponse> register(@Valid @RequestBody InviteRegistrationRequest body) {
    return Result.success(mapper.toResponse(mapper.toCurrentUser(invites.register(
        body.inviteCode(), body.username(), body.password(), body.displayName()))));
  }

  @PostMapping("/logout")
  public Result<Void> logout(HttpServletRequest request, HttpServletResponse response) {
    sessions.logout(request, response);
    return Result.success();
  }

  @GetMapping("/me")
  public Result<CurrentUserResponse> me() {
    return Result.success(sessions.me());
  }

  @PostMapping("/change-password")
  public Result<Void> changePassword(@Valid @RequestBody ChangePasswordRequest body) {
    sessions.changePassword(body.currentPassword(), body.newPassword());
    return Result.success();
  }

  @GetMapping("/csrf")
  public Result<CsrfResponse> csrf(CsrfToken token) {
    return Result.success(new CsrfResponse(token.getHeaderName(), token.getToken()));
  }

  public record CsrfResponse(String headerName, String token) {
  }
}
