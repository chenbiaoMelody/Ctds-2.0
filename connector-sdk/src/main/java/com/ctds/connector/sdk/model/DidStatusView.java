package com.ctds.connector.sdk.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** DID 状态视图（可解析、状态有效——行为 3 标准 1）。 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DidStatusView(String did, String status) {
}
