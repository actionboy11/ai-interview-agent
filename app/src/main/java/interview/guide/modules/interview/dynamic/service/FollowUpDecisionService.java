package interview.guide.modules.interview.dynamic.service;

import interview.guide.common.ai.LlmProviderRegistry;
import interview.guide.common.ai.PromptSanitizer;
import interview.guide.common.ai.StructuredOutputInvoker;
import interview.guide.common.ai.PromptSecurityConstants;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.interview.dynamic.model.FollowUpContext;
import interview.guide.modules.interview.dynamic.model.FollowUpModelDecision;
import java.time.Clock;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.io.IOException;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

/** Proposes a decision only; publication, budget consumption and late-result fences belong elsewhere. */
@Service
@Slf4j
public class FollowUpDecisionService {
  private final LlmProviderRegistry registry;
  private final StructuredOutputInvoker invoker;
  private final FollowUpDecisionValidator validator;
  private final PromptSanitizer sanitizer;
  private final Clock clock;
  private final PromptTemplate prompt;
  private final JsonMapper mapper = JsonMapper.builder().build();
  private final BeanOutputConverter<FollowUpModelDecision> converter =
      new BeanOutputConverter<>(FollowUpModelDecision.class);

  public FollowUpDecisionService(LlmProviderRegistry registry, StructuredOutputInvoker invoker,
      FollowUpDecisionValidator validator, PromptSanitizer sanitizer, ResourceLoader loader,
      Clock clock) throws IOException {
    this.registry = registry;
    this.invoker = invoker;
    this.validator = validator;
    this.sanitizer = sanitizer;
    this.clock = clock;
    this.prompt = new PromptTemplate(loader.getResource(
        "classpath:prompts/interview/dynamic-follow-up.st").getContentAsString(StandardCharsets.UTF_8));
  }

  public FollowUpModelDecision decide(String provider, FollowUpContext context, Instant deadline) {
    if (deadline == null || !clock.instant().isBefore(deadline)) {
      throw new BusinessException(ErrorCode.INTERVIEW_QUESTION_GENERATION_FAILED,
          "动态追问决策失败：决策deadline已耗尽");
    }
    validator.validateContext(context);
    // Preserve the exact formal evidence, including uncertain answers and possible injection text.
    // Random delimiters and system instructions establish the boundary; do not silently rewrite it.
    var data = sanitizer.wrapWithDelimiters("current_group",
        mapper.writeValueAsString(context.snapshot()));
    return invoker.invoke(registry.getChatClientOrDefault(provider),
        "你是技术面试官，只提出当前主问题组的有限追问建议。"
            + PromptSecurityConstants.DATA_BOUNDARY_INSTRUCTION + converter.getFormat(),
        prompt.render(Map.of("context", data)), converter,
        ErrorCode.INTERVIEW_QUESTION_GENERATION_FAILED, "动态追问决策失败：", "动态追问决策",
        provider, "unknown", decision -> validator.validate(context, decision), deadline, log);
  }
}
