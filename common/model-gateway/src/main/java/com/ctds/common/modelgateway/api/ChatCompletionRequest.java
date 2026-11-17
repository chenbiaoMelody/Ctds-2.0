package com.ctds.common.modelgateway.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.util.List;

/** B1 OpenAI 兼容请求契约（C-11.1；hifi §3，最小常用集，stream 仅字段位）。 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ChatCompletionRequest(String model, List<ChatMessage> messages, Double temperature,
                                    Integer maxTokens, Boolean stream) {
}
