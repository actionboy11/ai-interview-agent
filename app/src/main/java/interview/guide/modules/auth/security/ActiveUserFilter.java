package interview.guide.modules.auth.security;

import interview.guide.common.exception.BusinessException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/** Re-checks persisted status even when a request already loaded its session principal. */
@RequiredArgsConstructor
final class ActiveUserFilter extends OncePerRequestFilter {
  private final CurrentUserProvider currentUsers;
  private final ResultAuthenticationEntryPoint responses;

  @Override
  protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
      FilterChain chain) throws ServletException, IOException {
    var authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication != null && authentication.getPrincipal() instanceof CurrentUser) {
      try {
        currentUsers.requireCurrentUser();
      } catch (BusinessException exception) {
        SecurityContextHolder.clearContext();
        var session = request.getSession(false);
        if (session != null) {
          session.invalidate();
        }
        responses.write(response, exception.getCode(), exception.getMessage());
        return;
      }
    }
    chain.doFilter(request, response);
  }
}
