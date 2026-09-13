package com.ctds.kms.domain;

import java.time.LocalDateTime;

/**
 * 密钥版本记录：材料列只存根密钥 SM4 信封的 Base64（落库无明文，规格行为 1）。
 * materialCipher 仅在领域与基础设施间流转，接口层只透出给内部供给端点。
 */
public record KeyVersion(String keyRef, int version, String materialCipher, LocalDateTime createdAt) {
}
