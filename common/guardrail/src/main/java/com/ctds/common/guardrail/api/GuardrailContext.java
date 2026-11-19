package com.ctds.common.guardrail.api;

/** B2 调用上下文（仅元数据）：缺省或空白字段一律归一为 "-"。 */
public record GuardrailContext(String feature, String actor, String traceId, String provider) {

    private static final String DEFAULT_VALUE = "-";

    public static final GuardrailContext NONE =
            new GuardrailContext(DEFAULT_VALUE, DEFAULT_VALUE, DEFAULT_VALUE, DEFAULT_VALUE);

    public GuardrailContext {
        feature = normalize(feature);
        actor = normalize(actor);
        traceId = normalize(traceId);
        provider = normalize(provider);
    }

    private static String normalize(final String value) {
        return (value == null || value.isBlank()) ? DEFAULT_VALUE : value;
    }
}