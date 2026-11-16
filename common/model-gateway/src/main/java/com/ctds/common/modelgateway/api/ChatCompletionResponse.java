package com.ctds.common.modelgateway.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.util.List;

/** B1 OpenAI 兼容响应契约（C-11.1；hifi §3）。 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ChatCompletionResponse(String id, String object, Long created, String model,
                                     List<Choice> choices, Usage usage) {

    public static final String OBJECT_CHAT_COMPLETION = "chat.completion";
}
