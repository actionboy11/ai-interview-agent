package interview.guide.modules.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import interview.guide.common.exception.BusinessException;
import interview.guide.modules.auth.config.AuthBootstrapProperties;
import interview.guide.modules.auth.model.UserRole;
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
