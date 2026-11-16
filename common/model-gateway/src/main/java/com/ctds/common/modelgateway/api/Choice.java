package com.ctds.common.modelgateway.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

/** B1 候选结果（finishReason 为字符串不做枚举，如 stop）。 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Choice(Integer index, ChatMessage message, String finishReason) {
}
