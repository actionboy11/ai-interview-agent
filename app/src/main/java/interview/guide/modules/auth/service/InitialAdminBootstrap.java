package interview.guide.modules.auth.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.auth.config.AuthBootstrapProperties;
import interview.guide.modules.auth.model.UserEntity;
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
    String normalized = normalizer.normalize(properties.getUsername());
    var existing = users.findByNormalizedUsername(normalized);
    if (existing.isPresent()) {
      requireAdmin(existing.get());
      return;
    }
    try {
      accounts.createUser(new CreateAdminUserCommand(properties.getUsername(), properties.getPassword(),
          properties.getDisplayName(), UserRole.ADMIN));
    } catch (BusinessException exception) {
      if (!ErrorCode.AUTH_USERNAME_TAKEN.getCode().equals(exception.getCode())) {
        throw exception;
      }
      // The proxied create transaction has already rolled back; reload the concurrent winner.
      var concurrent = users.findByNormalizedUsername(normalized).orElseThrow(() -> exception);
      requireAdmin(concurrent);
    }
  }

  private void requireAdmin(UserEntity user) {
    if (user.getRole() != UserRole.ADMIN) {
      throw new BusinessException(ErrorCode.AUTH_BOOTSTRAP_INVALID, "初始管理员用户名已被普通用户占用");
    }
  }

  private boolean blank(String value) {
    return value == null || value.isBlank();
  }
}
