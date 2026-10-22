package interview.guide.modules.auth.controller;

import interview.guide.common.result.Result;
import interview.guide.modules.auth.dto.AdminCreateUserRequest;
import interview.guide.modules.auth.dto.AdminUserResponse;
import interview.guide.modules.auth.mapper.AuthResponseMapper;
import interview.guide.modules.auth.service.AdminAccountService;
import interview.guide.modules.auth.service.AuthSessionService;
import interview.guide.modules.auth.service.CreateAdminUserCommand;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/users")
@RequiredArgsConstructor
public class AdminUserController {
  private final AdminAccountService accounts;
  private final AuthSessionService sessions;
  private final AuthResponseMapper mapper;

  @GetMapping
  public Result<Page<AdminUserResponse>> list(@RequestParam(defaultValue = "") String search,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return Result.success(accounts.list(search, page, size));
  }

  @PostMapping
  public Result<AdminUserResponse> create(@Valid @RequestBody AdminCreateUserRequest body) {
    return Result.success(mapper.toAdminResponse(accounts.createUser(new CreateAdminUserCommand(
        body.username(), body.password(), body.displayName(), body.role()))));
  }

  @PostMapping("/{id}/disable")
  public Result<Void> disable(@PathVariable UUID id) {
    sessions.disable(id);
    return Result.success();
  }

  @PostMapping("/{id}/enable")
  public Result<Void> enable(@PathVariable UUID id) {
    sessions.enable(id);
    return Result.success();
  }
}
