package com.ctds.example.interfaces;

/**
 * 统一响应结构（ADR-005）：code=0 表示成功；traceId 取网关透传头，缺省 "-"。
 * 错误封套（非 0 code 与异常映射）随 common-错误码组件（WBS 2.4.2）落地。
 */
public record ApiResult<T>(int code, String message, String traceId, T data) {

    public static <T> ApiResult<T> ok(final T data, final String traceId) {
        final String tid = (traceId == null || traceId.isBlank()) ? "-" : traceId;
        return new ApiResult<>(0, "success", tid, data);
    }
}
