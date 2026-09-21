package com.ctds.did.interfaces.dto;

import com.ctds.did.application.DidIssuanceService;
import java.time.LocalDateTime;

/** 吊销结果视图（hifi §4.1）。 */
public record RevocationView(String did, String status, LocalDateTime revokedAt) {

    public static RevocationView from(final DidIssuanceService.RevocationResult result) {
        return new RevocationView(result.did(), result.status().name(), result.revokedAt());
    }
}
