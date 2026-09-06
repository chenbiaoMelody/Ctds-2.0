package com.ctds.common.logging;

import org.slf4j.MDC;

/**
 * 日志上下文：业务代码通过本类把错误码、业务模块写入 MDC，
 * 经 Spring Boot 结构化日志自动成为 JSON 字段（ADR-005 §3 第 6 项）。
 * traceId 归 common-errorcode 的 TraceIdFilter 管，本类不得读写，clear() 亦不清除。
 */
public final class LogContext {

    /** MDC 中错误码键；有值时结构化日志出现 errorCode 字段。 */
    public static final String ERROR_CODE_MDC_KEY = "errorCode";

    /** MDC 中业务模块键；有值时结构化日志出现 module 字段。 */
    public static final String MODULE_MDC_KEY = "module";

    private LogContext() {
    }

    /** 写入错误码；null 或空串视为清除该键。 */
    public static void setErrorCode(final String code) {
    }

    /** 写入业务模块标识（如模块名/用例名）；null 或空串视为清除该键。 */
    public static void setModule(final String module) {
    }

    /** 清除本组件管理的全部键（不清除 traceId）。 */
    public static void clear() {
    }
}
