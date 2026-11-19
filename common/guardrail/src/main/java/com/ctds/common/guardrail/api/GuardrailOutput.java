package com.ctds.common.guardrail.api;

import com.ctds.common.modelgateway.api.ChatCompletionResponse;

/** B1 输出护栏入参：模型响应 + 调用上下文。 */
public record GuardrailOutput(ChatCompletionResponse response, GuardrailContext context) {
}