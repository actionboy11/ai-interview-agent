package interview.guide.modules.auth.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.auth.model.UserEntity;
import interview.guide.modules.auth.model.UserStatus;
import interview.guide.modules.auth.repository.UserRepository;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AuthService {
  // Fixed cost-10 BCrypt value, matching the encoder's default work factor; never an account password.
  private static final String DUMMY_PASSWORD_HASH =
      "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy";

  private final UserRepository users;
  private final UsernameNormalizer normalizer;
  private final BCryptPasswordEncoder passwords = new BCryptPasswordEncoder();

  @Transactional
  public UserEntity authenticate(String username, String rawPassword) {
    if (rawPassword == null || rawPassword.getBytes(StandardCharsets.UTF_8).length > 72) {
      throw invalidCredentials();
    }
    var user = users.findByNormalizedUsername(normalizer.normalize(username))
        .orElse(null);
    if (user == null) {
      passwords.matches(rawPassword, DUMMY_PASSWORD_HASH);
      throw invalidCredentials();
    }
    checkPassword(user, rawPassword);
    checkActive(user);
    Instant now = Instant.now();
    user.setLastLoginAt(now);
    user.setUpdatedAt(now);
    return user;
  }

  @Transactional
  public void changePassword(UUID userId, String currentPassword, String newPassword) {
    if (userId == null) {
      throw new BusinessException(ErrorCode.AUTH_USER_NOT_FOUND, "用户不存在");
    }
    var user = users.findById(userId).orElseThrow(() ->
        new BusinessException(ErrorCode.AUTH_USER_NOT_FOUND, "用户不存在"));
    checkActive(user);
    checkPassword(user, currentPassword);
    AdminAccountService.validatePassword(newPassword);
    user.setPasswordHash(passwords.encode(newPassword));
    user.setMustChangePassword(false);
    user.setUpdatedAt(Instant.now());
  }

  private void checkPassword(UserEntity user, String rawPassword) {
    if (rawPassword == null || rawPassword.getBytes(StandardCharsets.UTF_8).length > 72
        || !passwords.matches(rawPassword, user.getPasswordHash())) {
      throw invalidCredentials();
    }
  }

  private void checkActive(UserEntity user) {
    if (user.getStatus() != UserStatus.ACTIVE) {
      throw new BusinessException(ErrorCode.AUTH_ACCOUNT_DISABLED, "账号已禁用");
    }
  }

  private BusinessException invalidCredentials() {
    return new BusinessException(ErrorCode.AUTH_INVALID_CREDENTIALS, "用户名或密码错误");
  }
}
