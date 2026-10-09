package interview.guide.modules.agentevaluation.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.modules.agentevaluation.model.AgentEvaluationCaseEntity;
import interview.guide.modules.agentevaluation.model.AgentEvaluationCaseStatus;
import interview.guide.modules.agentevaluation.model.AgentEvaluationErrorType;
import interview.guide.modules.agentevaluation.model.AgentEvaluationRunEntity;
import interview.guide.modules.agentevaluation.model.AgentEvaluationRunStatus;
import interview.guide.modules.agentevaluation.model.AgentEvaluationStageStatus;
import interview.guide.modules.agentevaluation.model.AgentEvaluationStageType;
import interview.guide.modules.agentevaluation.repository.AgentEvaluationCaseRepository;
import interview.guide.modules.agentevaluation.repository.AgentEvaluationRunRepository;
import interview.guide.modules.agentevaluation.repository.AgentEvaluationStageRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(
    classes = AgentEvaluationCheckpointServiceTest.JpaTestApplication.class,
    properties = {
        "spring.datasource.url=jdbc:h2:mem:agent-checkpoints;DB_CLOSE_DELAY=-1",
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
@DisplayName("Agent 评测检查点")
class AgentEvaluationCheckpointServiceTest {

  private static final Instant NOW = Instant.parse("2026-10-09T09:00:00Z");

  @Autowired
  private StageIdempotencyKeyFactory keyFactory;

  @Autowired
  private AgentEvaluationCheckpointService checkpointService;

  @Autowired
  private AgentEvaluationRunRepository runRepository;

  @Autowired
  private AgentEvaluationCaseRepository caseRepository;

  @Autowired
  private AgentEvaluationStageRepository stageRepository;

  @Test
  @DisplayName("幂等键使用长度前缀字段生成稳定 SHA-256")
  void shouldCreateDeterministicDelimiterSafeIdempotencyKey() {
    String key = keyFactory.create(
        "run",
        "case",
        AgentEvaluationStageType.RESUME_PARSE,
        "input",
        "prompt",
        "model"
    );

    assertThat(key).isEqualTo("3800698bca988c711783d8d4af068608701abb47a9f987142243030db9de1312");
    assertThat(keyFactory.create(
        "a", "bc", AgentEvaluationStageType.RESUME_PARSE, "input", "prompt", "model"))
        .isNotEqualTo(keyFactory.create(
            "ab", "c", AgentEvaluationStageType.RESUME_PARSE, "input", "prompt", "model"));
  }

  @Test
  @DisplayName("成功阶段再次请求时直接复用且不覆盖产物")
  void shouldReuseSucceededStageWithoutOverwritingArtifact() {
    Fixture fixture = fixture("reuse");
    StageClaim first = claim(fixture);
    checkpointService.markSucceeded(
        first.stage().getId(),
        "artifact/original",
        1_250L,
        100,
        20,
        120,
        NOW.plusSeconds(2)
    );

    StageClaim second = claim(fixture);

    assertThat(first.disposition()).isEqualTo(StageClaimDisposition.CLAIMED);
    assertThat(second.disposition()).isEqualTo(StageClaimDisposition.REUSED);
    assertThat(second.stage().getArtifactKey()).isEqualTo("artifact/original");
    assertThat(second.stage().getStatus()).isEqualTo(AgentEvaluationStageStatus.SUCCEEDED);
  }

  @Test
  @DisplayName("运行中阶段的第二次认领返回 BUSY")
  void shouldReturnBusyForAlreadyRunningStage() {
    Fixture fixture = fixture("busy");

    StageClaim first = claim(fixture);
    StageClaim second = claim(fixture);

    assertThat(first.disposition()).isEqualTo(StageClaimDisposition.CLAIMED);
    assertThat(second.disposition()).isEqualTo(StageClaimDisposition.BUSY);
    assertThat(second.stage().getAttemptCount()).isOne();
  }

  @Test
  @DisplayName("凭证失效同时暂停阶段、样本和批次")
  void shouldPauseStageCaseAndRunForInvalidCredential() {
    Fixture fixture = fixture("credential");
    StageClaim claim = claim(fixture);

    checkpointService.pauseForCredential(
        claim.stage().getId(),
        "credential unavailable",
        NOW.plusSeconds(3)
    );

    assertThat(stageRepository.findById(claim.stage().getId()).orElseThrow()).satisfies(stage -> {
      assertThat(stage.getStatus()).isEqualTo(AgentEvaluationStageStatus.PAUSED);
      assertThat(stage.getErrorType()).isEqualTo(AgentEvaluationErrorType.INVALID_CREDENTIAL);
    });
    assertThat(caseRepository.findById(fixture.testCase().getId()).orElseThrow().getStatus())
        .isEqualTo(AgentEvaluationCaseStatus.PAUSED);
    assertThat(runRepository.findById(fixture.run().getId()).orElseThrow()).satisfies(run -> {
      assertThat(run.getStatus()).isEqualTo(AgentEvaluationRunStatus.PAUSED);
      assertThat(run.getPauseReason()).isEqualTo("credential unavailable");
    });
  }

  @Test
  @DisplayName("成功阶段不能再被标记为失败")
  void shouldRejectOverwritingSucceededStage() {
    Fixture fixture = fixture("immutable");
    StageClaim claim = claim(fixture);
    checkpointService.markSucceeded(
        claim.stage().getId(), "artifact/final", 10L, 1, 1, 2, NOW.plusSeconds(1));

    assertThatThrownBy(() -> checkpointService.markFailed(
        claim.stage().getId(),
        AgentEvaluationErrorType.INFRASTRUCTURE_ERROR,
        "late failure",
        NOW.plusSeconds(2)
    )).isInstanceOf(BusinessException.class);

    assertThat(stageRepository.findById(claim.stage().getId()).orElseThrow()).satisfies(stage -> {
      assertThat(stage.getStatus()).isEqualTo(AgentEvaluationStageStatus.SUCCEEDED);
      assertThat(stage.getArtifactKey()).isEqualTo("artifact/final");
    });
  }

  private StageClaim claim(Fixture fixture) {
    return checkpointService.claimOrReuse(
        fixture.run().getId(),
        fixture.testCase().getId(),
        fixture.testCase().getCaseKey(),
        AgentEvaluationStageType.RESUME_PARSE,
        fixture.testCase().getInputHash(),
        "prompt-v1",
        "dashscope",
        "qwen3.5-flash",
        NOW.plusSeconds(1)
    );
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
