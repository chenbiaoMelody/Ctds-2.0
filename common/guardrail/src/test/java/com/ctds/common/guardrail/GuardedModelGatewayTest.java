package com.ctds.common.guardrail;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ctds.common.guardrail.api.GuardrailContext;
import com.ctds.common.guardrail.api.GuardrailDecision;
import com.ctds.common.guardrail.api.GuardrailDeniedException;
import com.ctds.common.guardrail.api.GuardrailStage;
import com.ctds.common.guardrail.audit.AiCallLog;
import com.ctds.common.guardrail.audit.AiCallLogRecorder;
import com.ctds.common.guardrail.audit.AiCallOutcome;
import com.ctds.common.guardrail.engine.GuardrailChain;
import com.ctds.common.guardrail.gateway.GuardedModelGateway;
import com.ctds.common.modelgateway.api.ChatCompletionRequest;
import com.ctds.common.modelgateway.api.ChatCompletionResponse;
import com.ctds.common.modelgateway.api.ChatMessage;
import com.ctds.common.modelgateway.api.Choice;
import com.ctds.common.modelgateway.api.ModelGateway;
import com.ctds.common.modelgateway.api.Usage;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class GuardedModelGatewayTest {

    @Test
    @DisplayName("T2 输入拒绝：下游零调用、stage=INPUT、原因原样、记录 DENIED")
    void inputDenyShouldSkipDelegateAndRecordDenied() {
        final FakeGateway delegate = new FakeGateway(response(1, 2), null);
        final CapturingRecorder recorder = new CapturingRecorder();
        final GuardedModelGateway gateway = new GuardedModelGateway(delegate,
                new GuardrailChain(List.of(input -> GuardrailDecision.deny("PII_DETECTED")), List.of()),
                recorder);

        final GuardrailDeniedException ex = assertThrows(GuardrailDeniedException.class,
                () -> gateway.chat(request(), context()));

        assertEquals(GuardrailStage.INPUT, ex.stage());
        assertEquals("PII_DETECTED", ex.reasonCode());
        assertEquals(0, delegate.calls);
        assertEquals(1, recorder.logs.size());
        final AiCallLog log = recorder.logs.get(0);
        assertEquals(AiCallOutcome.DENIED, log.outcome());
        assertEquals(0, log.promptTokens());
        assertEquals(0, log.completionTokens());
        assertEquals(GuardrailDecision.deny("PII_DETECTED").verdict(), log.guardrailVerdict());
        assertEquals("PII_DETECTED", log.guardrailReason());
    }

    @Test
    @DisplayName("T4 输出拒绝：下游已调用、响应不返回、stage=OUTPUT、tokens 取响应 usage")
    void outputDenyShouldHideResponseAndRecordDenied() {
        final FakeGateway delegate = new FakeGateway(response(12, 34), null);
        final CapturingRecorder recorder = new CapturingRecorder();
        final GuardedModelGateway gateway = new GuardedModelGateway(delegate,
                new GuardrailChain(List.of(), List.of(output -> GuardrailDecision.deny("UNSAFE_OUTPUT"))),
                recorder);

        final GuardrailDeniedException ex = assertThrows(GuardrailDeniedException.class,
                () -> gateway.chat(request(), context()));

        assertEquals(GuardrailStage.OUTPUT, ex.stage());
        assertEquals("UNSAFE_OUTPUT", ex.reasonCode());
        assertEquals(1, delegate.calls);
        assertEquals(1, recorder.logs.size());
        final AiCallLog log = recorder.logs.get(0);
        assertEquals(AiCallOutcome.DENIED, log.outcome());
        assertEquals(12, log.promptTokens());
        assertEquals(34, log.completionTokens());
        assertEquals("UNSAFE_OUTPUT", log.guardrailReason());
    }

    @Test
    @DisplayName("T5 成功路径：下游恰一次、记录 SUCCESS、chat(request) 使用 NONE 上下文")
    void successShouldRecordOnceWithUsageAndLatency() {
        final FakeGateway delegate = new FakeGateway(response(7, 9), null);
        final CapturingRecorder recorder = new CapturingRecorder();
        final GuardedModelGateway gateway = new GuardedModelGateway(delegate, GuardrailChain.empty(), recorder);

        final ChatCompletionResponse result = gateway.chat(request());

        assertNotNull(result);
        assertEquals(1, delegate.calls);
        assertEquals(1, recorder.logs.size());
        final AiCallLog log = recorder.logs.get(0);
        assertEquals(AiCallOutcome.SUCCESS, log.outcome());
        assertEquals(7, log.promptTokens());
        assertEquals(9, log.completionTokens());
        assertTrue(log.latencyMs() >= 0);
        assertEquals("-", log.traceId());
        assertEquals("-", log.feature());
        assertEquals("-", log.actor());
        assertEquals("-", log.provider());
        assertEquals("test-model", log.model());
        assertEquals(GuardrailDecision.allow().verdict(), log.guardrailVerdict());
    }

    @Test
    @DisplayName("T6 下游异常：同一实例原样抛出、记录 FAILURE、errorCode=null")
    void delegateFailureShouldRethrowSameInstanceAndRecordFailure() {
        final IllegalStateException failure = new IllegalStateException("provider down");
        final FakeGateway delegate = new FakeGateway(null, failure);
        final CapturingRecorder recorder = new CapturingRecorder();
        final GuardedModelGateway gateway = new GuardedModelGateway(delegate, GuardrailChain.empty(), recorder);

        final RuntimeException thrown = assertThrows(IllegalStateException.class,
                () -> gateway.chat(request(), context()));

        assertSame(failure, thrown);
        assertEquals(1, recorder.logs.size());
        final AiCallLog log = recorder.logs.get(0);
        assertEquals(AiCallOutcome.FAILURE, log.outcome());
        assertEquals(0, log.promptTokens());
        assertEquals(0, log.completionTokens());
        assertNull(log.errorCode());
        assertEquals(GuardrailDecision.allow().verdict(), log.guardrailVerdict());
    }

    @Test
    @DisplayName("T7 记录器异常隔离：成功/拒绝/下游异常语义均不改变")
    void recorderFailureShouldNotChangeOutcome() {
        final ExplodingRecorder successRecorder = new ExplodingRecorder();
        final FakeGateway successDelegate = new FakeGateway(response(1, 1), null);
        final GuardedModelGateway successGateway = new GuardedModelGateway(
                successDelegate, GuardrailChain.empty(), successRecorder);
        assertNotNull(successGateway.chat(request(), context()));
        assertEquals(1, successRecorder.calls);

        final ExplodingRecorder deniedRecorder = new ExplodingRecorder();
        final FakeGateway deniedDelegate = new FakeGateway(response(1, 1), null);
        final GuardedModelGateway deniedGateway = new GuardedModelGateway(deniedDelegate,
                new GuardrailChain(List.of(input -> GuardrailDecision.deny("BLOCKED")), List.of()),
                deniedRecorder);
        assertThrows(GuardrailDeniedException.class, () -> deniedGateway.chat(request(), context()));
        assertEquals(0, deniedDelegate.calls);
        assertEquals(1, deniedRecorder.calls);

        final ExplodingRecorder failureRecorder = new ExplodingRecorder();
        final IllegalStateException failure = new IllegalStateException("boom");
        final FakeGateway failureDelegate = new FakeGateway(null, failure);
        final GuardedModelGateway failureGateway = new GuardedModelGateway(
                failureDelegate, GuardrailChain.empty(), failureRecorder);
        final RuntimeException thrown = assertThrows(IllegalStateException.class,
                () -> failureGateway.chat(request(), context()));
        assertSame(failure, thrown);
        assertEquals(1, failureRecorder.calls);
    }

    @Test
    @DisplayName("T8 记录器默认与上下文归一：null recorder/chain/context/usage 均有安全默认")
    void nullRecorderAndContextShouldFallBackToDefaults() {
        final FakeGateway delegate = new FakeGateway(response(null, null), null);
        final CapturingRecorder recorder = new CapturingRecorder();
        final GuardedModelGateway gateway = new GuardedModelGateway(delegate, null, recorder);

        assertNotNull(gateway.chat(request(), new GuardrailContext(null, " ", "\t", "")));
        final AiCallLog log = recorder.logs.get(0);
        assertEquals("-", log.traceId());
        assertEquals("-", log.feature());
        assertEquals("-", log.actor());
        assertEquals("-", log.provider());
        assertEquals(0, log.promptTokens());
        assertEquals(0, log.completionTokens());

        final GuardedModelGateway noRecorder = new GuardedModelGateway(delegate, null, null);
        assertNotNull(noRecorder.chat(request(), null));
        assertEquals(2, delegate.calls);
    }

    @Test
    @DisplayName("§6 delegate Error 不捕获、不审计、直接传播")
    void delegateErrorShouldPropagateWithoutAudit() {
        final AssertionError error = new AssertionError("fatal");
        final ModelGateway delegate = request -> {
            throw error;
        };
        final CapturingRecorder recorder = new CapturingRecorder();
        final GuardedModelGateway gateway = new GuardedModelGateway(delegate, GuardrailChain.empty(), recorder);

        final AssertionError thrown = assertThrows(AssertionError.class,
                () -> gateway.chat(request(), context()));

        assertSame(error, thrown);
        assertEquals(0, recorder.logs.size());
    }

    @Test
    @DisplayName("§6 null request/response 与负数 usage：model 归一、tokens 归 0")
    void nullRequestAndNegativeUsageShouldFallBackToZero() {
        final CapturingRecorder recorder = new CapturingRecorder();
        final GuardedModelGateway gateway = new GuardedModelGateway(
                new FakeGateway(null, null), GuardrailChain.empty(), recorder);

        assertNull(gateway.chat(null, null));
        final AiCallLog nullLog = recorder.logs.get(0);
        assertEquals("-", nullLog.model());
        assertEquals(0, nullLog.promptTokens());
        assertEquals(0, nullLog.completionTokens());

        final CapturingRecorder negativeRecorder = new CapturingRecorder();
        final GuardedModelGateway negativeGateway = new GuardedModelGateway(
                new FakeGateway(response(-5, -7), null), GuardrailChain.empty(), negativeRecorder);
        assertNotNull(negativeGateway.chat(request(), context()));
        assertEquals(0, negativeRecorder.logs.get(0).promptTokens());
        assertEquals(0, negativeRecorder.logs.get(0).completionTokens());
    }

    @Test
    void constructorShouldRejectNullDelegate() {
        assertThrows(IllegalArgumentException.class,
                () -> new GuardedModelGateway(null, GuardrailChain.empty(), null));
    }

    private static ChatCompletionRequest request() {
        return new ChatCompletionRequest("test-model",
                List.of(new ChatMessage(ChatMessage.ROLE_USER, "hello")), null, null, null);
    }

    private static GuardrailContext context() {
        return new GuardrailContext("feature-a", "actor-a", "trace-a", "provider-a");
    }

    private static ChatCompletionResponse response(final Integer promptTokens, final Integer completionTokens) {
        final Usage usage = new Usage(promptTokens, completionTokens, null);
        return new ChatCompletionResponse("resp-1", ChatCompletionResponse.OBJECT_CHAT_COMPLETION, 1L,
                "test-model", List.of(new Choice(0, new ChatMessage(ChatMessage.ROLE_ASSISTANT, "hi"), "stop")),
                usage);
    }

    private static final class FakeGateway implements ModelGateway {

        private final ChatCompletionResponse response;
        private final RuntimeException failure;
        private int calls;

        private FakeGateway(final ChatCompletionResponse response, final RuntimeException failure) {
            this.response = response;
            this.failure = failure;
        }

        @Override
        public ChatCompletionResponse chat(final ChatCompletionRequest request) {
            calls++;
            if (failure != null) {
                throw failure;
            }
            return response;
        }
    }

    private static final class CapturingRecorder implements AiCallLogRecorder {

        private final List<AiCallLog> logs = new ArrayList<>();

        @Override
        public void record(final AiCallLog log) {
            logs.add(log);
        }
    }

    private static final class ExplodingRecorder implements AiCallLogRecorder {

        private int calls;

        @Override
        public void record(final AiCallLog log) {
            calls++;
            throw new IllegalStateException("recorder down");
        }
    }
}