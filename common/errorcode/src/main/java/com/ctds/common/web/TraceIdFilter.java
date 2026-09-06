package com.ctds.common.web;

import com.ctds.common.api.ApiResult;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 链路追踪过滤器：从 X-Trace-Id 头取净化后的 traceId 写入 MDC 并回写响应头，
 * 使成功封套与异常封套使用同一来源（ADR-005）。无头或非法值统一落 "-"。
 */
public class TraceIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Trace-Id";

    @Override
    protected void doFilterInternal(final HttpServletRequest request, final HttpServletResponse response,
            final FilterChain filterChain) throws ServletException, IOException {
        final String traceId = ApiResult.sanitizeTraceId(request.getHeader(HEADER));
        MDC.put(ApiResult.TRACE_MDC_KEY, traceId);
        response.setHeader(HEADER, traceId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(ApiResult.TRACE_MDC_KEY);
        }
    }
}
