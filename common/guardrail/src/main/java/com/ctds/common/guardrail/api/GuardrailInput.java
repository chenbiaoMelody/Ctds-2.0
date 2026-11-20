package com.ctds.common.guardrail.api;

import com.ctds.common.modelgateway.api.ChatCompletionRequest;

/** B1 输入护栏入参：模型请求 + 调用上下文；护栏实现须视入参为只读，禁止打印/记录原文（hifi §9 E-05）。 */
public record GuardrailInput(ChatCompletionRequest request, GuardrailContext context) {
}