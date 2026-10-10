package interview.guide.modules.agentevaluation.integration;

import interview.guide.common.exception.BusinessException;
import interview.guide.modules.agentevaluation.artifact.BenchmarkArtifactCipher;
import interview.guide.modules.agentevaluation.config.AgentEvaluationProperties;
import interview.guide.modules.agentevaluation.model.AgentEvaluationCaseEntity;
import interview.guide.modules.agentevaluation.model.AgentEvaluationRunEntity;
import interview.guide.modules.agentevaluation.model.AgentEvaluationRunStatus;
import interview.guide.modules.agentevaluation.model.AgentEvaluationStageStatus;
import interview.guide.modules.agentevaluation.model.AgentEvaluationStageType;
import interview.guide.modules.agentevaluation.repository.AgentEvaluationCaseRepository;
import interview.guide.modules.agentevaluation.repository.AgentEvaluationRunRepository;
import interview.guide.modules.agentevaluation.repository.AgentEvaluationStageRepository;
import interview.guide.modules.agentevaluation.service.AgentEvaluationCheckpointService;
import interview.guide.modules.agentevaluation.service.StageClaim;
import interview.guide.modules.agentevaluation.service.StageClaimDisposition;
import interview.guide.modules.agentevaluation.service.StageIdempotencyKeyFactory;
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
import java.util.Base64;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(
    classes = AgentEvaluationRecoveryIntegrationTest.JpaTestApplication.class,
    properties = {
        "spring.datasource.url=jdbc:h2:mem:agent-recovery;DB_CLOSE_DELAY=-1",
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
@DisplayName("Agent 评测恢复集成保障")
class AgentEvaluationRecoveryIntegrationTest {

  private static final Instant NOW = Instant.parse("2026-10-10T02:00:00Z");

  @Autowired
  private AgentEvaluationCheckpointService checkpointService;

  @Autowired
  private AgentEvaluationRunRepository runRepository;

  @Autowired
  private AgentEvaluationCaseRepository caseRepository;

  @Autowired
  private AgentEvaluationStageRepository stageRepository;

  @Test
  @DisplayName("凭证恢复后从首个未完成阶段继续且已成功阶段不重复调用模型")
  void shouldResumeFromFirstUnfinishedStageWithoutDuplicateModelCall() {
    Fixture fixture = fixture("credential-resume");
    AtomicInteger modelCalls = new AtomicInteger();
    RecoveryHarness firstCoordinator = new RecoveryHarness(fixture, modelCalls);

    firstCoordinator.complete(AgentEvaluationStageType.RESUME_PARSE, NOW.plusSeconds(1));
    StageClaim failed = firstCoordinator.claim(
        AgentEvaluationStageType.QUESTION_GENERATION,
        NOW.plusSeconds(2)
    );
    modelCalls.incrementAndGet();
    checkpointService.pauseForCredential(
        failed.stage().getId(),
        "API key 已失效",
        NOW.plusSeconds(3)
    );

    assertThat(runRepository.findById(fixture.run().getId()).orElseThrow().getStatus())
        .isEqualTo(AgentEvaluationRunStatus.PAUSED);

    checkpointService.resumeAfterCredentialRefresh(
        fixture.run().getId(),
        NOW.plusSeconds(4)
    );
    RecoveryHarness recreatedCoordinator = new RecoveryHarness(fixture, modelCalls);

    StageClaim reused = recreatedCoordinator.complete(
        AgentEvaluationStageType.RESUME_PARSE,
        NOW.plusSeconds(5)
    );
    StageClaim unfinished = recreatedCoordinator.claim(
        AgentEvaluationStageType.QUESTION_GENERATION,
        NOW.plusSeconds(6)
    );
    StageClaim duplicate = recreatedCoordinator.claim(
        AgentEvaluationStageType.QUESTION_GENERATION,
        NOW.plusSeconds(6)
    );
    recreatedCoordinator.completeClaim(unfinished, NOW.plusSeconds(7));

    assertThat(reused.disposition()).isEqualTo(StageClaimDisposition.REUSED);
    assertThat(unfinished.disposition()).isEqualTo(StageClaimDisposition.CLAIMED);
    assertThat(duplicate.disposition()).isEqualTo(StageClaimDisposition.BUSY);
    assertThat(modelCalls).hasValue(3);
    assertThat(stageRepository.findById(unfinished.stage().getId()).orElseThrow()).satisfies(stage -> {
      assertThat(stage.getStatus()).isEqualTo(AgentEvaluationStageStatus.SUCCEEDED);
      assertThat(stage.getAttemptCount()).isEqualTo(2);
    });
  }

  @Test
  @DisplayName("超时 RUNNING 孤儿阶段只回收一次并允许一次重新认领")
  void shouldRecoverRunningOrphanExactlyOnce() {
    Fixture fixture = fixture("orphan");
    RecoveryHarness coordinator = new RecoveryHarness(fixture, new AtomicInteger());
    StageClaim orphan = coordinator.claim(AgentEvaluationStageType.JD_ANALYSIS, NOW.plusSeconds(1));

    int firstRecovery = checkpointService.recoverOrphanedStages(
        NOW.plusSeconds(2),
        NOW.plusSeconds(3)
    );
    int secondRecovery = checkpointService.recoverOrphanedStages(
        NOW.plusSeconds(2),
        NOW.plusSeconds(4)
    );
    StageClaim reclaimed = coordinator.claim(
        AgentEvaluationStageType.JD_ANALYSIS,
        NOW.plusSeconds(5)
    );
    StageClaim duplicate = coordinator.claim(
        AgentEvaluationStageType.JD_ANALYSIS,
        NOW.plusSeconds(5)
    );

    assertThat(orphan.disposition()).isEqualTo(StageClaimDisposition.CLAIMED);
    assertThat(firstRecovery).isOne();
    assertThat(secondRecovery).isZero();
    assertThat(reclaimed.disposition()).isEqualTo(StageClaimDisposition.CLAIMED);
    assertThat(reclaimed.stage().getAttemptCount()).isEqualTo(2);
    assertThat(duplicate.disposition()).isEqualTo(StageClaimDisposition.BUSY);
  }

  @Test
  @DisplayName("加密评测产物被篡改时关闭失败且不返回部分明文")
  void shouldFailClosedForTamperedArtifact() {
    AgentEvaluationProperties properties = new AgentEvaluationProperties();
    properties.setArtifactKey(Base64.getEncoder().encodeToString(new byte[32]));
    BenchmarkArtifactCipher cipher = new BenchmarkArtifactCipher(properties);
    byte[] aad = "run/case/stage/raw".getBytes(StandardCharsets.UTF_8);
    byte[] encrypted = cipher.encrypt("sensitive".getBytes(StandardCharsets.UTF_8), aad);
    encrypted[encrypted.length - 1] ^= 0x01;

    assertThatThrownBy(() -> cipher.decrypt(encrypted, aad))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("完整性校验失败");
  }

  private Fixture fixture(String suffix) {
    AgentEvaluationRunEntity run = runRepository.saveAndFlush(AgentEvaluationRunEntity.create(
        "run-" + suffix,
        "dataset-v1",
        "prompt-v1",
        "abc1234",
        "dashscope",
        "qwen3.5-flash",
        1,
        NOW
    ));
    AgentEvaluationCaseEntity testCase = caseRepository.saveAndFlush(
        AgentEvaluationCaseEntity.create(
            run.getId(),
            "case-" + suffix,
            "input-" + suffix,
            NOW
        )
    );
    return new Fixture(run, testCase);
  }

  private final class RecoveryHarness {

    private final Fixture fixture;
    private final AtomicInteger modelCalls;

    private RecoveryHarness(Fixture fixture, AtomicInteger modelCalls) {
      this.fixture = fixture;
      this.modelCalls = modelCalls;
    }

    private StageClaim complete(AgentEvaluationStageType stageType, Instant now) {
      StageClaim claim = claim(stageType, now);
      if (claim.disposition() == StageClaimDisposition.CLAIMED) {
        completeClaim(claim, now.plusMillis(100));
      }
      return claim;
    }

    private void completeClaim(StageClaim claim, Instant now) {
      modelCalls.incrementAndGet();
      checkpointService.markSucceeded(
          claim.stage().getId(),
          "artifact/" + claim.stage().getStageType(),
          100L,
          10,
          5,
          15,
          now
      );
    }

    private StageClaim claim(AgentEvaluationStageType stageType, Instant now) {
      return checkpointService.claimOrReuse(
          fixture.run().getId(),
          fixture.testCase().getId(),
          fixture.testCase().getCaseKey(),
          stageType,
          fixture.testCase().getInputHash(),
          "prompt-v1",
          "dashscope",
          "qwen3.5-flash",
          now
      );
    }
  }

  private record Fixture(AgentEvaluationRunEntity run, AgentEvaluationCaseEntity testCase) {
  }

  @SpringBootConfiguration
  @EnableAutoConfiguration
  @EntityScan(basePackageClasses = AgentEvaluationRunEntity.class)
  @EnableJpaRepositories(basePackageClasses = AgentEvaluationRunRepository.class)
  @Import({StageIdempotencyKeyFactory.class, AgentEvaluationCheckpointService.class})
  static class JpaTestApplication {
  }
}
