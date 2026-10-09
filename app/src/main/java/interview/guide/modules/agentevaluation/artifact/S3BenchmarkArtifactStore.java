package interview.guide.modules.agentevaluation.artifact;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.infrastructure.file.FileStorageService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.regex.Pattern;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "app.agent-evaluation", name = "enabled", havingValue = "true")
public class S3BenchmarkArtifactStore implements BenchmarkArtifactStore {

  private static final String CONTENT_TYPE = "application/octet-stream";
  private static final Pattern SAFE_SEGMENT = Pattern.compile("[A-Za-z0-9._-]+");

  private final FileStorageService storageService;
  private final BenchmarkArtifactCipher cipher;

  @Override
  public String put(
      String runId,
      String caseId,
      String stageId,
      String artifactType,
      byte[] plaintext
  ) {
    validateSegments(runId, caseId, stageId, artifactType);
    String key = "agent-evaluation/%s/%s/%s/%s-%s.enc".formatted(
        runId,
        caseId,
        stageId,
        artifactType,
        UUID.randomUUID()
    );
    byte[] encrypted = cipher.encrypt(plaintext, aad(runId, caseId, stageId, artifactType));
    return storageService.uploadBytes(encrypted, key, CONTENT_TYPE);
  }

  @Override
  public byte[] get(
      String key,
      String runId,
      String caseId,
      String stageId,
      String artifactType
  ) {
    validateSegments(runId, caseId, stageId, artifactType);
    byte[] encrypted = storageService.downloadFile(key);
    return cipher.decrypt(encrypted, aad(runId, caseId, stageId, artifactType));
  }

  private byte[] aad(String runId, String caseId, String stageId, String artifactType) {
    String value = lengthPrefix(runId)
        + lengthPrefix(caseId)
        + lengthPrefix(stageId)
        + lengthPrefix(artifactType);
    return value.getBytes(StandardCharsets.UTF_8);
  }

  private String lengthPrefix(String value) {
    return value.length() + ":" + value;
  }

  private void validateSegments(String... values) {
    for (String value : values) {
      if (value == null || !SAFE_SEGMENT.matcher(value).matches()) {
        throw new BusinessException(ErrorCode.BAD_REQUEST, "评测产物标识包含非法字符");
      }
    }
  }
}
