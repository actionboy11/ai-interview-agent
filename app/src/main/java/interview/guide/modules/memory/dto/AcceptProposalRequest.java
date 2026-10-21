package interview.guide.modules.memory.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record AcceptProposalRequest(@NotNull @Min(0) Long version) {
}
