package com.ctds.common.logging;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * LogContext 清理过滤器：请求结束时清除本组件写入的 MDC 键（errorCode/module），
 * 防止线程池复用把陈旧字段混入后续请求的 JSON 日志（ADR-005 §3 第 6 项"有值才出现"的按请求语义）。
 * traceId 归 common-errorcode 的 TraceIdFilter 清理，本过滤器不清除。
 */
public class LogContextCleanupFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(final HttpServletRequest request, final HttpServletResponse response,
            final FilterChain filterChain) throws ServletException, IOException {
        try {
            filterChain.doFilter(request, response);
        } finally {
            LogContext.clear();
        }
    }
}
