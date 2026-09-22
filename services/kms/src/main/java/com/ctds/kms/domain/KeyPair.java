package com.ctds.kms.domain;

import java.time.LocalDateTime;

/**
 * SM2 密钥对托管记录（WBS-3.1.8，hifi §2.4）：描述符（类型 + 公钥）在 kms_key，
 * 私钥 D 值信封在 kms_key_version.material_cipher。私钥只存 KMS，接口零私钥出站（规格行为 1）。
 */
public record KeyPair(String keyRef, String keyType, String publicKeyHex, String privateCipher,
                      LocalDateTime createdAt) {
}
