package com.ctds.did.interfaces.dto;

import com.ctds.did.application.DidDemoSignatureService;
import java.time.LocalDateTime;

/**
 * 演示签名结果视图（WBS-3.1.11 hifi §2.1）：data = 原文 Base64、signature = SM2 DER 签名 Base64、
 * signedAt = 演示期时间；不含任何私钥/材料字段（私钥不出 KMS）。
 */
public record DemoSignatureView(String did, String data, String signature, LocalDateTime signedAt) {

    public static DemoSignatureView from(final DidDemoSignatureService.DemoSignatureResult result) {
        return new DemoSignatureView(result.did(), result.data(), result.signature(), result.signedAt());
    }
}
