package interview.guide.modules.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.auth.model.UserRole;
import interview.guide.modules.auth.model.UserStatus;
import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

@DisplayName("账号服务")
class AuthServiceTest extends AuthServiceTestSupport {
  @Test
  @DisplayName("管理员创建临时密码用户并只保存 BCrypt 哈希")
  void storesBcryptAndRequiresPasswordChange() {
    var user = createUser("Alice", UserRole.USER);
    var saved = users.findById(user.getId()).orElseThrow();
    assertThat(saved.getUsername()).isEqualTo("Alice");
    assertThat(saved.getNormalizedUsername()).isEqualTo("alice");
    assertThat(saved.getPasswordHash()).startsWith("$2").isNotEqualTo("secret-password");
    assertThat(new BCryptPasswordEncoder().matches("secret-password", saved.getPasswordHash()))
        .isTrue();
    assertThat(saved.getRole()).isEqualTo(UserRole.USER);
    assertThat(saved.getStatus()).isEqualTo(UserStatus.ACTIVE);
    assertThat(saved.isMustChangePassword()).isTrue();
    assertThat(saved.getCreatedAt()).isNotNull();
  }

  @Test
  @DisplayName("注册创建登录统一 Unicode 归一化且不受土耳其语默认语言影响")
  void normalizesUnicodeAndLocaleConsistently() {
    var previous = Locale.getDefault();
    try {
      Locale.setDefault(Locale.forLanguageTag("tr-TR"));
      var user = createUser("  ＡLICÉ  ", UserRole.USER);
      assertThat(user.getNormalizedUsername()).isEqualTo("alicé");
      assertThat(auth.authenticate("ALICÉ", "secret-password").getId()).isEqualTo(user.getId());
      assertThatThrownBy(() -> createUser("Alicé", UserRole.USER))
          .isInstanceOfSatisfying(BusinessException.class,
              error -> assertThat(error.getCode()).isEqualTo(ErrorCode.AUTH_USERNAME_TAKEN.getCode()));
    } finally {
      Locale.setDefault(previous);
    }
  }

  @Test
  @DisplayName("登录记录时间且错误密码和不存在用户返回相同凭据错误")
  void authenticatesAndRejectsBadCredentials() {
    var user = createUser("Alice", UserRole.USER);
    auth.authenticate("aLiCe", "secret-password");
    assertThat(users.findById(user.getId()).orElseThrow().getLastLoginAt()).isNotNull();
    assertThatThrownBy(() -> auth.authenticate("Alice", "wrong"))
        .isInstanceOfSatisfying(BusinessException.class, error -> assertThat(error.getCode())
            .isEqualTo(ErrorCode.AUTH_INVALID_CREDENTIALS.getCode()));
    assertThatThrownBy(() -> auth.authenticate("missing", "wrong"))
        .isInstanceOfSatisfying(BusinessException.class, error -> assertThat(error.getCode())
            .isEqualTo(ErrorCode.AUTH_INVALID_CREDENTIALS.getCode()));
  }

  @Test
  @DisplayName("停用阻止登录和改密，启用后恢复登录")
  void persistsDisableAndEnable() {
    var user = createUser("Alice", UserRole.USER);
    accounts.disable(user.getId());
    assertThat(users.findById(user.getId()).orElseThrow().getStatus()).isEqualTo(UserStatus.DISABLED);
    assertThatThrownBy(() -> auth.authenticate("Alice", "secret-password"))
        .isInstanceOfSatisfying(BusinessException.class, error -> assertThat(error.getCode())
            .isEqualTo(ErrorCode.AUTH_ACCOUNT_DISABLED.getCode()));
    assertThatThrownBy(() -> auth.changePassword(user.getId(), "secret-password", "new-password"))
        .isInstanceOfSatisfying(BusinessException.class, error -> assertThat(error.getCode())
            .isEqualTo(ErrorCode.AUTH_ACCOUNT_DISABLED.getCode()));
    accounts.enable(user.getId());
    assertThat(auth.authenticate("Alice", "secret-password").getStatus()).isEqualTo(UserStatus.ACTIVE);
  }

  @Test
  @DisplayName("改密验证旧密码，保存新哈希并清除首次改密标记")
  void changesPassword() {
    var user = createUser("Alice", UserRole.USER);
    assertThatThrownBy(() -> auth.changePassword(user.getId(), "wrong", "new-password"))
        .isInstanceOfSatisfying(BusinessException.class, error -> assertThat(error.getCode())
            .isEqualTo(ErrorCode.AUTH_INVALID_CREDENTIALS.getCode()));
    auth.changePassword(user.getId(), "secret-password", "new-password");
    assertThat(auth.authenticate("Alice", "new-password").isMustChangePassword()).isFalse();
    assertThatThrownBy(() -> auth.authenticate("Alice", "secret-password"))
        .isInstanceOf(BusinessException.class);
  }

  @Test
  @DisplayName("拒绝空用户名、空密码以及超过 BCrypt 72 字节限制的密码")
  void rejectsInvalidInput() {
    assertThatThrownBy(() -> createUser(" ", UserRole.USER)).isInstanceOf(BusinessException.class);
    assertThatThrownBy(() -> accounts.createUser(
        new CreateAdminUserCommand("Alice", "", "Alice", UserRole.USER)))
        .isInstanceOf(BusinessException.class);
    assertThatThrownBy(() -> accounts.createUser(
        new CreateAdminUserCommand("Alice", "密".repeat(25), "Alice", UserRole.USER)))
        .isInstanceOf(BusinessException.class);
    assertThat(users.count()).isZero();
  }

  @Test
  @DisplayName("登录和旧密码校验拒绝超过 72 字节的输入而非截断或抛出底层异常")
  void rejectsOverlongCredentials() {
    String password = "a".repeat(72);
    var user = accounts.createUser(new CreateAdminUserCommand("Alice", password, "Alice", UserRole.USER));
    assertThatThrownBy(() -> auth.authenticate("Alice", password + "x"))
        .isInstanceOfSatisfying(BusinessException.class, error -> assertThat(error.getCode())
            .isEqualTo(ErrorCode.AUTH_INVALID_CREDENTIALS.getCode()));
    assertThatThrownBy(() -> auth.changePassword(user.getId(), password + "x", "new-password"))
        .isInstanceOfSatisfying(BusinessException.class, error -> assertThat(error.getCode())
            .isEqualTo(ErrorCode.AUTH_INVALID_CREDENTIALS.getCode()));
  }

  @Test
  @DisplayName("不存在用户的管理和改密操作返回独立错误码")
  void rejectsMissingUser() {
    var missing = UUID.randomUUID();
    assertThatThrownBy(() -> accounts.disable(missing))
        .isInstanceOfSatisfying(BusinessException.class, error -> assertThat(error.getCode())
            .isEqualTo(ErrorCode.AUTH_USER_NOT_FOUND.getCode()));
    assertThatThrownBy(() -> accounts.enable(missing)).isInstanceOf(BusinessException.class);
    assertThatThrownBy(() -> auth.changePassword(missing, "old", "new"))
        .isInstanceOf(BusinessException.class);
  }
}
