package com.ctds.space.interfaces.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 策略域请求体（WBS-3.2.5 hifi §1 端点 1/2/4）。
 * 覆盖请求白名单语义（hifi §2/§9 结构错配口径）：仅接受 entryKey/entryValue——携带 redline
 * 等载体外字段不静默忽略（红线标记仅平台面可写），由控制器转 1006C0012
 * （沿 3.2.3 UpdateSpaceRequest @JsonAnySetter 白名单先例）。
 */
public final class PolicyRequests {

    /** 平台条目创建请求（端点 1：键 + 值 + 红线标记，redline 缺省 false）。 */
    public record PlatformPolicyCreateRequest(String entryKey, String entryValue, boolean redline) {
    }

    /** 平台条目变更请求（端点 2：值/红线标记至少一项；键不可变更——无该字段）。 */
    public record PlatformPolicyUpdateRequest(String entryValue, Boolean redline) {
    }

    /** 空间覆盖提交请求（端点 4：entryKey 业务自然键定位，platform_entry_id 由服务端回填）。 */
    public static class PolicyOverrideRequest {

        private String entryKey;
        private String entryValue;
        @JsonIgnore
        private final Set<String> unknownFields = new LinkedHashSet<>();

        public String getEntryKey() {
            return entryKey;
        }

        public void setEntryKey(final String entryKey) {
            this.entryKey = entryKey;
        }

        public String getEntryValue() {
            return entryValue;
        }

        public void setEntryValue(final String entryValue) {
            this.entryValue = entryValue;
        }

        /** 白名单外字段捕获（@JsonAnySetter；只记字段存在性，不回显值——3.2.3 先例）。 */
        @JsonAnySetter
        void captureUnknownField(final String name, final Object value) {
            unknownFields.add(name);
        }

        /** 是否只含白名单字段（结构错配 → 1006C0012，hifi §9）。 */
        public boolean withinWhitelist() {
            return unknownFields.isEmpty();
        }
    }

    private PolicyRequests() {
    }
}
