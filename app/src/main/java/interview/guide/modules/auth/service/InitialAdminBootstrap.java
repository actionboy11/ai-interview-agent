package interview.guide.modules.auth.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.auth.config.AuthBootstrapProperties;
import interview.guide.modules.auth.model.UserRole;
import interview.guide.modules.auth.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class InitialAdminBootstrap implements ApplicationRunner {
  private final AuthBootstrapProperties properties;
  private final UserRepository users;
  private final AdminAccountService accounts;
  private final UsernameNormalizer normalizer;

  @Override
  public void run(ApplicationArguments args) {
    if (blank(properties.getUsername()) && blank(properties.getPassword())
        && blank(properties.getDisplayName())) {
      return;
    }
    if (blank(properties.getUsername()) || blank(properties.getPassword())
        || blank(properties.getDisplayName())) {
      throw new BusinessException(ErrorCode.AUTH_BOOTSTRAP_INVALID, "初始管理员配置必须完整填写");
    }
    var existing = users.findByNormalizedUsername(normalizer.normalize(properties.getUsername()));
    if (existing.isPresent()) {
      if (existing.get().getRole() != UserRole.ADMIN) {
        throw new BusinessException(ErrorCode.AUTH_BOOTSTRAP_INVALID, "初始管理员用户名已被普通用户占用");
      }
      return;
    }
    accounts.createUser(new CreateAdminUserCommand(properties.getUsername(), properties.getPassword(),
        properties.getDisplayName(), UserRole.ADMIN));
  }

  private boolean blank(String value) {
    return value == null || value.isBlank();
  }
}
