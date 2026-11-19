package com.ctds.common.guardrail.api;

import com.ctds.common.modelgateway.api.ChatCompletionRequest;

/** B1 输入护栏入参：模型请求 + 调用上下文。 */
public record GuardrailInput(ChatCompletionRequest request, GuardrailContext context) {
}