package interview.guide.modules.agentevaluation.artifact;

import interview.guide.common.exception.BusinessException;
import interview.guide.modules.agentevaluation.config.AgentEvaluationProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("评测产物 AES-GCM 加密")
class BenchmarkArtifactCipherTest {

  private static final byte[] KEY = new byte[32];
  private static final byte[] OTHER_KEY = new byte[32];
  private static final byte[] AAD = "run-1/case-1/stage-1/raw".getBytes(StandardCharsets.UTF_8);

  static {
    Arrays.fill(KEY, (byte) 0x11);
    Arrays.fill(OTHER_KEY, (byte) 0x22);
  }

  @Test
  @DisplayName("相同明文每次生成不同密文且都能正确解密")
  void shouldUseRandomNonceAndRoundTrip() {
    BenchmarkArtifactCipher cipher = cipher(KEY);
    byte[] plaintext = "sensitive model response".getBytes(StandardCharsets.UTF_8);

    byte[] first = cipher.encrypt(plaintext, AAD);
    byte[] second = cipher.encrypt(plaintext, AAD);

    assertThat(first).isNotEqualTo(second);
    assertThat(first).isNotEqualTo(plaintext);
    assertThat(cipher.decrypt(first, AAD)).isEqualTo(plaintext);
    assertThat(cipher.decrypt(second, AAD)).isEqualTo(plaintext);
  }

  @Test
  @DisplayName("错误密钥和错误 AAD 都不能得到任何明文")
  void shouldRejectWrongKeyAndWrongAad() {
    byte[] encrypted = cipher(KEY).encrypt("secret".getBytes(StandardCharsets.UTF_8), AAD);

    assertThatThrownBy(() -> cipher(OTHER_KEY).decrypt(encrypted, AAD))
        .isInstanceOf(BusinessException.class);
    assertThatThrownBy(() -> cipher(KEY).decrypt(
        encrypted,
        "other-stage".getBytes(StandardCharsets.UTF_8)
    )).isInstanceOf(BusinessException.class);
  }

  @Test
  @DisplayName("密文或认证标签被篡改时关闭失败")
  void shouldRejectTamperedEnvelope() {
    byte[] encrypted = cipher(KEY).encrypt("secret".getBytes(StandardCharsets.UTF_8), AAD);
    encrypted[encrypted.length - 1] ^= 0x01;

    assertThatThrownBy(() -> cipher(KEY).decrypt(encrypted, AAD))
        .isInstanceOf(BusinessException.class);
  }

  @Test
  @DisplayName("配置密钥必须是 Base64 编码的 32 字节")
  void shouldRejectInvalidKeyLength() {
    AgentEvaluationProperties properties = new AgentEvaluationProperties();
    properties.setArtifactKey(Base64.getEncoder().encodeToString(new byte[16]));

    assertThatThrownBy(() -> new BenchmarkArtifactCipher(properties))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("32");
  }

  private BenchmarkArtifactCipher cipher(byte[] key) {
    AgentEvaluationProperties properties = new AgentEvaluationProperties();
    properties.setArtifactKey(Base64.getEncoder().encodeToString(key));
    return new BenchmarkArtifactCipher(properties);
  }
}
