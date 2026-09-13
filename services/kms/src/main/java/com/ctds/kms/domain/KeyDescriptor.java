package com.ctds.kms.domain;

import java.time.LocalDateTime;

/** 密钥描述符（元数据，不含密钥材料）：编号 + 状态 + 当前版本。 */
public record KeyDescriptor(String keyRef, KeyStatus status, int currentVersion, LocalDateTime createdAt) {
}
