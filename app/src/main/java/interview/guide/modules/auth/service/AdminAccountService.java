package interview.guide.modules.auth.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.auth.model.UserEntity;
import interview.guide.modules.auth.model.UserRole;
import interview.guide.modules.auth.model.UserStatus;
import interview.guide.modules.auth.repository.UserRepository;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AdminAccountService {
  private final UserRepository users;
  private final UsernameNormalizer normalizer;
  private final BCryptPasswordEncoder passwords = new BCryptPasswordEncoder();

  @Transactional
  public UserEntity createUser(CreateAdminUserCommand command) {
    if (command == null) {
      throw new BusinessException(ErrorCode.AUTH_INVALID_INPUT, "账号参数不能为空");
    }
    return createAccount(command.username(), command.password(), command.displayName(),
        command.role(), true);
  }

  @Transactional
  public void disable(UUID userId) {
    setStatus(userId, UserStatus.DISABLED);
  }

  @Transactional
  public void enable(UUID userId) {
    setStatus(userId, UserStatus.ACTIVE);
  }

  // Both callers are Service transaction boundaries; invite consumption joins this same transaction.
  UserEntity createAccount(String username, String password, String displayName, UserRole role,
      boolean mustChangePassword) {
    String normalized = normalizer.normalize(username);
    validatePassword(password);
    if (displayName == null || displayName.isBlank() || displayName.strip().length() > 100
        || role == null) {
      throw new BusinessException(ErrorCode.AUTH_INVALID_INPUT, "显示名称或角色无效");
    }
    if (users.findByNormalizedUsername(normalized).isPresent()) {
      throw new BusinessException(ErrorCode.AUTH_USERNAME_TAKEN, "用户名已存在");
    }
    Instant now = Instant.now();
    var user = UserEntity.builder().username(username.strip()).normalizedUsername(normalized)
        .passwordHash(passwords.encode(password)).displayName(displayName.strip()).role(role)
        .status(UserStatus.ACTIVE).mustChangePassword(mustChangePassword)
        .createdAt(now).updatedAt(now).build();
    try {
      return users.saveAndFlush(user);
    } catch (DataIntegrityViolationException exception) {
      // The normalized username is the sole user-controlled unique column (UUID is generated).
      throw new BusinessException(ErrorCode.AUTH_USERNAME_TAKEN, "用户名已存在");
    }
  }

  static void validatePassword(String password) {
    if (password == null || password.isBlank()
        || password.getBytes(StandardCharsets.UTF_8).length > 72) {
      throw new BusinessException(ErrorCode.AUTH_INVALID_INPUT, "密码不能为空且不能超过 72 UTF-8 字节");
    }
  }

  private void setStatus(UUID userId, UserStatus status) {
    if (userId == null) {
      throw new BusinessException(ErrorCode.AUTH_USER_NOT_FOUND, "用户不存在");
    }
    var user = users.findById(userId).orElseThrow(() ->
        new BusinessException(ErrorCode.AUTH_USER_NOT_FOUND, "用户不存在"));
    user.setStatus(status);
    user.setUpdatedAt(Instant.now());
  }
}
