package interview.guide.modules.auth.security;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.auth.model.UserRole;
import interview.guide.modules.auth.service.AuthUserQueryService;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class CurrentUserProvider {
  private final AuthUserQueryService users;

  public UUID requireUserId() {
    return requireCurrentUser().id();
  }

  public CurrentUser requireCurrentUser() {
    var authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication == null || !authentication.isAuthenticated()
        || !(authentication.getPrincipal() instanceof CurrentUser principal)) {
      throw new BusinessException(ErrorCode.UNAUTHORIZED, "请先登录");
    }
    return users.requireActive(principal.id());
  }

  public boolean isAdmin() {
    return requireCurrentUser().role() == UserRole.ADMIN;
  }
}
