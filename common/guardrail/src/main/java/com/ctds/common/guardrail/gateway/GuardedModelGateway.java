package com.ctds.common.guardrail.gateway;

import com.ctds.common.guardrail.api.GuardrailContext;
import com.ctds.common.guardrail.api.GuardrailDecision;
import com.ctds.common.guardrail.api.GuardrailDeniedException;
import com.ctds.common.guardrail.api.GuardrailInput;
import com.ctds.common.guardrail.api.GuardrailOutput;
import com.ctds.common.guardrail.api.GuardrailStage;
import com.ctds.common.guardrail.audit.AiCallLog;
import com.ctds.common.guardrail.audit.AiCallLogRecorder;
import com.ctds.common.guardrail.audit.AiCallOutcome;
import com.ctds.common.guardrail.engine.GuardrailChain;
import com.ctds.common.modelgateway.api.ChatCompletionRequest;
import com.ctds.common.modelgateway.api.ChatCompletionResponse;
import com.ctds.common.modelgateway.api.ModelGateway;
import com.ctds.common.modelgateway.api.Usage;

/** B1 装饰器：调用前执行输入护栏、调用后执行输出护栏；审计记录绝不打断主流程。 */
public final class GuardedModelGateway implements ModelGateway {

    private static final String DEFAULT_VALUE = "-";

    private final ModelGateway delegate;
    private final GuardrailChain chain;
    private final AiCallLogRecorder recorder;

    public GuardedModelGateway(final ModelGateway delegate, final GuardrailChain chain,
            final AiCallLogRecorder recorder) {
        if (delegate == null) {
            throw new IllegalArgumentException("delegate must not be null");
        }
        this.delegate = delegate;
        this.chain = (chain == null) ? GuardrailChain.empty() : chain;
        this.recorder = (recorder == null) ? AiCallLogRecorder.NOOP : recorder;
    }

    @Override
    public ChatCompletionResponse chat(final ChatCompletionRequest request) {
        return chat(request, GuardrailContext.NONE);
    }

    public ChatCompletionResponse chat(final ChatCompletionRequest request, final GuardrailContext context) {
        final GuardrailContext effectiveContext = (context == null) ? GuardrailContext.NONE : context;
        final long startNanos = System.nanoTime();

        final GuardrailDecision inputDecision = chain.checkInput(new GuardrailInput(request, effectiveContext));
        if (inputDecision.denied()) {
            record(effectiveContext, request, 0, 0, startNanos, AiCallOutcome.DENIED, inputDecision);
            throw new GuardrailDeniedException(GuardrailStage.INPUT, inputDecision.reasonCode());
        }

        final ChatCompletionResponse response;
        try {
            response = delegate.chat(request);
        } catch (final RuntimeException ex) {
            record(effectiveContext, request, 0, 0, startNanos, AiCallOutcome.FAILURE, GuardrailDecision.allow());
            throw ex;
        }

        final GuardrailDecision outputDecision = chain.checkOutput(new GuardrailOutput(response, effectiveContext));
        if (outputDecision.denied()) {
            record(effectiveContext, request, promptTokens(response), completionTokens(response),
                    startNanos, AiCallOutcome.DENIED, outputDecision);
            throw new GuardrailDeniedException(GuardrailStage.OUTPUT, outputDecision.reasonCode());
        }

        record(effectiveContext, request, promptTokens(response), completionTokens(response),
                startNanos, AiCallOutcome.SUCCESS, GuardrailDecision.allow());
        return response;
    }

    private void record(final GuardrailContext context, final ChatCompletionRequest request,
            final int promptTokens, final int completionTokens, final long startNanos,
            final AiCallOutcome outcome, final GuardrailDecision decision) {
        final AiCallLog log = new AiCallLog(
                context.traceId(),
                context.feature(),
                context.actor(),
                context.provider(),
                (request == null) ? DEFAULT_VALUE : request.model(),
                promptTokens,
                completionTokens,
                elapsedMs(startNanos),
                outcome,
                null,
                decision.verdict(),
                decision.reasonCode());
        try {
            recorder.record(log);
        } catch (final RuntimeException ignored) {
            // 审计铁律：记录器异常绝不改变调用结果；Error 属致命错误，按 hifi §6 / E-04 不捕获。
        }
    }

    private static int promptTokens(final ChatCompletionResponse response) {
        return tokenValue(response, true);
    }

    private static int completionTokens(final ChatCompletionResponse response) {
        return tokenValue(response, false);
    }

    private static int tokenValue(final ChatCompletionResponse response, final boolean prompt) {
        if (response == null || response.usage() == null) {
            return 0;
        }
        final Usage usage = response.usage();
        final Integer value = prompt ? usage.promptTokens() : usage.completionTokens();
        return (value == null || value < 0) ? 0 : value;
    }

    private static int elapsedMs(final long startNanos) {
        final long elapsed = (System.nanoTime() - startNanos) / 1_000_000L;
        if (elapsed < 0) {
            return 0;
        }
        return (elapsed > Integer.MAX_VALUE) ? Integer.MAX_VALUE : (int) elapsed;
    }
}