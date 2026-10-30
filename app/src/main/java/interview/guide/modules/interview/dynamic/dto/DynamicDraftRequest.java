package interview.guide.modules.interview.dynamic.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record DynamicDraftRequest(@NotNull @Min(0) Integer questionIndex,
    @NotNull @Min(0) Long expectedVersion, String answer) {}
