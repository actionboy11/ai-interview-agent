package interview.guide.modules.resume.deadletter;

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
@RequestMapping("/api/admin/resume-analysis/dead-letters")
@Tag(name = "Resume analysis dead letters")
public class ResumeAnalysisDeadLetterController {
    private final ResumeAnalysisDeadLetterService service;

    public ResumeAnalysisDeadLetterController(ResumeAnalysisDeadLetterService service) {
        this.service = service;
    }

    @GetMapping
    public Result<ResumeAnalysisDeadLetterPageResponse> list(
        @RequestParam(required = false) ResumeAnalysisDeadLetterStatus status,
        @RequestParam(defaultValue = "0") @Min(0) int page,
        @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size
    ) {
        return Result.success(service.list(status, page, size));
    }

    @GetMapping("/{id}")
    public Result<ResumeAnalysisDeadLetterDTO> get(@PathVariable Long id) {
        return Result.success(service.get(id));
    }

    @PostMapping("/{id}/replay")
    @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 5)
    @RateLimit(dimension = RateLimit.Dimension.IP, count = 5)
    public Result<ResumeAnalysisDeadLetterDTO> replay(@PathVariable Long id) {
        return Result.success(service.replay(id));
    }
}
