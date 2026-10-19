package interview.guide.modules.auth.service;

import interview.guide.modules.auth.dto.CurrentUserResponse;
import interview.guide.modules.auth.mapper.AuthResponseMapper;
import interview.guide.modules.auth.security.CurrentUser;
import interview.guide.modules.auth.security.CurrentUserProvider;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.stereotype.Service;

/** Session operations occur after account service transactions have committed. */
@Service
@RequiredArgsConstructor
public class AuthSessionService {
  private final AuthService auth;
  private final AuthenticationManager authenticationManager;
  private final AdminAccountService accounts;
  private final CurrentUserProvider currentUsers;
  private final AuthResponseMapper mapper;
  private final HttpSessionSecurityContextRepository contexts;
  private final CookieCsrfTokenRepository csrf;
  private final FindByIndexNameSessionRepository<? extends Session> sessions;

  public CurrentUserResponse login(String username, String password, HttpServletRequest request,
      HttpServletResponse response) {
    var authentication = authenticationManager.authenticate(
        UsernamePasswordAuthenticationToken.unauthenticated(username, password));
    CurrentUser user = (CurrentUser) authentication.getPrincipal();
    if (request.getSession(false) != null) {
      request.changeSessionId();
    }
    var context = SecurityContextHolder.createEmptyContext();
    context.setAuthentication(authentication);
    SecurityContextHolder.setContext(context);
    contexts.saveContext(context, request, response);
    csrf.saveToken(null, request, response);
    return mapper.toResponse(user);
  }

  public void logout(HttpServletRequest request, HttpServletResponse response) {
    new SecurityContextLogoutHandler().logout(request, response,
        SecurityContextHolder.getContext().getAuthentication());
    csrf.saveToken(null, request, response);
  }

  public CurrentUserResponse me() {
    return mapper.toResponse(currentUsers.requireCurrentUser());
  }

  public void changePassword(String currentPassword, String newPassword) {
    auth.changePassword(currentUsers.requireUserId(), currentPassword, newPassword);
  }

  public void disable(UUID id) {
    accounts.disable(id);
    sessions.findByPrincipalName(id.toString()).keySet().forEach(sessions::deleteById);
  }

  public void enable(UUID id) {
    accounts.enable(id);
  }
}
