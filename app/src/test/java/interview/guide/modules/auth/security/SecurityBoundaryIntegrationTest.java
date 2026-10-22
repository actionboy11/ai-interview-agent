package interview.guide.modules.auth.security;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.common.result.Result;
import interview.guide.modules.auth.model.UserRole;
import interview.guide.modules.auth.model.UserStatus;
import interview.guide.modules.auth.service.CreateAdminUserCommand;
import interview.guide.modules.auth.service.InviteCodeService;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@DisplayName("默认拒绝、CSRF、CORS 与禁用安全边界")
public class SecurityBoundaryIntegrationTest extends AuthWebTestSupport {
  @Autowired CurrentUserProvider currentUsers;
  @Autowired InviteCodeService inviteCodes;

  @Test
  @DisplayName("应用认证机制使 Boot 默认账号自动配置退出")
  void doesNotCreateFallbackUser() {
    assertThat(context.getBeansOfType(UserDetailsService.class)).isEmpty();
  }

  @Test
  @DisplayName("匿名仅允许精确认证引导路径，任意业务与伪造认证路径均拒绝")
  void anonymousBoundary() throws Exception {
    for (String path : new String[]{"/api/auth/me", "/api/admin/users", "/api/resumes",
        "/api/auth/future", "/api/auth/login/extra", "/v3/api-docs", "/actuator/info"}) {
      mvc.perform(get(path)).andExpect(status().isOk()).andExpect(jsonPath("$.code").value(401));
    }
    // Missing CSRF on an anonymous mutation still follows the public HTTP-200 error contract.
    mvc.perform(post("/api/auth/login")).andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(401));
    mvc.perform(get("/actuator/health")).andExpect(jsonPath("$.code").value(200));
    bootstrap();
  }

  @Test
  @DisplayName("普通用户不能调用管理员接口，管理员仍使用本人身份执行内容所有权检查")
  void adminIsNotContentOwner() throws Exception {
    var owner = createUser("owner", UserRole.USER);
    createUser("admin", UserRole.ADMIN);
    var user = login("owner");
    mvc.perform(user.mutate(post("/api/admin/invites")).contentType("application/json")
        .content("{\"ttlSeconds\":3600}"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(403));
    var admin = login("admin");
    mvc.perform(admin.read(get("/api/owner-probe/" + owner.getId())))
        .andExpect(jsonPath("$.code").value(404));
    mvc.perform(user.read(get("/api/owner-probe/" + owner.getId())))
        .andExpect(jsonPath("$.code").value(200));
  }

  @Test
  @DisplayName("普通用户不能读取或更改全局模型和语音提供商设置")
  void providerSettingsRequireAdmin() throws Exception {
    createUser("provider-user", UserRole.USER);
    var user = login("provider-user");
    mvc.perform(user.read(get("/api/llm-provider/list")))
        .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(403));
    mvc.perform(user.mutate(post("/api/llm-provider/reload")))
        .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(403));
    mvc.perform(user.read(get("/api/llm-provider/voice/asr")))
        .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(403));
  }

  @Test
  @DisplayName("已登录变更要求可用 Cookie/header CSRF，允许显式来源携带凭证")
  void csrfAndCors() throws Exception {
    createUser("alice", UserRole.USER);
    var browser = login("alice");
    mvc.perform(browser.read(post("/api/auth/logout"))).andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(403));
    mvc.perform(options("/api/auth/login").header("Origin", "http://localhost:5173")
        .header("Access-Control-Request-Method", "POST")
        .header("Access-Control-Request-Headers", "content-type,x-xsrf-token"))
        .andExpect(status().isOk())
        .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"))
        .andExpect(header().string("Access-Control-Allow-Credentials", "true"));
    mvc.perform(options("/api/auth/login").header("Origin", "https://evil.example")
        .header("Access-Control-Request-Method", "POST"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(403))
        .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
  }

  @Test
  @DisplayName("数据库已禁用但会话尚未清除时，请求过滤器仍拒绝并删除旧会话")
  void rejectsPersistedDisabledAccountOnEveryRequest() throws Exception {
    var user = createUser("disabled", UserRole.USER);
    var browser = login("disabled");
    accounts.disable(user.getId());
    mvc.perform(browser.read(get("/api/auth/me")).header("Origin", "http://localhost:5173"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(12002))
        .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"));
    assertThat(sessions.findById(browser.sessionId())).isNull();
  }

  @Test
  @DisplayName("管理员禁用自身时当前请求不得重新保存已删除的会话")
  void selfDisableDoesNotResurrectSession() throws Exception {
    var user = createUser("admin", UserRole.ADMIN);
    var browser = login("admin");
    mvc.perform(browser.mutate(post("/api/admin/users/" + user.getId() + "/disable")))
        .andExpect(jsonPath("$.code").value(200));
    assertThat(sessions.findById(browser.sessionId())).isNull();
    assertThat(sessions.findByPrincipalName(user.getId().toString())).isEmpty();
  }

  @Test
  @DisplayName("禁用先持久化再删除所有索引会话，已加载身份也不能继续使用")
  void disableEvictsAllAndRejectsLoadedPrincipal() throws Exception {
    var target = createUser("target", UserRole.USER);
    createUser("admin", UserRole.ADMIN);
    var first = login("target");
    var second = login("target");
    SecurityContext loaded = sessions.findById(first.sessionId()).getAttribute("SPRING_SECURITY_CONTEXT");
    var admin = login("admin");
    mvc.perform(admin.mutate(post("/api/admin/users/" + target.getId() + "/disable")))
        .andExpect(jsonPath("$.code").value(200));
    assertThat(users.findById(target.getId()).orElseThrow().getStatus()).isEqualTo(UserStatus.DISABLED);
    assertThat(sessions.findByPrincipalName(target.getId().toString())).isEmpty();
    assertThat(sessions.findById(first.sessionId())).isNull();
    assertThat(sessions.findById(second.sessionId())).isNull();
    mvc.perform(first.read(get("/api/auth/me"))).andExpect(jsonPath("$.code").value(401));
    SecurityContextHolder.setContext(loaded);
    try {
      assertThatThrownBy(currentUsers::requireCurrentUser).isInstanceOfSatisfying(BusinessException.class,
          e -> assertThat(e.getCode()).isEqualTo(12002));
    } finally {
      SecurityContextHolder.clearContext();
    }
    mvc.perform(admin.mutate(post("/api/admin/users/" + target.getId() + "/enable")))
        .andExpect(jsonPath("$.code").value(200));
    login("target");
  }

  @Test
  @DisplayName("临时密码会话仅能读取本人身份和令牌、改密及登出，改密后恢复业务访问")
  void temporaryPasswordIsEnforcedByServer() throws Exception {
    accounts.createUser(new CreateAdminUserCommand("temporary", "secret-password",
        "Temporary", UserRole.ADMIN));
    var browser = login("temporary");
    mvc.perform(browser.read(get("/api/auth/me")))
        .andExpect(jsonPath("$.data.mustChangePassword").value(true));
    mvc.perform(browser.read(get("/api/auth/csrf"))).andExpect(jsonPath("$.code").value(200));
    mvc.perform(browser.read(get("/api/owner-probe/" + users.findAll().getFirst().getId())))
        .andExpect(jsonPath("$.code").value(12009));
    mvc.perform(browser.read(get("/api/admin/users")))
        .andExpect(jsonPath("$.code").value(12009));
    mvc.perform(browser.mutate(post("/api/auth/change-password"))
        .contentType("application/json")
        .content("{\"currentPassword\":\"secret-password\",\"newPassword\":\"new-password\"}"))
        .andExpect(jsonPath("$.code").value(200));
    mvc.perform(browser.read(get("/api/auth/me")))
        .andExpect(jsonPath("$.data.mustChangePassword").value(false));
    mvc.perform(browser.read(get("/api/admin/users")))
        .andExpect(jsonPath("$.code").value(200));
  }

  @Test
  @DisplayName("管理员分页搜索包含邀请注册账号且响应不包含口令或业务内容")
  void adminListsPersistedAccountsSafely() throws Exception {
    var admin = createUser("admin-list", UserRole.ADMIN);
    var issued = inviteCodes.issue(Duration.ofHours(1), admin.getId());
    inviteCodes.register(issued.rawCode(), "invited", "chosen-password", "Invited Person");
    var browser = login("admin-list");
    var result = mvc.perform(browser.read(get("/api/admin/users")
        .param("search", "invited").param("page", "0").param("size", "10")))
        .andExpect(jsonPath("$.code").value(200))
        .andExpect(jsonPath("$.data.content[0].username").value("invited"))
        .andReturn();
    assertThat(result.getResponse().getContentAsString())
        .doesNotContain("chosen-password", "passwordHash", "resumeText");
  }

  @Test
  @DisplayName("管理员可列出和撤销签发的邀请码，列表不泄露明文与哈希")
  void adminListsAndRevokesInvitesSafely() throws Exception {
    var admin = createUser("admin-invite", UserRole.ADMIN);
    var issued = inviteCodes.issue(Duration.ofHours(1), admin.getId());
    var browser = login("admin-invite");
    var result = mvc.perform(browser.read(get("/api/admin/invites")))
        .andExpect(jsonPath("$.code").value(200))
        .andExpect(jsonPath("$.data.content[0].id").value(issued.id().toString()))
        .andReturn();
    assertThat(result.getResponse().getContentAsString())
        .doesNotContain(issued.rawCode(), "inviteHash");
    mvc.perform(browser.mutate(post("/api/admin/invites/" + issued.id() + "/revoke")))
        .andExpect(jsonPath("$.code").value(200));
    mvc.perform(browser.read(get("/api/admin/invites")))
        .andExpect(jsonPath("$.data.content[0].status").value("REVOKED"));
  }

  @RestController
  public static class OwnerProbeController {
    private final CurrentUserProvider currentUsers;

    public OwnerProbeController(CurrentUserProvider currentUsers) {
      this.currentUsers = currentUsers;
    }

    @GetMapping("/api/owner-probe/{owner}")
    Result<Void> content(@PathVariable UUID owner) {
      if (!currentUsers.requireUserId().equals(owner)) {
        throw new BusinessException(ErrorCode.NOT_FOUND, "资源不存在");
      }
      return Result.success();
    }

    @GetMapping("/actuator/health")
    Result<Void> health() {
      return Result.success();
    }
  }
}
