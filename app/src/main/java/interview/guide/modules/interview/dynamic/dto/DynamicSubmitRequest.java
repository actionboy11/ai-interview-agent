package interview.guide.modules.interview.dynamic.dto;

import interview.guide.modules.interview.dynamic.model.SubmissionIntent;
import java.util.UUID;

public record DynamicSubmitRequest(UUID requestId, int questionIndex, long expectedVersion,
    SubmissionIntent submissionIntent, String answer) {}
