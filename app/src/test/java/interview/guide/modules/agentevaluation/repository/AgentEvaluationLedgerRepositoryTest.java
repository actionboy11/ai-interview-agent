package interview.guide.modules.agentevaluation.repository;

import interview.guide.common.ai.telemetry.AiParseOutcome;
import interview.guide.modules.agentevaluation.model.AgentEvaluationCaseEntity;
import interview.guide.modules.agentevaluation.model.AgentEvaluationCaseStatus;
import interview.guide.modules.agentevaluation.model.AgentEvaluationRunEntity;
import interview.guide.modules.agentevaluation.model.AgentEvaluationRunStatus;
import interview.guide.modules.agentevaluation.model.AgentEvaluationStageEntity;
import interview.guide.modules.agentevaluation.model.AgentEvaluationStageStatus;
import interview.guide.modules.agentevaluation.model.AgentEvaluationStageType;
import interview.guide.modules.agentevaluation.model.AiCallAuditEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

import java.time.Instant;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(
    classes = AgentEvaluationLedgerRepositoryTest.JpaTestApplication.class,
    properties = {
        "spring.datasource.url=jdbc:h2:mem:agent-evaluation;DB_CLOSE_DELAY=-1",
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
@DisplayName("Agent 评测账本持久化")
class AgentEvaluationLedgerRepositoryTest {

  @SpringBootConfiguration
  @EnableAutoConfiguration
  @EntityScan(basePackageClasses = AgentEvaluationRunEntity.class)
  @EnableJpaRepositories(basePackageClasses = AgentEvaluationRunRepository.class)
  static class JpaTestApplication {
  }

  @Autowired
  private AgentEvaluationRunRepository runRepository;

  @Autowired
  private AgentEvaluationCaseRepository caseRepository;

  @Autowired
  private AgentEvaluationStageRepository stageRepository;

  @Autowired
  private AiCallAuditRepository auditRepository;

  @Test
  @DisplayName("批次、样本、阶段和调用审计使用字符串枚举并保留空 Token")
  void shouldPersistLedgerEnumsAndNullableTokens() {
    Instant now = Instant.parse("2026-10-09T08:00:00Z");
    AgentEvaluationRunEntity run = runRepository.saveAndFlush(AgentEvaluationRunEntity.create(
        "baseline",
        "dataset-v1",
        "prompt-v1",
        "abc1234",
        "dashscope",
        "qwen3.5-flash",
        20,
        now
    ));
    AgentEvaluationCaseEntity testCase = caseRepository.saveAndFlush(
        AgentEvaluationCaseEntity.create(run.getId(), "java-mid-001", "input-sha", now)
    );
    AgentEvaluationStageEntity stage = stageRepository.saveAndFlush(AgentEvaluationStageEntity.create(
        run.getId(),
        testCase.getId(),
        "java-mid-001",
        AgentEvaluationStageType.QUESTION_GENERATION,
        "input-sha",
        "prompt-v1",
        "dashscope",
        "qwen3.5-flash",
        "idempotency-sha",
        now
    ));
    AiCallAuditEntity audit = auditRepository.saveAndFlush(AiCallAuditEntity.create(
        run.getId(),
        testCase.getId(),
        stage.getId(),
        "question_generation",
        "dashscope",
        "qwen3.5-flash",
        1,
        AiParseOutcome.DIRECT_SUCCESS,
        25_000_000L,
        null,
        null,
        null,
        null,
        null,
        null,
        now
    ));

    assertThat(runRepository.findById(run.getId()).orElseThrow().getStatus())
        .isEqualTo(AgentEvaluationRunStatus.CREATED);
    assertThat(caseRepository.findById(testCase.getId()).orElseThrow().getStatus())
        .isEqualTo(AgentEvaluationCaseStatus.PENDING);
    assertThat(stageRepository.findById(stage.getId()).orElseThrow().getStatus())
        .isEqualTo(AgentEvaluationStageStatus.PENDING);
    assertThat(auditRepository.findById(audit.getId()).orElseThrow()).satisfies(saved -> {
      assertThat(saved.getParseOutcome()).isEqualTo(AiParseOutcome.DIRECT_SUCCESS);
      assertThat(saved.getPromptTokens()).isNull();
      assertThat(saved.getCompletionTokens()).isNull();
      assertThat(saved.getTotalTokens()).isNull();
    });
  }

  @Test
  @DisplayName("同一批次不能保存重复的样本键")
  void shouldRejectDuplicateCaseKeyWithinRun() {
    Instant now = Instant.parse("2026-10-09T08:00:00Z");
    AgentEvaluationRunEntity run = runRepository.saveAndFlush(newRun("run-a", now));
    caseRepository.saveAndFlush(
        AgentEvaluationCaseEntity.create(run.getId(), "duplicate", "hash-a", now)
    );

    assertThatThrownBy(() -> caseRepository.saveAndFlush(
        AgentEvaluationCaseEntity.create(run.getId(), "duplicate", "hash-b", now)
    )).isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  @DisplayName("阶段幂等键全局唯一")
  void shouldRejectDuplicateStageIdempotencyKey() {
    Instant now = Instant.parse("2026-10-09T08:00:00Z");
    AgentEvaluationRunEntity run = runRepository.saveAndFlush(newRun("run-a", now));
    AgentEvaluationCaseEntity firstCase = caseRepository.saveAndFlush(
        AgentEvaluationCaseEntity.create(run.getId(), "case-a", "hash-a", now)
    );
    AgentEvaluationCaseEntity secondCase = caseRepository.saveAndFlush(
        AgentEvaluationCaseEntity.create(run.getId(), "case-b", "hash-b", now)
    );
    stageRepository.saveAndFlush(newStage(run, firstCase, "same-key", now));

    assertThatThrownBy(() -> stageRepository.saveAndFlush(
        newStage(run, secondCase, "same-key", now)
    )).isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  @DisplayName("并发认领只允许第一个工作线程把阶段改为运行中")
  void shouldClaimStageOnlyOnce() {
    Instant now = Instant.parse("2026-10-09T08:00:00Z");
    AgentEvaluationRunEntity run = runRepository.saveAndFlush(newRun("run-a", now));
    AgentEvaluationCaseEntity testCase = caseRepository.saveAndFlush(
        AgentEvaluationCaseEntity.create(run.getId(), "case-a", "hash-a", now)
    );
    AgentEvaluationStageEntity stage = stageRepository.saveAndFlush(
        newStage(run, testCase, "claim-key", now)
    );

    int first = stageRepository.claim(
        stage.getId(),
        Set.of(AgentEvaluationStageStatus.PENDING, AgentEvaluationStageStatus.RETRY_WAITING),
        now.plusSeconds(1)
    );
    int second = stageRepository.claim(
        stage.getId(),
        Set.of(AgentEvaluationStageStatus.PENDING, AgentEvaluationStageStatus.RETRY_WAITING),
        now.plusSeconds(2)
    );

    assertThat(first).isOne();
    assertThat(second).isZero();
    assertThat(stageRepository.findById(stage.getId()).orElseThrow()).satisfies(claimed -> {
      assertThat(claimed.getStatus()).isEqualTo(AgentEvaluationStageStatus.RUNNING);
      assertThat(claimed.getStartedAt()).isEqualTo(now.plusSeconds(1));
    });
  }

  private AgentEvaluationRunEntity newRun(String name, Instant now) {
    return AgentEvaluationRunEntity.create(
        name,
        "dataset-v1",
        "prompt-v1",
        "abc1234",
        "dashscope",
        "qwen3.5-flash",
        2,
        now
    );
  }

  private AgentEvaluationStageEntity newStage(
      AgentEvaluationRunEntity run,
      AgentEvaluationCaseEntity testCase,
      String idempotencyKey,
      Instant now
  ) {
    return AgentEvaluationStageEntity.create(
        run.getId(),
        testCase.getId(),
        testCase.getCaseKey(),
        AgentEvaluationStageType.RESUME_PARSE,
        testCase.getInputHash(),
        "prompt-v1",
        "dashscope",
        "qwen3.5-flash",
        idempotencyKey,
        now
    );
  }
}
