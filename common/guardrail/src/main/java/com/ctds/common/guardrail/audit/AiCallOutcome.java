package com.ctds.common.guardrail.audit;

/** B2 AI 调用结果三分：成功 / 被护栏拒绝 / 下游失败。 */
public enum AiCallOutcome {
    SUCCESS,
    DENIED,
    FAILURE
}