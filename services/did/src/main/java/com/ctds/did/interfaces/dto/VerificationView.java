package com.ctds.did.interfaces.dto;

import com.ctds.did.application.DidVerificationService;
import java.time.LocalDateTime;

/** 验证结果视图（hifi §2）：result（PASS|FAIL|UNAVAILABLE）/reason（PASS 时为空）/verifiedAt。 */
public record VerificationView(String did, String result, String reason, LocalDateTime verifiedAt) {

    public static VerificationView from(final DidVerificationService.VerificationResult result) {
        return new VerificationView(result.did(), result.result().name(),
                result.reason() == null ? null : result.reason().name(), result.verifiedAt());
    }
}