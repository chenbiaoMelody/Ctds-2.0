package com.ctds.common.guardrail.audit;

import com.ctds.common.guardrail.api.GuardrailVerdict;

/**
 * B2 AI 调用审计记录：仅元数据，零 prompt / 响应原文 / 密钥 / 个人信息（CAT-01 / L2）。
 * errorCode 保持可空（hifi §3 步骤 5 / T6）；其余字符串缺省归一为 "-"。
 */
public record AiCallLog(
        String traceId,
        String feature,
        String actor,
        String provider,
        String model,
        int promptTokens,
        int completionTokens,
        int latencyMs,
        AiCallOutcome outcome,
        String errorCode,
        GuardrailVerdict guardrailVerdict,
        String guardrailReason) {

    private static final String DEFAULT_VALUE = "-";

    public AiCallLog {
        traceId = normalizeText(traceId);
        feature = normalizeText(feature);
        actor = normalizeText(actor);
        provider = normalizeText(provider);
        model = normalizeText(model);
        promptTokens = Math.max(0, promptTokens);
        completionTokens = Math.max(0, completionTokens);
        latencyMs = Math.max(0, latencyMs);
        if (outcome == null) {
            throw new IllegalArgumentException("outcome must not be null");
        }
        errorCode = (errorCode == null || errorCode.isBlank()) ? null : errorCode;
        guardrailReason = normalizeText(guardrailReason);
    }

    private static String normalizeText(final String value) {
        return (value == null || value.isBlank()) ? DEFAULT_VALUE : value;
    }
}