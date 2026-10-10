package interview.guide.modules.agentevaluation.service;

import interview.guide.common.ai.telemetry.AiCallObservation;
import interview.guide.common.ai.telemetry.AiParseOutcome;
import interview.guide.modules.agentevaluation.artifact.BenchmarkArtifactStore;
import interview.guide.modules.agentevaluation.model.AiCallAuditEntity;
import interview.guide.modules.agentevaluation.repository.AgentEvaluationRunRepository;
import interview.guide.modules.agentevaluation.repository.AiCallAuditRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
    classes = AiCallAuditPersistenceSinkTest.JpaTestApplication.class,
    properties = {
        "spring.datasource.url=jdbc:h2:mem:ai-call-audit;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.ai.model.audio.speech=none",
        "spring.ai.model.audio.transcription=none",
        "spring.ai.model.chat=none",
        "spring.ai.model.embedding=none",
        "spring.ai.model.image=none",
        "spring.ai.model.moderation=none",
        "spring.ai.vectorstore.type=none"
    }
)
@DisplayName("模型调用审计持久化 Sink")
class AiCallAuditPersistenceSinkTest {

  @Autowired
  private AiCallAuditRepository auditRepository;

  @Autowired
  private AiCallAuditWriter auditWriter;

  private AgentEvaluationExecutionContext executionContext;
  private InMemoryArtifactStore artifactStore;
  private AiCallAuditPersistenceSink sink;

  @BeforeEach
  void setUp() {
    auditRepository.deleteAll();
    executionContext = new AgentEvaluationExecutionContext();
    artifactStore = new InMemoryArtifactStore();
    sink = new AiCallAuditPersistenceSink(executionContext, artifactStore, auditWriter);
  }

  @Test
  @DisplayName("评测范围之外的普通 AI 调用不写审计账本")
  void shouldIgnoreObservationOutsideEvaluationScope() {
    sink.record(observation("outside"));

    assertThat(auditRepository.count()).isZero();
    assertThat(artifactStore.values).isEmpty();
  }

  @Test
  @DisplayName("评测范围内保存关联审计和原始响应产物")
  void shouldPersistLinkedAuditAndRawResponseInsideScope() {
    StageContext stage = new StageContext("run-1", "case-1", "stage-1");

    executionContext.runWithStage(stage, () -> {
      sink.record(observation("{\"value\":\"ok\"}"));
      return null;
    });

    List<AiCallAuditEntity> audits = auditRepository.findAllByStageIdOrderByAttemptAsc("stage-1");
    assertThat(audits).singleElement().satisfies(audit -> {
      assertThat(audit.getRunId()).isEqualTo("run-1");
      assertThat(audit.getCaseId()).isEqualTo("case-1");
      assertThat(audit.getStageId()).isEqualTo("stage-1");
      assertThat(audit.getAttempt()).isOne();
      assertThat(audit.getTotalTokens()).isEqualTo(150);
      assertThat(audit.getArtifactKey()).isEqualTo(
          "memory/run-1/case-1/stage-1/raw-response");
    });
    String artifactKey = "memory/run-1/case-1/stage-1/raw-response";
    assertThat(artifactStore.values).containsKey(artifactKey);
    assertThat(artifactStore.values.get(artifactKey))
        .isEqualTo("{\"value\":\"ok\"}".getBytes(StandardCharsets.UTF_8));
  }

  private AiCallObservation observation(String rawResponse) {
    return new AiCallObservation(
        "question_generation",
        "dashscope",
        "qwen3.5-flash",
        1,
        AiParseOutcome.DIRECT_SUCCESS,
        20_000_000L,
        20_000_000L,
        true,
        120,
        30,
        150,
        rawResponse,
        null,
        null,
        Instant.parse("2026-10-09T10:00:00Z")
    );
  }

  private static final class InMemoryArtifactStore implements BenchmarkArtifactStore {

    private final Map<String, byte[]> values = new HashMap<>();

    @Override
    public String put(
        String runId,
        String caseId,
        String stageId,
        String artifactType,
        byte[] plaintext
    ) {
      String key = "memory/%s/%s/%s/%s".formatted(runId, caseId, stageId, artifactType);
      values.put(key, plaintext.clone());
      return key;
    }

    @Override
    public byte[] get(
        String key,
        String runId,
        String caseId,
        String stageId,
        String artifactType
    ) {
      return values.get(key).clone();
    }
  }

  @SpringBootConfiguration
  @EnableAutoConfiguration
  @EntityScan(basePackageClasses = AiCallAuditEntity.class)
  @EnableJpaRepositories(basePackageClasses = AgentEvaluationRunRepository.class)
  @Import(AiCallAuditWriter.class)
  static class JpaTestApplication {
  }
}
