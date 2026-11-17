package com.ctds.common.modelgateway.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

/** B1 token 用量（V1.0 仅契约字段位，无计量对接）。 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Usage(Integer promptTokens, Integer completionTokens, Integer totalTokens) {
}
