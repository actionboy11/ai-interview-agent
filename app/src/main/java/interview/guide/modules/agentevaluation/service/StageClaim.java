package interview.guide.modules.agentevaluation.service;

import interview.guide.modules.agentevaluation.model.AgentEvaluationStageEntity;

public record StageClaim(
    StageClaimDisposition disposition,
    AgentEvaluationStageEntity stage
) {
}
