package com.ctds.connector.sdk.model;

import com.fasterxml.jackson.annotation.JsonIgnore;

/**
 * 使用执行请求（行为 5）：凭据三件必填（合约编号 / 用途 / 域——网关交付链判定输入）；
 * 幂等键缺省时 SDK 自动生成。
 */
public record UsageRequest(String contractNo, String purpose, String territory,
        @JsonIgnore String idempotencyKey) {
}
