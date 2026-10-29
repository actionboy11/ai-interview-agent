package interview.guide.common.ai;

import interview.guide.common.ai.telemetry.AiCallObservation;
import interview.guide.common.ai.telemetry.AiCallTelemetryRecorder;
import interview.guide.common.ai.telemetry.AiParseOutcome;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import org.slf4j.Logger;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ResponseEntity;
import org.springframework.ai.chat.metadata.EmptyUsage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.function.Consumer;

/**
 * 统一封装结构化输出调用与重试策略。
 */
@Component
public class StructuredOutputInvoker {

    private static final String STRICT_JSON_INSTRUCTION = """
请仅返回可被 JSON 解析器直接解析的 JSON 对象，并严格满足字段结构要求：
1) 不要输出 Markdown 代码块（如 ```json）。
2) 不要输出任何解释文字、前后缀、注释。
3) 所有字符串内引号必须正确转义。
    """;

    private final int maxAttempts;
    private final boolean includeLastErrorInRetryPrompt;
    private final boolean retryUseRepairPrompt;
    private final boolean retryAppendStrictJsonInstruction;
    private final int errorMessageMaxLength;
    private final boolean schemaValidationEnabled;
    private final AiCallTelemetryRecorder telemetryRecorder;
    private final StructuredOutputDeadlineExecutor deadlineExecutor;

    @Autowired
    public StructuredOutputInvoker(StructuredOutputProperties properties,
        AiCallTelemetryRecorder telemetryRecorder, StructuredOutputDeadlineExecutor executor) {
        this.maxAttempts = Math.max(1, properties.getStructuredMaxAttempts());
        this.includeLastErrorInRetryPrompt = properties.isStructuredIncludeLastError();
        this.retryUseRepairPrompt = properties.isStructuredRetryUseRepairPrompt();
        this.retryAppendStrictJsonInstruction = properties.isStructuredRetryAppendStrictJsonInstruction();
        this.errorMessageMaxLength = Math.max(20, properties.getStructuredErrorMessageMaxLength());
        this.schemaValidationEnabled = properties.isStructuredSchemaValidationEnabled();
        this.telemetryRecorder = telemetryRecorder;
        this.deadlineExecutor = executor;
    }

    /**
     * Absolute deadline includes queueing, calls and all repair attempts. Uses local conversion plus
     * the required semantic validator, not Spring AI's nested schema-validation retries or a full
     * JSON Schema engine. Legacy overloads retain their configured schema-validation behavior.
     */
    public <T> T invoke(ChatClient client, String system, String user,
        BeanOutputConverter<T> converter, ErrorCode errorCode, String prefix, String context,
        String provider, String model, Consumer<T> validator, Instant deadline, Logger log) {
        if (deadlineExecutor == null || validator == null) {
            throw new BusinessException(errorCode, prefix + "deadline执行设施及语义校验器必须提供");
        }
        return deadlineExecutor.execute(() -> invokeInternal(client, system, user, converter,
            errorCode, prefix, context, provider, model, validator, deadline, log),
            deadline, errorCode, prefix);
    }

    public StructuredOutputInvoker(
        StructuredOutputProperties properties,
        AiCallTelemetryRecorder telemetryRecorder
    ) {
        this(properties, telemetryRecorder, null);
    }

    public <T> T invoke(
        ChatClient chatClient,
        String systemPromptWithFormat,
        String userPrompt,
        BeanOutputConverter<T> outputConverter,
        ErrorCode errorCode,
        String errorPrefix,
        String logContext,
        Logger log
    ) {
        return invoke(
            chatClient,
            systemPromptWithFormat,
            userPrompt,
            outputConverter,
            errorCode,
            errorPrefix,
            logContext,
            "unknown",
            "unknown",
            log
        );
    }

    public <T> T invoke(
        ChatClient chatClient,
        String systemPromptWithFormat,
        String userPrompt,
        BeanOutputConverter<T> outputConverter,
        ErrorCode errorCode,
        String errorPrefix,
        String logContext,
        String provider,
        String model,
        Logger log
    ) {
        return invoke(chatClient, systemPromptWithFormat, userPrompt, outputConverter,
            errorCode, errorPrefix, logContext, provider, model, value -> {}, log);
    }

    /** Semantic validation shares the same bounded attempts as parsing and repair. */
    public <T> T invoke(
        ChatClient chatClient,
        String systemPromptWithFormat,
        String userPrompt,
        BeanOutputConverter<T> outputConverter,
        ErrorCode errorCode,
        String errorPrefix,
        String logContext,
        Consumer<T> validator,
        Logger log
    ) {
        return invoke(chatClient, systemPromptWithFormat, userPrompt, outputConverter,
            errorCode, errorPrefix, logContext, "unknown", "unknown", validator, log);
    }

    public <T> T invoke(
        ChatClient chatClient,
        String systemPromptWithFormat,
        String userPrompt,
        BeanOutputConverter<T> outputConverter,
        ErrorCode errorCode,
        String errorPrefix,
        String logContext,
        String provider,
        String model,
        Consumer<T> validator,
        Logger log
    ) {
        return invokeInternal(chatClient, systemPromptWithFormat, userPrompt, outputConverter,
            errorCode, errorPrefix, logContext, provider, model, validator, null, log);
    }

    private <T> T invokeInternal(ChatClient chatClient, String systemPromptWithFormat,
        String userPrompt, BeanOutputConverter<T> outputConverter, ErrorCode errorCode,
        String errorPrefix, String logContext, String provider, String model,
        Consumer<T> validator, Instant deadline, Logger log) {
        // 统计完整调用耗时，包括结构化解析失败后的重试。
        long startNanos = System.nanoTime();
        String securedSystemPrompt = systemPromptWithFormat
            + PromptSecurityConstants.ANTI_INJECTION_INSTRUCTION;
        Exception lastError = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            if (deadline != null) { deadlineExecutor.checkDeadline(deadline, errorCode, errorPrefix); }
            long attemptStartNanos = System.nanoTime();
            String attemptSystemPrompt = attempt == 1
                ? securedSystemPrompt
                : buildRetrySystemPrompt(securedSystemPrompt, lastError);
            try {
                StructuredCallResult<T> result = callStructuredOutput(
                    chatClient, attemptSystemPrompt, userPrompt, outputConverter, logContext,
                    deadline == null && schemaValidationEnabled, log);
                if (deadline != null) { deadlineExecutor.checkDeadline(deadline, errorCode, errorPrefix); }
                try {
                    validator.accept(result.value());
                } catch (Exception validationFailure) {
                    throw new StructuredParseException(result.rawResponse(), result.response(),
                        validationFailure);
                }
                if (deadline != null) { deadlineExecutor.checkDeadline(deadline, errorCode, errorPrefix); }
                telemetryRecorder.record(toObservation(
                    logContext,
                    provider,
                    model,
                    attempt,
                    result.outcome(),
                    attemptStartNanos,
                    startNanos,
                    true,
                    result.response(),
                    result.rawResponse(),
                    null
                ));
                return result.value();
            } catch (Exception e) {
                lastError = e;
                boolean cancelled = deadline != null && deadlineExecutor.expired(deadline);
                boolean terminal = attempt == maxAttempts || cancelled;
                StructuredParseException parseException = e instanceof StructuredParseException value
                    ? value
                    : null;
                telemetryRecorder.record(toObservation(
                    logContext,
                    provider,
                    model,
                    attempt,
                    parseException == null
                        ? AiParseOutcome.CALL_FAILURE
                        : AiParseOutcome.PARSE_FAILURE,
                    attemptStartNanos,
                    startNanos,
                    terminal,
                    parseException == null ? null : parseException.response(),
                    parseException == null ? null : parseException.rawResponse(),
                    e
                ));
                if (deadline != null) {
                    log.warn("{}结构化调用失败: attempt={}/{}, terminal={}, errorType={}",
                        logContext, attempt, maxAttempts, terminal, e.getClass().getSimpleName());
                } else if (attempt < maxAttempts) {
                    log.warn("{}结构化解析失败，准备重试: attempt={}/{}, error={}",
                        logContext, attempt, maxAttempts, e.getMessage());
                } else {
                    log.error("{}结构化解析失败，已达最大重试次数: attempts={}, error={}",
                        logContext, maxAttempts, e.getMessage());
                }
                if (cancelled) {
                    throw new BusinessException(errorCode, errorPrefix + "决策deadline已耗尽或等待已取消");
                }
            }
        }

        throw new BusinessException(
            errorCode,
            errorPrefix + (deadline != null ? "结构化解析或语义校验失败"
                : lastError != null ? lastError.getMessage() : "unknown")
        );
    }

    private <T> StructuredCallResult<T> callStructuredOutput(
        ChatClient chatClient,
        String systemPrompt,
        String userPrompt,
        BeanOutputConverter<T> outputConverter,
        String logContext,
        boolean validateSchema,
        Logger log
    ) {
        ChatClient.CallResponseSpec call = chatClient.prompt()
            .system(systemPrompt)
            .user(userPrompt)
            .call();
        if (validateSchema) {
            ResponseEntity<ChatResponse, T> result = call.responseEntity(
                outputConverter,
                spec -> spec.validateSchema()
            );
            ChatResponse response = result.response();
            return new StructuredCallResult<>(
                result.entity(),
                response,
                rawResponse(response),
                AiParseOutcome.DIRECT_SUCCESS
            );
        }
        ChatResponse response = call.chatResponse();
        String content = rawResponse(response);
        return convertWithRepair(content, response, outputConverter, logContext, log);
    }

    private <T> StructuredCallResult<T> convertWithRepair(
        String content,
        ChatResponse response,
        BeanOutputConverter<T> outputConverter,
        String logContext,
        Logger log
    ) {
        try {
            return new StructuredCallResult<>(
                outputConverter.convert(content),
                response,
                content,
                AiParseOutcome.DIRECT_SUCCESS
            );
        } catch (Exception firstError) {
            String repaired = repairUnescapedQuotesInJsonStrings(content);
            if (!repaired.equals(content)) {
                try {
                    T result = outputConverter.convert(repaired);
                    log.warn("{}结构化 JSON 存在未转义引号，已在本地修复后解析成功", logContext);
                    return new StructuredCallResult<>(
                        result,
                        response,
                        content,
                        AiParseOutcome.LOCAL_REPAIR_SUCCESS
                    );
                } catch (Exception repairError) {
                    firstError.addSuppressed(repairError);
                }
            }
            throw new StructuredParseException(content, response, firstError);
        }
    }

    private AiCallObservation toObservation(
        String context,
        String provider,
        String model,
        int attempt,
        AiParseOutcome outcome,
        long attemptStartNanos,
        long invocationStartNanos,
        boolean terminal,
        ChatResponse response,
        String rawResponse,
        Exception error
    ) {
        Usage reportedUsage = response == null || response.getMetadata() == null
            ? null
            : response.getMetadata().getUsage();
        Usage usage = reportedUsage instanceof EmptyUsage ? null : reportedUsage;
        return new AiCallObservation(
            context,
            provider,
            model,
            attempt,
            outcome,
            System.nanoTime() - attemptStartNanos,
            System.nanoTime() - invocationStartNanos,
            terminal,
            usage == null ? null : usage.getPromptTokens(),
            usage == null ? null : usage.getCompletionTokens(),
            usage == null ? null : usage.getTotalTokens(),
            rawResponse,
            error == null ? null : error.getClass().getSimpleName(),
            error == null ? null : sanitizeErrorMessage(error.getMessage()),
            Instant.now()
        );
    }

    private String rawResponse(ChatResponse response) {
        if (response == null || response.getResult() == null || response.getResult().getOutput() == null) {
            return null;
        }
        return response.getResult().getOutput().getText();
    }

    private String repairUnescapedQuotesInJsonStrings(String content) {
        if (content == null || content.isBlank()) {
            return content;
        }
        StringBuilder repaired = new StringBuilder(content.length() + 16);
        boolean inString = false;
        boolean escaping = false;
        for (int i = 0; i < content.length(); i++) {
            char ch = content.charAt(i);
            if (!inString) {
                if (ch == '"') {
                    inString = true;
                }
                repaired.append(ch);
                continue;
            }

            if (escaping) {
                repaired.append(ch);
                escaping = false;
                continue;
            }
            if (ch == '\\') {
                repaired.append(ch);
                escaping = true;
                continue;
            }
            if (ch == '"') {
                if (isLikelyJsonStringTerminator(content, i + 1)) {
                    inString = false;
                    repaired.append(ch);
                } else {
                    repaired.append("\\\"");
                }
                continue;
            }
            repaired.append(ch);
        }
        return repaired.toString();
    }

    private boolean isLikelyJsonStringTerminator(String content, int start) {
        for (int i = start; i < content.length(); i++) {
            char next = content.charAt(i);
            if (Character.isWhitespace(next)) {
                continue;
            }
            return next == ',' || next == '}' || next == ']' || next == ':';
        }
        return true;
    }

    private String buildRetrySystemPrompt(String systemPromptWithFormat, Exception lastError) {
        if (!retryUseRepairPrompt) {
            return systemPromptWithFormat;
        }

        StringBuilder prompt = new StringBuilder(systemPromptWithFormat)
            .append("\n\n");

        if (retryAppendStrictJsonInstruction) {
            prompt.append(STRICT_JSON_INSTRUCTION).append('\n');
        }
        prompt.append("上次输出解析失败，请仅返回合法 JSON。");

        if (includeLastErrorInRetryPrompt && lastError != null && lastError.getMessage() != null) {
            prompt.append("\n上次失败原因：")
                .append(sanitizeErrorMessage(lastError.getMessage()));
        }
        return prompt.toString();
    }

    private String sanitizeErrorMessage(String message) {
        if (message == null) {
            return null;
        }
        String oneLine = message.replace('\n', ' ').replace('\r', ' ').trim();
        if (oneLine.length() > errorMessageMaxLength) {
            return oneLine.substring(0, errorMessageMaxLength) + "...";
        }
        return oneLine;
    }

    private record StructuredCallResult<T>(
        T value,
        ChatResponse response,
        String rawResponse,
        AiParseOutcome outcome
    ) {
    }

    private static final class StructuredParseException extends RuntimeException {

        private final String rawResponse;
        private final ChatResponse response;

        private StructuredParseException(
            String rawResponse,
            ChatResponse response,
            Exception cause
        ) {
            super(cause.getMessage(), cause);
            this.rawResponse = rawResponse;
            this.response = response;
        }

        private String rawResponse() {
            return rawResponse;
        }

        private ChatResponse response() {
            return response;
        }
    }
}
