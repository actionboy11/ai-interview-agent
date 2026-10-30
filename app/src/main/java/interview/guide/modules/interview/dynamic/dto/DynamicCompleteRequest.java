package interview.guide.modules.interview.dynamic.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record DynamicCompleteRequest(@NotNull @Min(0) Long expectedVersion) {}
