package interview.guide.modules.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.auth.config.AuthBootstrapProperties;
import interview.guide.modules.auth.model.UserRole;
import interview.guide.modules.auth.repository.UserRepository;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

@DisplayName("初始管理员引导")
class InitialAdminBootstrapTest extends AuthServiceTestSupport {
  @Test
  @DisplayName("并发启动均先读到管理员不存在时仍能完成且仅创建一个管理员")
  void concurrentBootstrapsRecoverCreateCollision() throws Exception {
    var initialReads = new CountDownLatch(2);
    var releaseReads = new CountDownLatch(1);
    var lookupCount = new AtomicInteger();
    var coordinatedUsers = mock(UserRepository.class, delegatesTo(users));
    doAnswer(invocation -> {
      var result = users.findByNormalizedUsername("admin");
      if (lookupCount.incrementAndGet() <= 2) {
        assertThat(result).isEmpty();
        initialReads.countDown();
        if (!releaseReads.await(10, TimeUnit.SECONDS)) {
          throw new IllegalStateException("Bootstrap reads were not released");
        }
      }
      return result;
    }).when(coordinatedUsers).findByNormalizedUsername("admin");
    var first = new InitialAdminBootstrap(bootstrapProperties(), coordinatedUsers, accounts,
        new UsernameNormalizer());
    var second = new InitialAdminBootstrap(bootstrapProperties(), coordinatedUsers, accounts,
        new UsernameNormalizer());
    try (var executor = new ThreadPoolExecutor(2, 2, 0, TimeUnit.MILLISECONDS,
        new LinkedBlockingQueue<Runnable>())) {
      var firstResult = executor.submit(() -> first.run(new DefaultApplicationArguments()));
      var secondResult = executor.submit(() -> second.run(new DefaultApplicationArguments()));
      assertThat(initialReads.await(10, TimeUnit.SECONDS)).isTrue();
      releaseReads.countDown();
      firstResult.get(10, TimeUnit.SECONDS);
      secondResult.get(10, TimeUnit.SECONDS);
      assertThat(users.count()).isOne();
      assertThat(auth.authenticate("ADMIN", "initial-secret").getRole()).isEqualTo(UserRole.ADMIN);
    } finally {
      releaseReads.countDown();
    }
  }

  @Test
  @DisplayName("并发出现同名普通用户时引导失败且不得提升角色")
  void concurrentOrdinaryUserIsNotAccepted() throws Exception {
    var initialRead = new CountDownLatch(1);
    var releaseRead = new CountDownLatch(1);
    var lookupCount = new AtomicInteger();
    var coordinatedUsers = mock(UserRepository.class, delegatesTo(users));
    doAnswer(invocation -> {
      var result = users.findByNormalizedUsername("admin");
      if (lookupCount.incrementAndGet() == 1) {
        assertThat(result).isEmpty();
        initialRead.countDown();
        if (!releaseRead.await(10, TimeUnit.SECONDS)) {
          throw new IllegalStateException("Bootstrap read was not released");
        }
      }
      return result;
    }).when(coordinatedUsers).findByNormalizedUsername("admin");
    var bootstrap = new InitialAdminBootstrap(bootstrapProperties(), coordinatedUsers, accounts,
        new UsernameNormalizer());
    try (var executor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
        new LinkedBlockingQueue<Runnable>())) {
      var result = executor.submit(() -> {
        assertThatThrownBy(() -> bootstrap.run(new DefaultApplicationArguments()))
            .isInstanceOfSatisfying(BusinessException.class, error -> assertThat(error.getCode())
                .isEqualTo(ErrorCode.AUTH_BOOTSTRAP_INVALID.getCode()));
      });
      assertThat(initialRead.await(10, TimeUnit.SECONDS)).isTrue();
      var ordinaryUser = createUser("Admin", UserRole.USER);
      releaseRead.countDown();
      result.get(10, TimeUnit.SECONDS);
      assertThat(users.count()).isOne();
      assertThat(users.findById(ordinaryUser.getId()).orElseThrow().getRole()).isEqualTo(UserRole.USER);
    } finally {
      releaseRead.countDown();
    }
  }

  private AuthBootstrapProperties bootstrapProperties() {
    var properties = new AuthBootstrapProperties();
    properties.setUsername("ADMIN");
    properties.setPassword("initial-secret");
    properties.setDisplayName("管理员");
    return properties;
  }

  @Test
  @DisplayName("从配置创建管理员且重复启动不改写已有密码和状态")
  void bootstrapsIdempotently() throws Exception {
    var properties = new AuthBootstrapProperties();
    properties.setUsername("Ａdmin");
    properties.setPassword("initial-secret");
    properties.setDisplayName("管理员");
    var bootstrap = new InitialAdminBootstrap(properties, users, accounts, new UsernameNormalizer());
    bootstrap.run(new DefaultApplicationArguments());
    var admin = auth.authenticate("ADMIN", "initial-secret");
    assertThat(admin.getRole()).isEqualTo(UserRole.ADMIN);
    assertThat(admin.getDisplayName()).isEqualTo("管理员");
    assertThat(admin.isMustChangePassword()).isTrue();
    auth.changePassword(admin.getId(), "initial-secret", "changed-password");
    bootstrap.run(new DefaultApplicationArguments());
    assertThat(users.count()).isOne();
    assertThat(auth.authenticate("admin", "changed-password").isMustChangePassword()).isFalse();
  }

  @Test
  @DisplayName("未配置凭据时不创建默认管理员，部分配置报错")
  void hasNoDefaultPassword() throws Exception {
    var environment = new StandardEnvironment();
    for (var source : new YamlPropertySourceLoader().load("application",
        new ClassPathResource("application.yml"))) {
      environment.getPropertySources().addLast(source);
    }
    var defaults = Binder.get(environment).bind("app.auth.bootstrap", AuthBootstrapProperties.class)
        .orElseGet(AuthBootstrapProperties::new);
    assertThat(defaults.getPassword()).isNullOrEmpty();
    new InitialAdminBootstrap(defaults, users, accounts, new UsernameNormalizer())
        .run(new DefaultApplicationArguments());
    assertThat(users.count()).isZero();
    defaults.setUsername("admin");
    assertThatThrownBy(() -> new InitialAdminBootstrap(defaults, users, accounts,
        new UsernameNormalizer()).run(new DefaultApplicationArguments()))
        .isInstanceOf(BusinessException.class);
  }

  @Test
  @DisplayName("同名普通用户不会被引导流程提升为管理员")
  void doesNotPromoteExistingUser() {
    var existing = createUser("Admin", UserRole.USER);
    var properties = new AuthBootstrapProperties();
    properties.setUsername("ADMIN");
    properties.setPassword("initial-secret");
    properties.setDisplayName("管理员");
    assertThatThrownBy(() -> new InitialAdminBootstrap(properties, users, accounts,
        new UsernameNormalizer()).run(new DefaultApplicationArguments()))
        .isInstanceOf(BusinessException.class);
    assertThat(users.findById(existing.getId()).orElseThrow().getRole()).isEqualTo(UserRole.USER);
  }
}
