package interview.guide.modules.interview.dynamic;

import interview.guide.common.annotation.RateLimit;
import interview.guide.common.result.Result;
import interview.guide.modules.auth.security.CurrentUserProvider;
import interview.guide.modules.interview.dynamic.dto.DynamicCompleteRequest;
import interview.guide.modules.interview.dynamic.dto.DynamicDraftRequest;
import interview.guide.modules.interview.dynamic.dto.DynamicRuntimeSnapshot;
import interview.guide.modules.interview.dynamic.dto.DynamicSubmissionReceipt;
import interview.guide.modules.interview.dynamic.dto.DynamicSubmitRequest;
import interview.guide.modules.interview.dynamic.service.DynamicInterviewCommandService;
import interview.guide.modules.interview.dynamic.service.DynamicInterviewQueryService;
import interview.guide.modules.interview.dynamic.service.DynamicInterviewSessionSupport;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/interview/sessions/{sessionId}/dynamic")
public class DynamicInterviewController {
  private final CurrentUserProvider currentUser;
  private final DynamicInterviewQueryService queries;
  private final DynamicInterviewCommandService commands;
  private final DynamicInterviewSessionSupport sessions;

  @GetMapping
  public Result<DynamicRuntimeSnapshot> get(@PathVariable String sessionId) {
    return Result.success(queries.get(currentUser.requireUserId(), sessionId));
  }

  @PostMapping("/answers")
  @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 10)
  public Result<DynamicSubmissionReceipt> submit(@PathVariable String sessionId,
      @Valid @RequestBody DynamicSubmitRequest request) {
    var ownerId = currentUser.requireUserId();
    var receipt = commands.submit(ownerId, sessionId, request);
    sessions.invalidate(ownerId, sessionId);
    return Result.success(receipt);
  }

  @PutMapping("/draft")
  @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 30)
  public Result<Void> draft(@PathVariable String sessionId,
      @Valid @RequestBody DynamicDraftRequest request) {
    var ownerId = currentUser.requireUserId();
    commands.saveDraft(ownerId, sessionId, request.questionIndex(), request.expectedVersion(),
        request.answer());
    sessions.invalidate(ownerId, sessionId);
    return Result.success(null);
  }

  @PostMapping("/complete")
  @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 10)
  public Result<DynamicRuntimeSnapshot> complete(@PathVariable String sessionId,
      @Valid @RequestBody DynamicCompleteRequest request) {
    var ownerId = currentUser.requireUserId();
    var snapshot = commands.complete(ownerId, sessionId, request.expectedVersion());
    sessions.invalidate(ownerId, sessionId);
    return Result.success(snapshot);
  }
}
