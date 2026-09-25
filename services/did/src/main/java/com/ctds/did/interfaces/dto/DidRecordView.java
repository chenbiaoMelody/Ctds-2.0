package com.ctds.did.interfaces.dto;

import com.ctds.did.domain.DidIdentity;
import java.time.LocalDateTime;

/**
 * 签发记录行视图（WBS-3.1.11 hifi §2.1）：主体申请编号/签发序号/DID/记录状态/密钥引用/时间。
 * did 与 keyRef 在"待签发"记录（记录中间态）为空；status 为既有 DidStatus 三值名，
 * 无私钥、无公钥 hex、无文档原文（行为 1 规则 3：接口响应零私钥明文）。
 */
public record DidRecordView(
        String subjectNo,
        int issuanceSeq,
        String did,
        String status,
        String keyRef,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {

    public static DidRecordView from(final DidIdentity identity) {
        return new DidRecordView(identity.subjectNo(), identity.issuanceSeq(), identity.did(),
                identity.status().name(), identity.keyRef(), identity.createdAt(), identity.updatedAt());
    }
}
