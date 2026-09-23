package com.ctds.did.interfaces.dto;

import com.ctds.did.application.DidResolutionService;
import com.fasterxml.jackson.databind.JsonNode;

/** 解析结果视图（hifi §2）：did/status（ACTIVE|REVOKED）/document（公开要素，不含 L4 与密钥引用）。 */
public record ResolutionView(String did, String status, JsonNode document) {

    public static ResolutionView from(final DidResolutionService.ResolutionResult result) {
        return new ResolutionView(result.did(), result.status().name(), result.document());
    }
}