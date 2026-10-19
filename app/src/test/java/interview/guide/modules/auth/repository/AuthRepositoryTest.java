package interview.guide.modules.auth.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import interview.guide.modules.auth.model.RegistrationInviteEntity;
import interview.guide.modules.auth.model.RegistrationInviteStatus;
import interview.guide.modules.auth.model.UserEntity;
import interview.guide.modules.auth.model.UserRole;
import interview.guide.modules.auth.model.UserStatus;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.JdbcTemplateAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.transaction.autoconfigure.TransactionAutoConfiguration;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(classes = AuthRepositoryTest.JpaTestApplication.class, properties = {
    "spring.datasource.url=jdbc:h2:mem:auth-repository;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    "spring.datasource.driver-class-name=org.h2.Driver",
    "spring.datasource.username=sa",
    "spring.datasource.password=",
    "spring.jpa.hibernate.ddl-auto=none"
})
@Sql(scripts = "classpath:db/migration/V2__create_identity_tables.sql",
    executionPhase = Sql.ExecutionPhase.BEFORE_TEST_CLASS)
@DisplayName("用户与注册邀请码持久化")
class AuthRepositoryTest {
  private static final Instant NOW = Instant.parse("2026-10-19T08:00:00Z");

  @Configuration(proxyBeanMethods = false)
  @EntityScan(basePackageClasses = UserEntity.class)
  @EnableJpaRepositories(basePackageClasses = UserRepository.class)
  @ImportAutoConfiguration({DataSourceAutoConfiguration.class, HibernateJpaAutoConfiguration.class,
      JdbcTemplateAutoConfiguration.class, TransactionAutoConfiguration.class})
  static class JpaTestApplication {
  }

  @Autowired
  private UserRepository userRepository;
  @Autowired
  private RegistrationInviteRepository inviteRepository;
  @Autowired
  private TransactionTemplate transactions;

  private UserEntity admin;
  private UserEntity firstUser;
  private UserEntity secondUser;

  @BeforeEach
  void setUp() {
    inviteRepository.deleteAllInBatch();
    userRepository.deleteAllInBatch();
    admin = userRepository.saveAndFlush(user("Admin", "admin", UserRole.ADMIN));
    firstUser = userRepository.saveAndFlush(user("Alice", "alice", UserRole.USER));
    secondUser = userRepository.saveAndFlush(user("Bob", "bob", UserRole.USER));
  }

  @Test
  @DisplayName("UUID 用户保留显示用户名并按独立的归一化登录名查询")
  void findsUserByNormalizedUsername() {
    assertThat(firstUser.getId()).isNotNull();
    assertThat(userRepository.findByNormalizedUsername("alice")).hasValueSatisfying(saved -> {
      assertThat(saved.getUsername()).isEqualTo("Alice");
      assertThat(saved.getDisplayName()).isEqualTo("Alice display");
      assertThat(saved.getPasswordHash()).isEqualTo("password-hash");
      assertThat(saved.getRole()).isEqualTo(UserRole.USER);
      assertThat(saved.getStatus()).isEqualTo(UserStatus.ACTIVE);
      assertThat(saved.isMustChangePassword()).isTrue();
      assertThat(saved.getLastLoginAt()).isNull();
    });
    assertThat(userRepository.findByNormalizedUsername("missing")).isEmpty();
  }

  @Test
  @DisplayName("数据库拒绝重复归一化用户名")
  void rejectsDuplicateNormalizedUsername() {
    assertThatThrownBy(() -> userRepository.saveAndFlush(user("ALICE", "alice", UserRole.USER)))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  @DisplayName("数据库拒绝重复邀请码哈希")
  void rejectsDuplicateInviteHash() {
    inviteRepository.saveAndFlush(invite("same-hash", RegistrationInviteStatus.ACTIVE,
        NOW.plusSeconds(60)));
    assertThatThrownBy(() -> inviteRepository.saveAndFlush(invite("same-hash",
        RegistrationInviteStatus.ACTIVE, NOW.plusSeconds(120))))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  @DisplayName("有效邀请码仅消费一次并记录使用人、时间和版本")
  void consumesActiveInviteExactlyOnce() {
    var saved = inviteRepository.saveAndFlush(invite("active-hash",
        RegistrationInviteStatus.ACTIVE, NOW.plusSeconds(60)));

    assertThat(consume("active-hash", firstUser.getId(), NOW)).isOne();
    assertThat(consume("active-hash", secondUser.getId(), NOW.plusSeconds(1))).isZero();
    assertThat(inviteRepository.findById(saved.getId())).hasValueSatisfying(used -> {
      assertThat(used.getStatus()).isEqualTo(RegistrationInviteStatus.USED);
      assertThat(used.getUsedBy().getId()).isEqualTo(firstUser.getId());
      assertThat(used.getUsedAt()).isEqualTo(NOW);
      assertThat(used.getUpdatedAt()).isEqualTo(NOW);
      assertThat(used.getVersion()).isEqualTo(saved.getVersion() + 1);
    });
    assertThat(consume("missing-hash", firstUser.getId(), NOW)).isZero();
  }

  @ParameterizedTest
  @EnumSource(value = RegistrationInviteStatus.class, names = {"USED", "REVOKED", "EXPIRED"})
  @DisplayName("已使用、撤销和过期状态的邀请码不能消费")
  void rejectsInactiveInvite(RegistrationInviteStatus status) {
    var saved = invite("inactive-hash", status, NOW.plusSeconds(60));
    if (status == RegistrationInviteStatus.USED) {
      saved.setUsedBy(firstUser);
      saved.setUsedAt(NOW.minusSeconds(1));
    }
    inviteRepository.saveAndFlush(saved);

    assertThat(consume("inactive-hash", secondUser.getId(), NOW)).isZero();
    assertThat(inviteRepository.findById(saved.getId()).orElseThrow().getStatus()).isEqualTo(status);
  }

  @Test
  @DisplayName("过期时间早于或等于使用时间的 ACTIVE 邀请码不能消费")
  void rejectsExpiredActiveInviteAndExactExpiryBoundary() {
    inviteRepository.saveAndFlush(invite("expired-hash", RegistrationInviteStatus.ACTIVE,
        NOW.minusSeconds(1)));
    inviteRepository.saveAndFlush(invite("boundary-hash", RegistrationInviteStatus.ACTIVE, NOW));

    assertThat(consume("expired-hash", firstUser.getId(), NOW)).isZero();
    assertThat(consume("boundary-hash", firstUser.getId(), NOW)).isZero();
  }

  @Test
  @DisplayName("两个独立事务同时消费邀请码时只有一个成功")
  void concurrentConsumptionHasExactlyOneWinner() throws Exception {
    var saved = inviteRepository.saveAndFlush(invite("race-hash",
        RegistrationInviteStatus.ACTIVE, NOW.plusSeconds(60)));
    var ready = new CountDownLatch(2);
    var start = new CountDownLatch(1);
    try (var executor = new ThreadPoolExecutor(2, 2, 0, TimeUnit.MILLISECONDS,
        new LinkedBlockingQueue<Runnable>())) {
      var first = executor.submit(() -> compete(ready, start, firstUser.getId()));
      var second = executor.submit(() -> compete(ready, start, secondUser.getId()));
      assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
      start.countDown();
      int firstResult = first.get(10, TimeUnit.SECONDS);
      int secondResult = second.get(10, TimeUnit.SECONDS);

      assertThat(new int[] {firstResult, secondResult}).containsExactlyInAnyOrder(1, 0);
      var used = inviteRepository.findById(saved.getId()).orElseThrow();
      assertThat(used.getStatus()).isEqualTo(RegistrationInviteStatus.USED);
      assertThat(used.getUsedBy().getId())
          .isEqualTo(firstResult == 1 ? firstUser.getId() : secondUser.getId());
      assertThat(used.getUsedAt()).isEqualTo(NOW);
      assertThat(used.getVersion()).isEqualTo(saved.getVersion() + 1);
    } finally {
      start.countDown();
    }
  }

  @Test
  @DisplayName("消费服从调用方事务回滚，回滚后仍可消费")
  void rollsBackConsumptionWithCallerTransaction() {
    var saved = inviteRepository.saveAndFlush(invite("rollback-hash",
        RegistrationInviteStatus.ACTIVE, NOW.plusSeconds(60)));
    transactions.executeWithoutResult(transaction -> {
      assertThat(inviteRepository.consumeActiveInvite("rollback-hash", firstUser.getId(), NOW))
          .isOne();
      transaction.setRollbackOnly();
    });

    assertThat(inviteRepository.findById(saved.getId()).orElseThrow().getStatus())
        .isEqualTo(RegistrationInviteStatus.ACTIVE);
    assertThat(consume("rollback-hash", secondUser.getId(), NOW)).isOne();
  }

  @Test
  @DisplayName("用户版本阻止陈旧写入覆盖已更新的账号状态")
  void rejectsStaleUserUpdate() {
    var stale = userRepository.findById(firstUser.getId()).orElseThrow();
    firstUser.setStatus(UserStatus.DISABLED);
    userRepository.saveAndFlush(firstUser);
    stale.setDisplayName("stale name");

    assertThatThrownBy(() -> userRepository.saveAndFlush(stale))
        .isInstanceOf(ObjectOptimisticLockingFailureException.class);
  }

  @Test
  @DisplayName("消费递增邀请码版本以阻止陈旧撤销覆盖消费结果")
  void rejectsStaleInviteUpdateAfterConsumption() {
    var saved = inviteRepository.saveAndFlush(invite("stale-hash",
        RegistrationInviteStatus.ACTIVE, NOW.plusSeconds(60)));
    assertThat(consume("stale-hash", firstUser.getId(), NOW)).isOne();
    saved.setStatus(RegistrationInviteStatus.REVOKED);

    assertThatThrownBy(() -> inviteRepository.saveAndFlush(saved))
        .isInstanceOf(ObjectOptimisticLockingFailureException.class);
  }

  private int compete(CountDownLatch ready, CountDownLatch start, UUID userId) {
    return transactions.execute(transaction -> {
      ready.countDown();
      try {
        if (!start.await(10, TimeUnit.SECONDS)) {
          throw new IllegalStateException("Concurrent consumption did not start in time");
        }
      } catch (InterruptedException exception) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("Concurrent consumption was interrupted", exception);
      }
      return inviteRepository.consumeActiveInvite("race-hash", userId, NOW);
    });
  }

  private int consume(String inviteHash, UUID userId, Instant now) {
    return transactions.execute(transaction ->
        inviteRepository.consumeActiveInvite(inviteHash, userId, now));
  }

  private UserEntity user(String username, String normalized, UserRole role) {
    return UserEntity.builder().username(username).normalizedUsername(normalized)
        .displayName(username + " display").passwordHash("password-hash").role(role)
        .status(UserStatus.ACTIVE).mustChangePassword(true).createdAt(NOW).updatedAt(NOW).build();
  }

  private RegistrationInviteEntity invite(String hash, RegistrationInviteStatus status,
      Instant expiresAt) {
    return RegistrationInviteEntity.builder().inviteHash(hash).status(status)
        .expiresAt(expiresAt).createdBy(admin).createdAt(NOW.minusSeconds(60))
        .updatedAt(NOW.minusSeconds(60)).build();
  }
}
