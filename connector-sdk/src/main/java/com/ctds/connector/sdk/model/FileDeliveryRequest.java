package com.ctds.connector.sdk.model;

import com.fasterxml.jackson.annotation.JsonIgnore;

/**
 * 文件型交付请求（ADR-023；交付记录端点由交付域承载）。
 *
 * <p>幂等键<b>必填</b>（平台幂等口径 = 发起人 + 键，ADR-007）——重试须复用同一键，
 * SDK 不自动生成（自动生成会破坏重试幂等语义）。</p>
 */
public record FileDeliveryRequest(String contractNo, String fileId, String purpose, String territory,
        @JsonIgnore String idempotencyKey) {
}
