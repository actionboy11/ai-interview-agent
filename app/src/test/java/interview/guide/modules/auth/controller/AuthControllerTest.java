package interview.guide.modules.auth.controller;

import interview.guide.modules.auth.model.UserRole;
import interview.guide.modules.auth.security.AuthWebTestSupport;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@DisplayName("认证接口与实际 Redis 会话")
class AuthControllerTest extends AuthWebTestSupport {
  @Test
  @DisplayName("登录持久化安全上下文与用户索引，当前用户响应不泄漏密码")
  void loginAndMe() throws Exception {
    var user = createUser("alice", UserRole.USER);
    var browser = login("alice");
    var stored = sessions.findById(browser.sessionId());
    assertThat(stored).isNotNull();
    assertThat(stored.getAttributeNames()).contains("SPRING_SECURITY_CONTEXT");
    assertThat(stored.getMaxInactiveInterval()).isEqualTo(Duration.ofMinutes(5));
    assertThat(browser.session().isHttpOnly()).isTrue();
    assertThat(browser.session().getAttribute("SameSite")).isEqualTo("Lax");
    assertThat(sessions.findByPrincipalName(user.getId().toString())).containsKey(stored.getId());
    mvc.perform(browser.read(get("/api/auth/me")))
        .andExpect(status().isOk()).andExpect(jsonPath("$.data.id").value(user.getId().toString()))
        .andExpect(jsonPath("$.data.role").value("USER"))
        .andExpect(jsonPath("$.data.mustChangePassword").value(true))
        .andExpect(jsonPath("$.data.passwordHash").doesNotExist());
  }

  @Test
  @DisplayName("重复登录更换会话 ID，旧 ID 无法取回认证")
  void rotatesSessionOnLogin() throws Exception {
    createUser("alice", UserRole.USER);
    var browser = login("alice");
    var result = mvc.perform(browser.mutate(post("/api/auth/login"))
        .contentType("application/json")
        .content("{\"username\":\"alice\",\"password\":\"secret-password\"}"))
        .andExpect(jsonPath("$.code").value(200)).andReturn();
    assertThat(result.getResponse().getCookie("SESSION").getValue()
        .equals(browser.session().getValue())).isFalse();
    assertThat(sessions.findById(browser.sessionId())).isNull();
    mvc.perform(browser.read(get("/api/auth/me"))).andExpect(jsonPath("$.code").value(401));
  }

  @Test
  @DisplayName("错误凭证返回业务错误且不创建登录会话")
  void invalidLogin() throws Exception {
    var browser = bootstrap();
    mvc.perform(browser.mutate(post("/api/auth/login")).contentType("application/json")
        .content("{\"username\":\"missing\",\"password\":\"bad\"}"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(12001));
  }

  @Test
  @DisplayName("密码修改仅取当前身份，退出删除真实会话")
  void passwordAndLogout() throws Exception {
    var user = createUser("alice", UserRole.USER);
    var browser = login("alice");
    mvc.perform(browser.mutate(post("/api/auth/change-password")).contentType("application/json")
        .content("{\"currentPassword\":\"secret-password\",\"newPassword\":\"new-secret\",\"userId\":\""
            + UUID.randomUUID() + "\"}"))
        .andExpect(jsonPath("$.code").value(200));
    assertThat(users.findById(user.getId()).orElseThrow().isMustChangePassword()).isFalse();
    mvc.perform(browser.read(get("/api/auth/me")))
        .andExpect(jsonPath("$.data.mustChangePassword").value(false));
    mvc.perform(browser.mutate(post("/api/auth/logout")))
        .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(200));
    assertThat(sessions.findById(browser.sessionId())).isNull();
    mvc.perform(browser.read(get("/api/auth/me"))).andExpect(jsonPath("$.code").value(401));
  }

  @Test
  @DisplayName("管理员创建账号签发邀请码，匿名仅凭邀请码注册普通账号")
  void administrationAndRegistration() throws Exception {
    createUser("admin", UserRole.ADMIN);
    var admin = login("admin");
    mvc.perform(admin.mutate(post("/api/admin/users")).contentType("application/json")
        .content("{\"username\":\"managed\",\"password\":\"temporary\",\"displayName\":\"Managed\",\"role\":\"USER\"}"))
        .andExpect(jsonPath("$.code").value(200))
        .andExpect(jsonPath("$.data.mustChangePassword").value(true))
        .andExpect(jsonPath("$.data.passwordHash").doesNotExist());
    var issued = mvc.perform(admin.mutate(post("/api/admin/invites")).contentType("application/json")
        .content("{\"ttlSeconds\":3600}"))
        .andExpect(jsonPath("$.code").value(200)).andReturn();
    var anonymous = bootstrap();
    String body = "{\"inviteCode\":\"" + data(issued, "rawCode")
        + "\",\"username\":\"invited\",\"password\":\"invited-secret\",\"displayName\":\"Invited\",\"role\":\"ADMIN\"}";
    mvc.perform(anonymous.mutate(post("/api/auth/register-by-invite"))
        .contentType("application/json").content(body))
        .andExpect(jsonPath("$.code").value(200)).andExpect(jsonPath("$.data.role").value("USER"));
    mvc.perform(anonymous.mutate(post("/api/auth/register-by-invite"))
        .contentType("application/json").content(body.replace("invited\"", "another\"")))
        .andExpect(jsonPath("$.code").value(12005));
  }
}
