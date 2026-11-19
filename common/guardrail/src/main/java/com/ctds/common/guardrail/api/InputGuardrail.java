package com.ctds.common.guardrail.api;

/** B1 输入护栏扩展位：V1.0 仅契约，具体检查算法归 V1.5 C-11.5。 */
@FunctionalInterface
public interface InputGuardrail {

    GuardrailDecision check(GuardrailInput input);
}