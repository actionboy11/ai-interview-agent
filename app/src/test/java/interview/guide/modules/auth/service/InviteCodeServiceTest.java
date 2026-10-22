package interview.guide.modules.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.auth.model.RegistrationInviteStatus;
import interview.guide.modules.auth.model.UserRole;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

@DisplayName("邀请码服务")
class InviteCodeServiceTest extends AuthServiceTestSupport {
  @Test
  @DisplayName("签发独立的 256 位随机邀请码，数据库仅保存 SHA256")
  void issuesRandomHashOnlyInvite() throws Exception {
    var admin = createUser("Admin", UserRole.ADMIN);
    var first = inviteCodes.issue(Duration.ofHours(1), admin.getId());
    var second = inviteCodes.issue(Duration.ofHours(1), admin.getId());
    assertThat(Base64.getUrlDecoder().decode(first.rawCode())).hasSize(32);
    assertThat(first.rawCode()).isNotEqualTo(second.rawCode());
    var saved = invites.findById(first.id()).orElseThrow();
    assertThat(saved.getInviteHash()).isEqualTo(HexFormat.of().formatHex(
        MessageDigest.getInstance("SHA-256").digest(first.rawCode().getBytes(StandardCharsets.UTF_8))));
    assertThat(saved.getInviteHash()).hasSize(64).doesNotContain(first.rawCode());
    assertThat(saved.getCreatedBy().getId()).isEqualTo(admin.getId());
    assertThat(saved.getExpiresAt()).isEqualTo(first.expiresAt());
    assertThat(first.toString()).doesNotContain(first.rawCode());
  }

  @Test
  @DisplayName("邀请码注册普通用户并原子记录消费信息")
  void registersUserAndConsumesInvite() {
    var admin = createUser("Admin", UserRole.ADMIN);
    var issued = inviteCodes.issue(Duration.ofHours(1), admin.getId());
    var registered = inviteCodes.register(issued.rawCode(), "Ａlice", "my-password", "Alice");
    assertThat(auth.authenticate("alice", "my-password").getId()).isEqualTo(registered.getId());
    assertThat(registered.getRole()).isEqualTo(UserRole.USER);
    assertThat(registered.isMustChangePassword()).isFalse();
    var saved = invites.findById(issued.id()).orElseThrow();
    assertThat(saved.getStatus()).isEqualTo(RegistrationInviteStatus.USED);
    assertThat(saved.getUsedBy().getId()).isEqualTo(registered.getId());
    assertThat(saved.getUsedAt()).isNotNull();
    assertThatThrownBy(() -> inviteCodes.register(issued.rawCode(), "Bob", "password", "Bob"))
        .isInstanceOfSatisfying(BusinessException.class, error -> assertThat(error.getCode())
            .isEqualTo(ErrorCode.AUTH_INVITE_INVALID.getCode()));
    assertThat(users.count()).isEqualTo(2);
  }

  @ParameterizedTest
  @EnumSource(value = RegistrationInviteStatus.class, names = {"ACTIVE", "REVOKED", "EXPIRED"})
  @DisplayName("过期或撤销邀请码拒绝注册并回滚已创建的用户")
  void rejectsUnusableInviteAndRollsBackUser(RegistrationInviteStatus status) {
    var admin = createUser("Admin", UserRole.ADMIN);
    var issued = inviteCodes.issue(Duration.ofHours(1), admin.getId());
    var saved = invites.findById(issued.id()).orElseThrow();
    saved.setStatus(status);
    if (status == RegistrationInviteStatus.ACTIVE) {
      saved.setExpiresAt(Instant.now().minusSeconds(1));
    }
    invites.saveAndFlush(saved);
    assertThatThrownBy(() -> inviteCodes.register(issued.rawCode(), "Alice", "password", "Alice"))
        .isInstanceOfSatisfying(BusinessException.class, error -> assertThat(error.getCode())
            .isEqualTo(ErrorCode.AUTH_INVITE_INVALID.getCode()));
    assertThat(users.count()).isOne();
    assertThat(invites.findById(issued.id()).orElseThrow().getStatus()).isEqualTo(status);
  }

  @Test
  @DisplayName("重复用户名不会消耗邀请码，可使用其他用户名重试")
  void duplicateUsernameDoesNotConsumeInvite() {
    var admin = createUser("Admin", UserRole.ADMIN);
    var issued = inviteCodes.issue(Duration.ofHours(1), admin.getId());
    assertThatThrownBy(() -> inviteCodes.register(issued.rawCode(), "ADMIN", "password", "Alice"))
        .isInstanceOfSatisfying(BusinessException.class, error -> assertThat(error.getCode())
            .isEqualTo(ErrorCode.AUTH_USERNAME_TAKEN.getCode()));
    assertThat(invites.findById(issued.id()).orElseThrow().getStatus())
        .isEqualTo(RegistrationInviteStatus.ACTIVE);
    assertThat(inviteCodes.register(issued.rawCode(), "Alice", "password", "Alice").getId())
        .isNotNull();
  }

  @Test
  @DisplayName("管理员可查询安全元数据并撤销未使用邀请码，撤销后不能注册")
  void listsAndRevokesActiveInvite() {
    var admin = createUser("Admin", UserRole.ADMIN);
    var issued = inviteCodes.issue(Duration.ofHours(1), admin.getId());
    var listing = inviteCodes.list(0, 20);
    assertThat(listing.getContent()).hasSize(1);
    assertThat(listing.getContent().getFirst().id()).isEqualTo(issued.id());
    assertThat(listing.getContent().getFirst().toString()).doesNotContain(issued.rawCode());
    assertThat(listing.getContent().getFirst().toString()).doesNotContain(
        invites.findById(issued.id()).orElseThrow().getInviteHash());
    inviteCodes.revoke(issued.id());
    assertThat(invites.findById(issued.id()).orElseThrow().getStatus())
        .isEqualTo(RegistrationInviteStatus.REVOKED);
    assertThatThrownBy(() -> inviteCodes.register(issued.rawCode(), "Alice", "password", "Alice"))
        .isInstanceOfSatisfying(BusinessException.class, error -> assertThat(error.getCode())
            .isEqualTo(ErrorCode.AUTH_INVITE_INVALID.getCode()));
  }

  @Test
  @DisplayName("两个并发注册事务仅一个成功且失败用户不会残留")
  void concurrentRegistrationHasOneWinner() throws Exception {
    var admin = createUser("Admin", UserRole.ADMIN);
    var issued = inviteCodes.issue(Duration.ofHours(1), admin.getId());
    var ready = new CountDownLatch(2);
    var start = new CountDownLatch(1);
    try (var executor = new ThreadPoolExecutor(2, 2, 0, TimeUnit.MILLISECONDS,
        new LinkedBlockingQueue<Runnable>())) {
      var first = executor.submit(() -> compete(ready, start, issued.rawCode(), "Alice"));
      var second = executor.submit(() -> compete(ready, start, issued.rawCode(), "Bob"));
      assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
      start.countDown();
      assertThat(new boolean[] {first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)})
          .containsExactlyInAnyOrder(true, false);
      assertThat(users.count()).isEqualTo(2);
      assertThat(invites.findById(issued.id()).orElseThrow().getStatus())
          .isEqualTo(RegistrationInviteStatus.USED);
    } finally {
      start.countDown();
    }
  }

  @Test
  @DisplayName("撤销与兑换竞争只能产生已撤销或已使用其中一种持久化结果")
  void revokeAndRedeemHaveExactlyOneOutcome() throws Exception {
    var admin = createUser("Admin", UserRole.ADMIN);
    var issued = inviteCodes.issue(Duration.ofHours(1), admin.getId());
    var ready = new CountDownLatch(2);
    var start = new CountDownLatch(1);
    try (var executor = new ThreadPoolExecutor(2, 2, 0, TimeUnit.MILLISECONDS,
        new LinkedBlockingQueue<Runnable>())) {
      var revoke = executor.submit(() -> {
        ready.countDown();
        start.await();
        try {
          inviteCodes.revoke(issued.id());
          return true;
        } catch (BusinessException error) {
          assertThat(error.getCode()).isEqualTo(ErrorCode.AUTH_INVITE_INVALID.getCode());
          return false;
        }
      });
      var redeem = executor.submit(() -> compete(ready, start, issued.rawCode(), "Alice"));
      assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
      start.countDown();
      boolean revoked = revoke.get(10, TimeUnit.SECONDS);
      boolean redeemed = redeem.get(10, TimeUnit.SECONDS);
      assertThat(revoked).isNotEqualTo(redeemed);
      assertThat(invites.findById(issued.id()).orElseThrow().getStatus())
          .isEqualTo(revoked ? RegistrationInviteStatus.REVOKED : RegistrationInviteStatus.USED);
      assertThat(users.count()).isEqualTo(redeemed ? 2 : 1);
    } finally {
      start.countDown();
    }
  }

  @Test
  @DisplayName("仅启用的管理员能签发正数有效期的邀请码")
  void validatesIssuerAndTtl() {
    var user = createUser("User", UserRole.USER);
    assertThatThrownBy(() -> inviteCodes.issue(Duration.ofHours(1), user.getId()))
        .isInstanceOfSatisfying(BusinessException.class, error -> assertThat(error.getCode())
            .isEqualTo(ErrorCode.AUTH_ADMIN_REQUIRED.getCode()));
    var admin = createUser("Admin", UserRole.ADMIN);
    assertThatThrownBy(() -> inviteCodes.issue(Duration.ZERO, admin.getId()))
        .isInstanceOf(BusinessException.class);
    accounts.disable(admin.getId());
    assertThatThrownBy(() -> inviteCodes.issue(Duration.ofHours(1), admin.getId()))
        .isInstanceOf(BusinessException.class);
    assertThat(invites.count()).isZero();
    assertThatThrownBy(() -> inviteCodes.register("missing", "Alice", "password", "Alice"))
        .isInstanceOf(BusinessException.class);
    assertThat(users.count()).isEqualTo(2);
  }

  private boolean compete(CountDownLatch ready, CountDownLatch start, String code, String name)
      throws InterruptedException {
    ready.countDown();
    if (!start.await(10, TimeUnit.SECONDS)) {
      throw new IllegalStateException("Registration did not start in time");
    }
    try {
      inviteCodes.register(code, name, "password", name);
      return true;
    } catch (BusinessException error) {
      assertThat(error.getCode()).isEqualTo(ErrorCode.AUTH_INVITE_INVALID.getCode());
      return false;
    }
  }
}
