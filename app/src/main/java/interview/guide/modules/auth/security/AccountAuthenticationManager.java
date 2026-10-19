package interview.guide.modules.auth.security;

import interview.guide.modules.auth.mapper.AuthResponseMapper;
import interview.guide.modules.auth.service.AuthService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.stereotype.Component;

/** Uses the account service as the only credential verifier and suppresses Boot's default user. */
@Component
@RequiredArgsConstructor
public class AccountAuthenticationManager implements AuthenticationManager {
  private final AuthService auth;
  private final AuthResponseMapper mapper;

  @Override
  public Authentication authenticate(Authentication request) {
    String password = request.getCredentials() instanceof String value ? value : null;
    var user = mapper.toCurrentUser(auth.authenticate(request.getName(), password));
    return UsernamePasswordAuthenticationToken.authenticated(user, null,
        List.of(new SimpleGrantedAuthority("ROLE_" + user.role().name())));
  }
}
