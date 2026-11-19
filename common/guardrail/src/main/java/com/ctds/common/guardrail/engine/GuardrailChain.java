package com.ctds.common.guardrail.engine;

import com.ctds.common.guardrail.api.GuardrailDecision;
import com.ctds.common.guardrail.api.GuardrailInput;
import com.ctds.common.guardrail.api.GuardrailOutput;
import com.ctds.common.guardrail.api.InputGuardrail;
import com.ctds.common.guardrail.api.OutputGuardrail;
import java.util.List;

/** B1 护栏执行链：注册顺序执行、首个 DENY 短路、空链直通、护栏异常 fail-closed。 */
public final class GuardrailChain {

    private static final String GUARDRAIL_ERROR = "GUARDRAIL_ERROR";

    private final List<InputGuardrail> inputGuardrails;
    private final List<OutputGuardrail> outputGuardrails;

    public GuardrailChain(final List<InputGuardrail> inputGuardrails,
            final List<OutputGuardrail> outputGuardrails) {
        this.inputGuardrails = copyOf(inputGuardrails, "inputGuardrails");
        this.outputGuardrails = copyOf(outputGuardrails, "outputGuardrails");
    }

    public static GuardrailChain empty() {
        return new GuardrailChain(List.of(), List.of());
    }

    public GuardrailDecision checkInput(final GuardrailInput input) {
        for (final InputGuardrail guardrail : inputGuardrails) {
            final GuardrailDecision decision;
            try {
                decision = guardrail.check(input);
            } catch (final RuntimeException ex) {
                return GuardrailDecision.deny(GUARDRAIL_ERROR);
            }
            if (decision == null || decision.verdict() == null) {
                return GuardrailDecision.deny(GUARDRAIL_ERROR);
            }
            if (decision.denied()) {
                return decision;
            }
        }
        return GuardrailDecision.allow();
    }

    public GuardrailDecision checkOutput(final GuardrailOutput output) {
        for (final OutputGuardrail guardrail : outputGuardrails) {
            final GuardrailDecision decision;
            try {
                decision = guardrail.check(output);
            } catch (final RuntimeException ex) {
                return GuardrailDecision.deny(GUARDRAIL_ERROR);
            }
            if (decision == null || decision.verdict() == null) {
                return GuardrailDecision.deny(GUARDRAIL_ERROR);
            }
            if (decision.denied()) {
                return decision;
            }
        }
        return GuardrailDecision.allow();
    }

    private static <T> List<T> copyOf(final List<T> guardrails, final String name) {
        if (guardrails == null) {
            throw new IllegalArgumentException(name + " must not be null");
        }
        for (final T guardrail : guardrails) {
            if (guardrail == null) {
                throw new IllegalArgumentException(name + " must not contain null elements");
            }
        }
        return List.copyOf(guardrails);
    }
}