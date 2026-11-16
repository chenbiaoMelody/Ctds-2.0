package com.ctds.common.modelgateway.spi;

import com.ctds.common.modelgateway.api.ChatCompletionRequest;
import com.ctds.common.modelgateway.api.ChatCompletionResponse;

/** B2 供应商适配器实现位（glm/deepseek/qwen/kimi）；V1.0 无真实实现，零外呼。 */
public interface ModelProviderAdapter {

    String providerId();

    ChatCompletionResponse chat(ChatCompletionRequest request);
}
