package interview.guide.modules.voiceinterview.config;

import interview.guide.common.exception.BusinessException;
import interview.guide.modules.auth.security.CurrentUser;
import interview.guide.modules.auth.service.AuthUserQueryService;
import interview.guide.modules.voiceinterview.service.VoiceInterviewService;
import jakarta.servlet.http.HttpSession;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

/** Authorizes every connection and reconnect against the HTTP session and persisted owner. */
@Component
@RequiredArgsConstructor
public class VoiceInterviewHandshakeInterceptor implements HandshakeInterceptor {
  public static final String OWNER_ID_ATTRIBUTE = "verifiedVoiceOwnerId";
  public static final String SESSION_ID_ATTRIBUTE = "verifiedVoiceSessionId";

  private final AuthUserQueryService users;
  private final VoiceInterviewService interviews;

  @Override
  public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
      WebSocketHandler handler, Map<String, Object> attributes) {
    if (!(request instanceof ServletServerHttpRequest servletRequest)) {
      response.setStatusCode(HttpStatus.UNAUTHORIZED);
      return false;
    }
    HttpSession httpSession = servletRequest.getServletRequest().getSession(false);
    if (httpSession == null) {
      response.setStatusCode(HttpStatus.UNAUTHORIZED);
      return false;
    }
    Object contextValue = httpSession.getAttribute(
        HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
    if (!(contextValue instanceof SecurityContext context)
        || context.getAuthentication() == null
        || !context.getAuthentication().isAuthenticated()
        || !(context.getAuthentication().getPrincipal() instanceof CurrentUser principal)) {
      response.setStatusCode(HttpStatus.UNAUTHORIZED);
      return false;
    }
    String path = request.getURI().getPath();
    String idText = path.substring(path.lastIndexOf('/') + 1);
    final Long sessionId;
    try {
      sessionId = Long.valueOf(idText);
    } catch (NumberFormatException exception) {
      response.setStatusCode(HttpStatus.NOT_FOUND);
      return false;
    }
    try {
      var current = users.requireActive(principal.id());
      if (current.mustChangePassword()) {
        response.setStatusCode(HttpStatus.FORBIDDEN);
        return false;
      }
      UUID ownerId = current.id();
      interviews.requireSessionOwner(sessionId, ownerId);
      attributes.put(OWNER_ID_ATTRIBUTE, ownerId);
      attributes.put(SESSION_ID_ATTRIBUTE, sessionId);
      return true;
    } catch (BusinessException exception) {
      response.setStatusCode(HttpStatus.NOT_FOUND);
      return false;
    }
  }

  @Override
  public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
      WebSocketHandler handler, Exception exception) {
  }
}
