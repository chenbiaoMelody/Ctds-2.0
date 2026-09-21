package com.ctds.did.domain;

import java.time.LocalDateTime;

/**
 * DID 身份注册表行（唯一事实源，WBS-3.1.8 hifi §2.1）。
 * documentJson 只含公开要素（不含私钥/密钥引用/L4）；keyRef 为管理面留痕用，非公开。
 * guardKey 唯一性守卫列：非吊销行 = subject_no（保证一主体同期唯一有效/待签发身份），吊销行 = NULL。
 */
public record DidIdentity(
        Long id,
        String subjectNo,
        int issuanceSeq,
        String did,
        DidStatus status,
        String publicKeyHex,
        String keyRef,
        String documentJson,
        String guardKey,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
}
