package com.ctds.common.api;

import java.util.regex.Pattern;
import org.slf4j.MDC;

/**
 * 统一响应结构（ADR-005）：code="0" 表示成功，其余为 9 位错误码（ADR-005 §3.3）；
 * traceId 经净化（仅字母数字与 . _ -，最长 64，否则 "-"），来源统一为 MDC（由 TraceIdFilter 写入）。
 * 错误封套由 GlobalExceptionHandler 统一产出；封套 traceId 读取统一走 currentTraceId()。
 */
public record ApiResult<T>(String code, String message, String traceId, T data) {

    /** MDC 中链路追踪键；TraceIdFilter 写入，网关未传时为 "-"。 */
    public static final String TRACE_MDC_KEY = "traceId";

    private static final Pattern TRACE_ID = Pattern.compile("^[A-Za-z0-9._-]{1,64}$");

    public static <T> ApiResult<T> ok(final T data) {
        return new ApiResult<>("0", "success", sanitizeTraceId(MDC.get(TRACE_MDC_KEY)), data);
    }

    public static <T> ApiResult<T> ok(final T data, final String traceId) {
        return new ApiResult<>("0", "success", sanitizeTraceId(traceId), data);
    }

    public static String sanitizeTraceId(final String traceId) {
        return (traceId != null && TRACE_ID.matcher(traceId).matches()) ? traceId : "-";
    }

    /** 当前线程净化后的 traceId（MDC 来源；评审③P2-1 沉淀：各组件封套的 traceId 单一读取入口）。 */
    public static String currentTraceId() {
        return sanitizeTraceId(MDC.get(TRACE_MDC_KEY));
    }
}
