package com.ctds.kms.interfaces.dto;

import com.ctds.kms.application.KeyPairService;
import java.time.LocalDateTime;

/** SM2 密钥对创建视图（编号 + 公钥 + 时间；无私钥/材料字段）。 */
public record KeyPairView(String keyRef, String publicKeyHex, LocalDateTime createdAt) {

    public static KeyPairView from(final KeyPairService.KeyPairCreated created) {
        return new KeyPairView(created.keyRef(), created.publicKeyHex(), created.createdAt());
    }
}
