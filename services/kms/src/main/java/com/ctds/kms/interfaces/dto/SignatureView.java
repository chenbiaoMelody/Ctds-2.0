package com.ctds.kms.interfaces.dto;

import java.util.Base64;

/** 签名视图（编号 + Base64 DER 签名；无私钥/材料字段）。 */
public record SignatureView(String keyRef, String signature) {

    public static SignatureView from(final String keyRef, final byte[] signature) {
        return new SignatureView(keyRef, Base64.getEncoder().encodeToString(signature));
    }
}
