package com.ctds.common.guardrail;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ctds.common.guardrail.api.GuardrailContext;
import com.ctds.common.guardrail.api.GuardrailDecision;
import com.ctds.common.guardrail.api.GuardrailDeniedException;
import com.ctds.common.guardrail.api.GuardrailInput;
import com.ctds.common.guardrail.api.GuardrailOutput;
import com.ctds.common.guardrail.api.GuardrailStage;
import com.ctds.common.guardrail.api.GuardrailVerdict;
import com.ctds.common.guardrail.api.InputGuardrail;
import com.ctds.common.guardrail.api.OutputGuardrail;
import com.ctds.common.guardrail.engine.GuardrailChain;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class GuardrailChainTest {

    private static GuardrailInput input() {
        return new GuardrailInput(null, GuardrailContext.NONE);
    }

    private static GuardrailOutput output() {
        return new GuardrailOutput(null, GuardrailContext.NONE);
    }

    @Test
    @DisplayName("T1 空链直通：输入/输出均放行")
    void emptyChainShouldAllow() {
        final GuardrailChain chain = GuardrailChain.empty();
        final GuardrailInput input = input();
        final GuardrailOutput output = output();

        assertEquals(GuardrailDecision.allow(), chain.checkInput(input));
        assertEquals(GuardrailDecision.allow(), chain.checkOutput(output));
        assertEquals("-", chain.checkInput(input).reasonCode());
        assertFalse(chain.checkOutput(output).denied());

        final GuardrailChain registeredAllow = new GuardrailChain(List.of(),
                List.of(item -> GuardrailDecision.allow()));
        assertEquals(GuardrailVerdict.ALLOW, registeredAllow.checkOutput(output).verdict());
    }

    @Test
    @DisplayName("T2 输入拒绝短路：后续输入护栏不再执行")
    void inputDenyShouldShortCircuit() {
        final AtomicInteger secondCalls = new AtomicInteger();
        final AtomicReference<GuardrailInput> seen = new AtomicReference<>();
        final GuardrailInput given = input();
        final GuardrailChain chain = new GuardrailChain(
                List.of(item -> {
                    seen.set(item);
                    return GuardrailDecision.deny("BLOCKED_INPUT");
                }, item -> {
                    secondCalls.incrementAndGet();
                    return GuardrailDecision.allow();
                }),
                List.of());

        final GuardrailDecision decision = chain.checkInput(given);

        assertTrue(decision.denied());
        assertEquals("BLOCKED_INPUT", decision.reasonCode());
        assertEquals(0, secondCalls.get());
        assertSame(given, seen.get());
    }

    @Test
    @DisplayName("T2 输出拒绝短路：后续输出护栏不再执行")
    void outputDenyShouldShortCircuit() {
        final AtomicInteger secondCalls = new AtomicInteger();
        final AtomicReference<GuardrailOutput> seen = new AtomicReference<>();
        final GuardrailOutput given = output();
        final GuardrailChain chain = new GuardrailChain(
                List.of(),
                List.of(item -> {
                    seen.set(item);
                    return GuardrailDecision.deny("BLOCKED_OUTPUT");
                }, item -> {
                    secondCalls.incrementAndGet();
                    return GuardrailDecision.allow();
                }));

        final GuardrailDecision decision = chain.checkOutput(given);

        assertTrue(decision.denied());
        assertEquals("BLOCKED_OUTPUT", decision.reasonCode());
        assertEquals(0, secondCalls.get());
        assertSame(given, seen.get());
    }

    @Test
    @DisplayName("T3 护栏异常 fail-closed：抛异常/返回 null/verdict 为 null 均拒绝")
    void guardrailFailureShouldDenyClosed() {
        final GuardrailChain throwing = new GuardrailChain(
                List.of(item -> {
                    throw new IllegalStateException("boom");
                }),
                List.of());
        final GuardrailChain nullDecision = new GuardrailChain(List.of(item -> null), List.of());
        final GuardrailChain nullVerdict = new GuardrailChain(
                List.of(item -> new GuardrailDecision(null, "UNKNOWN")),
                List.of());

        assertDeniedWithError(throwing.checkInput(input()));
        assertDeniedWithError(nullDecision.checkInput(input()));
        assertDeniedWithError(nullVerdict.checkInput(input()));

        final GuardrailChain outputFailure = new GuardrailChain(
                List.of(),
                List.of(item -> {
                    throw new IllegalArgumentException("output boom");
                }));
        assertDeniedWithError(outputFailure.checkOutput(output()));

        final GuardrailChain outputNullDecision = new GuardrailChain(List.of(), List.of(item -> null));
        assertDeniedWithError(outputNullDecision.checkOutput(output()));

        final GuardrailChain outputNullVerdict = new GuardrailChain(
                List.of(), List.of(item -> new GuardrailDecision(null, "UNKNOWN")));
        assertDeniedWithError(outputNullVerdict.checkOutput(output()));
    }

    @Test
    void allowDecisionShouldUseDashReasonAndDenyShouldDefaultBlankReason() {
        assertEquals("-", GuardrailDecision.allow().reasonCode());
        assertEquals("GUARDRAIL_DENIED", GuardrailDecision.deny(null).reasonCode());
        assertEquals("GUARDRAIL_DENIED", GuardrailDecision.deny("   ").reasonCode());
        assertEquals("CUSTOM", GuardrailDecision.deny("CUSTOM").reasonCode());
        assertTrue(GuardrailDecision.deny("CUSTOM").denied());
        assertFalse(GuardrailDecision.allow().denied());

        final GuardrailDeniedException nullReason = new GuardrailDeniedException(GuardrailStage.INPUT, null);
        assertEquals(GuardrailStage.INPUT, nullReason.stage());
        assertEquals("GUARDRAIL_DENIED", nullReason.reasonCode());

        final GuardrailDeniedException blankReason = new GuardrailDeniedException(GuardrailStage.OUTPUT, "  ");
        assertEquals(GuardrailStage.OUTPUT, blankReason.stage());
        assertEquals("GUARDRAIL_DENIED", blankReason.reasonCode());
    }

    @Test
    void constructorShouldRejectNullListsAndNullElements() {
        assertThrows(IllegalArgumentException.class, () -> new GuardrailChain(null, List.of()));
        assertThrows(IllegalArgumentException.class, () -> new GuardrailChain(List.of(), null));

        final List<InputGuardrail> inputGuardrails = new ArrayList<>();
        inputGuardrails.add(null);
        assertThrows(IllegalArgumentException.class, () -> new GuardrailChain(inputGuardrails, List.of()));

        final List<OutputGuardrail> outputGuardrails = new ArrayList<>();
        outputGuardrails.add(null);
        assertThrows(IllegalArgumentException.class, () -> new GuardrailChain(List.of(), outputGuardrails));
    }

    @Test
    void constructorShouldCopyGuardrailsDefensively() {
        final List<InputGuardrail> guardrails = new ArrayList<>();
        guardrails.add(item -> GuardrailDecision.allow());
        final GuardrailChain chain = new GuardrailChain(guardrails, List.of());

        guardrails.add(item -> GuardrailDecision.deny("LATE"));

        assertEquals(GuardrailDecision.allow(), chain.checkInput(input()));
    }

    private static void assertDeniedWithError(final GuardrailDecision decision) {
        assertEquals(GuardrailVerdict.DENY, decision.verdict());
        assertEquals("GUARDRAIL_ERROR", decision.reasonCode());
    }
}