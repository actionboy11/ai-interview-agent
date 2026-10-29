package interview.guide.modules.interview.dynamic.dto;

import java.util.UUID;

public record DynamicSubmissionReceipt(UUID requestId, UUID decisionId,
    DynamicRuntimeSnapshot runtime) {}
