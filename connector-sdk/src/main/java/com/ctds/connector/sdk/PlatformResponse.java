package com.ctds.connector.sdk;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 平台统一响应同构解析（对齐 common {@code ApiResult}：字段 code / message / traceId / data，
 * {@code code="0"} 表示成功、其余为 9 位错误码；宽松映射——未知字段忽略，字段级对齐归 3.6.8 集成验证）。
 *
 * @param <T> data 视图类型
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PlatformResponse<T>(String code, String message, String traceId, T data) {

    /** 成功判定：code = "0"（平台口径）。 */
    public boolean isSuccess() {
        return "0".equals(code);
    }
}
