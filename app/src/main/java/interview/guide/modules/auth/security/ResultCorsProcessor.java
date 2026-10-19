package interview.guide.modules.auth.security;

import interview.guide.common.exception.ErrorCode;
import interview.guide.common.result.Result;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.cors.DefaultCorsProcessor;
import tools.jackson.databind.json.JsonMapper;

final class ResultCorsProcessor extends DefaultCorsProcessor {
  private final JsonMapper json = JsonMapper.builder().build();

  @Override
  protected void rejectRequest(ServerHttpResponse response) throws IOException {
    response.setStatusCode(HttpStatus.OK);
    response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
    json.writeValue(response.getBody(), Result.error(ErrorCode.FORBIDDEN));
    response.flush();
  }
}
