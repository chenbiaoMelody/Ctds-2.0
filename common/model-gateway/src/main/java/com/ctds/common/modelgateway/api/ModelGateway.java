package com.ctds.common.modelgateway.api;

/** B1 统一调用入口——未来所有 AI 调用唯一入口（3.10.3 审计/护栏挂点）；V1.0 无实现类。 */
public interface ModelGateway {

    ChatCompletionResponse chat(ChatCompletionRequest request);
}
