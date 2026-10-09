package interview.guide.modules.agentevaluation.artifact;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.agentevaluation.config.AgentEvaluationProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

@Component
@ConditionalOnProperty(prefix = "app.agent-evaluation", name = "enabled", havingValue = "true")
public class BenchmarkArtifactCipher {

  private static final byte[] MAGIC = "AEV1".getBytes(StandardCharsets.US_ASCII);
  private static final int KEY_BYTES = 32;
  private static final int NONCE_BYTES = 12;
  private static final int TAG_BITS = 128;
  private static final int MIN_ENVELOPE_BYTES = MAGIC.length + NONCE_BYTES + TAG_BITS / 8;

  private final SecretKeySpec key;
  private final SecureRandom secureRandom = new SecureRandom();

  public BenchmarkArtifactCipher(AgentEvaluationProperties properties) {
    byte[] decoded;
    try {
      decoded = Base64.getDecoder().decode(properties.getArtifactKey());
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("评测产物加密密钥必须是有效 Base64", e);
    }
    if (decoded.length != KEY_BYTES) {
      throw new IllegalArgumentException("评测产物加密密钥解码后必须为 32 字节");
    }
    this.key = new SecretKeySpec(decoded, "AES");
  }

  public byte[] encrypt(byte[] plaintext, byte[] aad) {
    byte[] nonce = new byte[NONCE_BYTES];
    secureRandom.nextBytes(nonce);
    try {
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
      cipher.updateAAD(aad);
      byte[] ciphertext = cipher.doFinal(plaintext);
      return ByteBuffer.allocate(MAGIC.length + nonce.length + ciphertext.length)
          .put(MAGIC)
          .put(nonce)
          .put(ciphertext)
          .array();
    } catch (GeneralSecurityException e) {
      throw new BusinessException(ErrorCode.STORAGE_UPLOAD_FAILED, "评测产物加密失败", e);
    }
  }

  public byte[] decrypt(byte[] envelope, byte[] aad) {
    if (envelope == null || envelope.length < MIN_ENVELOPE_BYTES) {
      throw decryptionFailure(null);
    }
    ByteBuffer buffer = ByteBuffer.wrap(envelope);
    byte[] magic = new byte[MAGIC.length];
    buffer.get(magic);
    if (!Arrays.equals(magic, MAGIC)) {
      throw decryptionFailure(null);
    }
    byte[] nonce = new byte[NONCE_BYTES];
    buffer.get(nonce);
    byte[] ciphertext = new byte[buffer.remaining()];
    buffer.get(ciphertext);
    try {
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
      cipher.updateAAD(aad);
      return cipher.doFinal(ciphertext);
    } catch (AEADBadTagException e) {
      throw decryptionFailure(e);
    } catch (GeneralSecurityException e) {
      throw decryptionFailure(e);
    }
  }

  private BusinessException decryptionFailure(Exception cause) {
    return new BusinessException(
        ErrorCode.STORAGE_DOWNLOAD_FAILED,
        "评测产物解密或完整性校验失败",
        cause
    );
  }
}
