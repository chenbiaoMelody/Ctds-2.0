package com.ctds.space.interfaces.dto;

/**
 * 策略域请求体（WBS-3.2.5 hifi §1 端点 1/2/4；覆盖请求不含 redline 字段——载体红线仅平台面可写，
 * 结构上不可达，hifi §2 结构错配口径）。
 */
public final class PolicyRequests {

    /** 平台条目创建请求（端点 1：键 + 值 + 红线标记，redline 缺省 false）。 */
    public record PlatformPolicyCreateRequest(String entryKey, String entryValue, boolean redline) {
    }

    /** 平台条目变更请求（端点 2：值/红线标记至少一项；键不可变更——无该字段）。 */
    public record PlatformPolicyUpdateRequest(String entryValue, Boolean redline) {
    }

    /** 空间覆盖提交请求（端点 4：entryKey 业务自然键定位，platform_entry_id 由服务端回填）。 */
    public record PolicyOverrideRequest(String entryKey, String entryValue) {
    }

    private PolicyRequests() {
    }
}
