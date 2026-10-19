package interview.guide.modules.auth.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import java.text.Normalizer;
import java.util.Locale;
import org.springframework.stereotype.Component;

@Component
public class UsernameNormalizer {
  /** NFKC handles equivalent Unicode spellings; ROOT keeps casing independent of host locale. */
  public String normalize(String username) {
    if (username == null) {
      throw new BusinessException(ErrorCode.AUTH_INVALID_INPUT, "用户名不能为空");
    }
    String normalized = Normalizer.normalize(username, Normalizer.Form.NFKC).strip()
        .toLowerCase(Locale.ROOT);
    normalized = Normalizer.normalize(normalized, Normalizer.Form.NFKC);
    if (normalized.isBlank() || normalized.length() > 100 || username.strip().length() > 100
        || normalized.codePoints().anyMatch(Character::isISOControl)) {
      throw new BusinessException(ErrorCode.AUTH_INVALID_INPUT, "用户名无效或超过 100 字符");
    }
    return normalized;
  }
}
