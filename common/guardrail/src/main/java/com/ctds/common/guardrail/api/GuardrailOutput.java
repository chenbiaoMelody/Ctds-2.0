package com.ctds.common.guardrail.api;

import com.ctds.common.modelgateway.api.ChatCompletionResponse;

/** B1 输出护栏入参：模型响应 + 调用上下文；护栏实现须视入参为只读，禁止打印/记录原文（hifi §9 E-05）。 */
public record GuardrailOutput(ChatCompletionResponse response, GuardrailContext context) {
}