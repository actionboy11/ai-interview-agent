package interview.guide.modules.knowledgebase.deadletter;

import interview.guide.common.annotation.RateLimit;
import interview.guide.common.result.Result;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/admin/knowledge-vectorization/dead-letters")
@Tag(name = "Knowledge vectorization dead letters")
public class KnowledgeVectorizationDeadLetterController {
    private final KnowledgeVectorizationDeadLetterService service;

    public KnowledgeVectorizationDeadLetterController(KnowledgeVectorizationDeadLetterService service) {
        this.service = service;
    }

    @GetMapping
    public Result<KnowledgeVectorizationDeadLetterPageResponse> list(
        @RequestParam(required = false) KnowledgeVectorizationDeadLetterStatus status,
        @RequestParam(defaultValue = "0") @Min(0) int page,
        @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size
    ) {
        return Result.success(service.list(status, page, size));
    }

    @GetMapping("/{id}")
    public Result<KnowledgeVectorizationDeadLetterDTO> get(@PathVariable Long id) {
        return Result.success(service.get(id));
    }

    @PostMapping("/{id}/replay")
    @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 5)
    @RateLimit(dimension = RateLimit.Dimension.IP, count = 5)
    public Result<KnowledgeVectorizationDeadLetterDTO> replay(@PathVariable Long id) {
        return Result.success(service.replay(id));
    }
}
