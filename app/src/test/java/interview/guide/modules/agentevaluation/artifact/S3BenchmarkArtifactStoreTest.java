package interview.guide.modules.agentevaluation.artifact;

import interview.guide.infrastructure.file.FileStorageService;
import interview.guide.modules.agentevaluation.config.AgentEvaluationProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("RustFS 评测产物存储")
class S3BenchmarkArtifactStoreTest {

  @Test
  @DisplayName("写入 RustFS 的只能是带正确前缀的密文")
  void shouldStoreOnlyEncryptedBytesUnderEvaluationPrefix() {
    FileStorageService storageService = mock(FileStorageService.class);
    S3BenchmarkArtifactStore store = store(storageService);
    byte[] plaintext = "raw model output".getBytes(StandardCharsets.UTF_8);
    when(storageService.uploadBytes(any(byte[].class), anyString(), anyString()))
        .thenAnswer(invocation -> invocation.getArgument(1));

    String key = store.put("run-1", "case-1", "stage-1", "raw-response", plaintext);

    ArgumentCaptor<byte[]> bytes = ArgumentCaptor.forClass(byte[].class);
    ArgumentCaptor<String> objectKey = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<String> contentType = ArgumentCaptor.forClass(String.class);
    verify(storageService).uploadBytes(bytes.capture(), objectKey.capture(), contentType.capture());
    assertThat(key).isEqualTo(objectKey.getValue());
    assertThat(key).startsWith(
        "agent-evaluation/run-1/case-1/stage-1/raw-response-");
    assertThat(key).endsWith(".enc");
    assertThat(contentType.getValue()).isEqualTo("application/octet-stream");
    assertThat(bytes.getValue()).isNotEqualTo(plaintext);
  }

  @Test
  @DisplayName("读取时使用相同标识作为 AAD 并返回解密正文")
  void shouldDecryptArtifactUsingBoundIdentifiers() {
    FileStorageService storageService = mock(FileStorageService.class);
    S3BenchmarkArtifactStore store = store(storageService);
    byte[] plaintext = "raw model output".getBytes(StandardCharsets.UTF_8);
    when(storageService.uploadBytes(any(byte[].class), anyString(), anyString()))
        .thenAnswer(invocation -> invocation.getArgument(1));
    String key = store.put("run-1", "case-1", "stage-1", "raw-response", plaintext);
    ArgumentCaptor<byte[]> encrypted = ArgumentCaptor.forClass(byte[].class);
    verify(storageService).uploadBytes(encrypted.capture(), anyString(), anyString());
    when(storageService.downloadFile(key)).thenReturn(encrypted.getValue());

    byte[] restored = store.get(
        key,
        "run-1",
        "case-1",
        "stage-1",
        "raw-response"
    );

    assertThat(restored).isEqualTo(plaintext);
  }

  private S3BenchmarkArtifactStore store(FileStorageService storageService) {
    byte[] key = new byte[32];
    Arrays.fill(key, (byte) 0x33);
    AgentEvaluationProperties properties = new AgentEvaluationProperties();
    properties.setArtifactKey(Base64.getEncoder().encodeToString(key));
    return new S3BenchmarkArtifactStore(storageService, new BenchmarkArtifactCipher(properties));
  }
}
