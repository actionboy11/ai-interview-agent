package interview.guide.modules.auth.security;

import interview.guide.common.exception.ErrorCode;
import interview.guide.common.result.Result;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Component
public class ResultAuthenticationEntryPoint implements AuthenticationEntryPoint {
  private final JsonMapper json = JsonMapper.builder().build();

  @Override
  public void commence(HttpServletRequest request, HttpServletResponse response,
      AuthenticationException exception) throws IOException {
    write(response, ErrorCode.UNAUTHORIZED.getCode(), ErrorCode.UNAUTHORIZED.getMessage());
  }

  public void write(HttpServletResponse response, int code, String message) throws IOException {
    response.setStatus(HttpServletResponse.SC_OK);
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    response.setCharacterEncoding("UTF-8");
    json.writeValue(response.getOutputStream(), Result.error(code, message));
  }
}
