package interview.guide.modules.auth.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.auth.mapper.AuthResponseMapper;
import interview.guide.modules.auth.model.UserStatus;
import interview.guide.modules.auth.repository.UserRepository;
import interview.guide.modules.auth.security.CurrentUser;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AuthUserQueryService {
  private final UserRepository users;
  private final AuthResponseMapper mapper;

  @Transactional(readOnly = true)
  public CurrentUser requireActive(UUID id) {
    var user = users.findById(id).orElseThrow(() ->
        new BusinessException(ErrorCode.UNAUTHORIZED, "请先登录"));
    if (user.getStatus() != UserStatus.ACTIVE) {
      throw new BusinessException(ErrorCode.AUTH_ACCOUNT_DISABLED, "账号已禁用");
    }
    return mapper.toCurrentUser(user);
  }
}
