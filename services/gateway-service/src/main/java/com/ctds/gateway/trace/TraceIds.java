package com.ctds.gateway.trace;

import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 链路追踪号口径（hifi §1.5 / Q8-A）：入站 {@code X-Trace-Id} 合法则透传、否则生成；
 * 与 {@code ApiResult.sanitizeTraceId} 同一字符集口径（字母数字与 . _ -，最长 64），
 * 避免网关自建封套与 common-api 封套对同一取值的净化结果不一致。
 */
public final class TraceIds {

    /** 追踪号头名（入站透传 + 出站回写）。 */
    public static final String HEADER = "X-Trace-Id";

    /** 反应式上下文键（TraceIdWebFilter 写入，各过滤器与封套读取）。 */
    public static final String CONTEXT_KEY = "ctds.gateway.traceId";

    /** 未取到追踪号时的占位值（沿 common 既有口径）。 */
    public static final String ABSENT = "-";

    private static final Pattern VALID = Pattern.compile("^[A-Za-z0-9._-]{1,64}$");

    private TraceIds() {
    }

    /** 入站值合法则透传，否则生成新号（不使用 "-" 占位，保证真实链路可串联）。 */
    public static String resolve(final String inbound) {
        if (inbound != null && VALID.matcher(inbound).matches()) {
            return inbound;
        }
        return UUID.randomUUID().toString().replace("-", "");
    }

    /** 上下文缺省取值（沿 ApiResult 口径）。 */
    public static String orAbsent(final String traceId) {
        return (traceId == null || traceId.isBlank()) ? ABSENT : traceId;
    }
}
