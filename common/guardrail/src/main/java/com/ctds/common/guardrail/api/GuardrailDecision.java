package com.ctds.common.guardrail.api;

/** B1 护栏判定结果：拒绝时携带可读原因码；放行原因码固定为 "-"。 */
public record GuardrailDecision(GuardrailVerdict verdict, String reasonCode) {

    public static final String ALLOW_REASON = "-";
    public static final String DEFAULT_DENY_REASON = "GUARDRAIL_DENIED";

    public GuardrailDecision {
        if (verdict == GuardrailVerdict.ALLOW) {
            reasonCode = ALLOW_REASON;
        } else if (verdict == GuardrailVerdict.DENY) {
            reasonCode = normalizeReason(reasonCode);
        }
    }

    public static GuardrailDecision allow() {
        return new GuardrailDecision(GuardrailVerdict.ALLOW, ALLOW_REASON);
    }

    public static GuardrailDecision deny(final String reasonCode) {
        return new GuardrailDecision(GuardrailVerdict.DENY, reasonCode);
    }

    public boolean denied() {
        return verdict == GuardrailVerdict.DENY;
    }

    private static String normalizeReason(final String reasonCode) {
        return (reasonCode == null || reasonCode.isBlank()) ? DEFAULT_DENY_REASON : reasonCode;
    }
}