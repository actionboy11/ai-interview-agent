package interview.guide.modules.auth.security;

import interview.guide.common.exception.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationTrustResolverImpl;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ResultAccessDeniedHandler implements AccessDeniedHandler {
  private final ResultAuthenticationEntryPoint responses;

  @Override
  public void handle(HttpServletRequest request, HttpServletResponse response,
      AccessDeniedException exception) throws IOException {
    var authentication = SecurityContextHolder.getContext().getAuthentication();
    ErrorCode code = new AuthenticationTrustResolverImpl().isAuthenticated(authentication)
        ? ErrorCode.FORBIDDEN : ErrorCode.UNAUTHORIZED;
    responses.write(response, code.getCode(), code.getMessage());
  }
}
