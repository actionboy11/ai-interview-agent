package interview.guide.modules.agentevaluation.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.agentevaluation.model.AgentEvaluationCaseEntity;
import interview.guide.modules.agentevaluation.model.AgentEvaluationErrorType;
import interview.guide.modules.agentevaluation.model.AgentEvaluationRunEntity;
import interview.guide.modules.agentevaluation.model.AgentEvaluationStageEntity;
import interview.guide.modules.agentevaluation.model.AgentEvaluationStageStatus;
import interview.guide.modules.agentevaluation.model.AgentEvaluationStageType;
import interview.guide.modules.agentevaluation.repository.AgentEvaluationCaseRepository;
import interview.guide.modules.agentevaluation.repository.AgentEvaluationRunRepository;
import interview.guide.modules.agentevaluation.repository.AgentEvaluationStageRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class AgentEvaluationCheckpointService {

  private static final Set<AgentEvaluationStageStatus> CLAIMABLE_STATUSES = Set.of(
      AgentEvaluationStageStatus.PENDING,
      AgentEvaluationStageStatus.RETRY_WAITING
  );

  private final AgentEvaluationRunRepository runRepository;
  private final AgentEvaluationCaseRepository caseRepository;
  private final AgentEvaluationStageRepository stageRepository;
  private final StageIdempotencyKeyFactory keyFactory;

  @Transactional
  public StageClaim claimOrReuse(
      String runId,
      String caseId,
      String caseKey,
      AgentEvaluationStageType stageType,
      String inputHash,
      String promptVersion,
      String provider,
      String model,
      Instant now
  ) {
    String idempotencyKey = keyFactory.create(
        runId, caseKey, stageType, inputHash, promptVersion, model);
    AgentEvaluationStageEntity stage = stageRepository.findByIdempotencyKey(idempotencyKey)
        .orElseGet(() -> stageRepository.saveAndFlush(AgentEvaluationStageEntity.create(
            runId,
            caseId,
            caseKey,
            stageType,
            inputHash,
            promptVersion,
            provider,
            model,
            idempotencyKey,
            now
        )));

    if (stage.getStatus() == AgentEvaluationStageStatus.SUCCEEDED) {
      return new StageClaim(StageClaimDisposition.REUSED, stage);
    }
    if (stage.getStatus() == AgentEvaluationStageStatus.RUNNING) {
      return new StageClaim(StageClaimDisposition.BUSY, stage);
    }
    if (!CLAIMABLE_STATUSES.contains(stage.getStatus())) {
      throw invalidTransition(stage, "claim");
    }

    int claimed = stageRepository.claim(stage.getId(), CLAIMABLE_STATUSES, now);
    AgentEvaluationStageEntity current = findStage(stage.getId());
    if (claimed == 0) {
      return current.getStatus() == AgentEvaluationStageStatus.SUCCEEDED
          ? new StageClaim(StageClaimDisposition.REUSED, current)
          : new StageClaim(StageClaimDisposition.BUSY, current);
    }
    AgentEvaluationCaseEntity testCase = findCase(caseId);
    testCase.startStage(stageType, now);
    return new StageClaim(StageClaimDisposition.CLAIMED, current);
  }

  @Transactional
  public void markSucceeded(
      String stageId,
      String artifactKey,
      long durationMs,
      Integer promptTokens,
      Integer completionTokens,
      Integer totalTokens,
      Instant completedAt
  ) {
    AgentEvaluationStageEntity stage = findStage(stageId);
    ensureRunning(stage, "succeed");
    stage.succeed(
        artifactKey,
        durationMs,
        promptTokens,
        completionTokens,
        totalTokens,
        completedAt
    );
  }

  @Transactional
  public void markRetryWaiting(
      String stageId,
      AgentEvaluationErrorType errorType,
      String errorSummary,
      Instant nextRetryAt,
      Instant now
  ) {
    AgentEvaluationStageEntity stage = findStage(stageId);
    ensureRunning(stage, "wait for retry");
    stage.waitForRetry(errorType, errorSummary, nextRetryAt, now);
  }

  @Transactional
  public void pauseForCredential(String stageId, String errorSummary, Instant now) {
    AgentEvaluationStageEntity stage = findStage(stageId);
    ensureRunning(stage, "pause for credential");
    AgentEvaluationCaseEntity testCase = findCase(stage.getCaseId());
    AgentEvaluationRunEntity run = findRun(stage.getRunId());
    stage.pauseForCredential(errorSummary, now);
    testCase.pause(errorSummary, now);
    run.pause(errorSummary, now);
  }

  @Transactional
  public void markFailed(
      String stageId,
      AgentEvaluationErrorType errorType,
      String errorSummary,
      Instant now
  ) {
    AgentEvaluationStageEntity stage = findStage(stageId);
    ensureRunning(stage, "fail");
    stage.fail(errorType, errorSummary, now);
  }

  private AgentEvaluationRunEntity findRun(String runId) {
    return runRepository.findById(runId)
        .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "评测批次不存在"));
  }

  private AgentEvaluationCaseEntity findCase(String caseId) {
    return caseRepository.findById(caseId)
        .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "评测样本不存在"));
  }

  private AgentEvaluationStageEntity findStage(String stageId) {
    return stageRepository.findById(stageId)
        .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "评测阶段不存在"));
  }

  private void ensureRunning(AgentEvaluationStageEntity stage, String action) {
    if (stage.getStatus() != AgentEvaluationStageStatus.RUNNING) {
      throw invalidTransition(stage, action);
    }
  }

  private BusinessException invalidTransition(
      AgentEvaluationStageEntity stage,
      String action
  ) {
    return new BusinessException(
        ErrorCode.BAD_REQUEST,
        "评测阶段状态不允许执行 " + action + ": " + stage.getStatus()
    );
  }
}
