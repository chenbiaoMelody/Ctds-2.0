package com.ctds.common.guardrail.api;

/** B1 护栏拒绝异常：V1.0 不映射平台错误码（无对外端点），V1.5 再行映射。 */
public final class GuardrailDeniedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final GuardrailStage stage;
    private final String reasonCode;

    public GuardrailDeniedException(final GuardrailStage stage, final String reasonCode) {
        super("guardrail denied: stage=" + stage + ", reason=" + reasonCode);
        this.stage = stage;
        this.reasonCode = (reasonCode == null || reasonCode.isBlank())
                ? GuardrailDecision.DEFAULT_DENY_REASON : reasonCode;
    }

    public GuardrailStage stage() {
        return stage;
    }

    public String reasonCode() {
        return reasonCode;
    }
}