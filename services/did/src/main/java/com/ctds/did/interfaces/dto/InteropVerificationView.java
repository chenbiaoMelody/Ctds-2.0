package com.ctds.did.interfaces.dto;

import com.ctds.did.application.DidInteropService;
import java.time.LocalDateTime;

/** 互认验证结果视图（hifi §2）：result（PASS|FAIL|UNAVAILABLE）/ reason（PASS 时为空）/ verifiedAt。 */
public record InteropVerificationView(
        String peerSpace,
        String did,
        String result,
        String reason,
        LocalDateTime verifiedAt) {

    public static InteropVerificationView from(final DidInteropService.InteropVerificationResult result) {
        return new InteropVerificationView(result.peerSpace(), result.did(), result.result().name(),
                result.reason() == null ? null : result.reason().name(), result.verifiedAt());
    }
}
