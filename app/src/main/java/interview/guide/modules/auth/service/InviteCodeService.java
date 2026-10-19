package interview.guide.modules.auth.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.auth.model.RegistrationInviteEntity;
import interview.guide.modules.auth.model.RegistrationInviteStatus;
import interview.guide.modules.auth.model.UserEntity;
import interview.guide.modules.auth.model.UserRole;
import interview.guide.modules.auth.model.UserStatus;
import interview.guide.modules.auth.repository.RegistrationInviteRepository;
import interview.guide.modules.auth.repository.UserRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class InviteCodeService {
  private final RegistrationInviteRepository invites;
  private final UserRepository users;
  private final AdminAccountService accounts;
  private final SecureRandom random = new SecureRandom();

  @Transactional
  public IssuedInvite issue(Duration ttl, UUID adminUserId) {
    if (ttl == null || ttl.isNegative() || ttl.isZero()) {
      throw new BusinessException(ErrorCode.AUTH_INVALID_INPUT, "邀请码有效期必须大于零");
    }
    if (adminUserId == null) {
      throw new BusinessException(ErrorCode.AUTH_ADMIN_REQUIRED, "需要启用的管理员账号");
    }
    var admin = users.findById(adminUserId).orElseThrow(() ->
        new BusinessException(ErrorCode.AUTH_ADMIN_REQUIRED, "需要启用的管理员账号"));
    if (admin.getRole() != UserRole.ADMIN || admin.getStatus() != UserStatus.ACTIVE) {
      throw new BusinessException(ErrorCode.AUTH_ADMIN_REQUIRED, "需要启用的管理员账号");
    }
    Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
    Instant expiresAt;
    try {
      expiresAt = now.plus(ttl).truncatedTo(ChronoUnit.MICROS);
    } catch (DateTimeException | ArithmeticException exception) {
      throw new BusinessException(ErrorCode.AUTH_INVALID_INPUT, "邀请码有效期超出范围");
    }
    if (!expiresAt.isAfter(now)) {
      throw new BusinessException(ErrorCode.AUTH_INVALID_INPUT, "邀请码有效期不能小于一微秒");
    }
    byte[] bytes = new byte[32];
    random.nextBytes(bytes);
    String rawCode = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    var saved = invites.saveAndFlush(RegistrationInviteEntity.builder().inviteHash(hash(rawCode))
        .status(RegistrationInviteStatus.ACTIVE).expiresAt(expiresAt).createdBy(admin)
        .createdAt(now).updatedAt(now).build());
    return new IssuedInvite(saved.getId(), rawCode, saved.getExpiresAt());
  }

  @Transactional
  public UserEntity register(String rawCode, String username, String password, String displayName) {
    if (rawCode == null || rawCode.isBlank() || rawCode.length() != 43) {
      throw new BusinessException(ErrorCode.AUTH_INVITE_INVALID, "邀请码无效");
    }
    var user = accounts.createAccount(username, password, displayName, UserRole.USER, false);
    if (invites.consumeActiveInvite(hash(rawCode), user.getId(), Instant.now()) != 1) {
      // Runtime business failure rolls back the user INSERT and the consumption together.
      throw new BusinessException(ErrorCode.AUTH_INVITE_INVALID, "邀请码无效、已使用、已撤销或已过期");
    }
    return user;
  }

  private String hash(String rawCode) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
          .digest(rawCode.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is required by the Java runtime", exception);
    }
  }
}
