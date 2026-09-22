package com.ctds.did.interfaces.dto;

import com.ctds.did.application.DidIssuanceService;
import java.time.LocalDateTime;

/** 签发结果视图（hifi §4.1）：did/status/keyRef/issuedAt，PENDING 时前三项为空。 */
public record IssuanceView(String did, String status, String keyRef, LocalDateTime issuedAt) {

    public static IssuanceView from(final DidIssuanceService.IssuanceResult result) {
        return new IssuanceView(result.did(), result.status().name(), result.keyRef(), result.issuedAt());
    }
}
