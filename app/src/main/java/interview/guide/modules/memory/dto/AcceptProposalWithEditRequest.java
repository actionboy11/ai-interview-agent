package interview.guide.modules.memory.dto;

import interview.guide.modules.memory.model.AbilityLevel;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record AcceptProposalWithEditRequest(@NotNull @Min(0) Long version,
    @NotNull AbilityLevel level, @NotBlank @Size(max = 2000) String summary) {
}
