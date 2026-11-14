package com.ctds.common.auth;

import com.ctds.common.api.ApiResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.util.AntPathMatcher;

/**
 * WBS-3.9.3 Q2-A（servlet 侧）：部署域退役态业务请求拦截——503 + `1013C0004` + "部署域已退役"
 * （B9-R6）；豁免路径模式命中放行（健康读数 / 审计读面 / 部署域读面）；缺省 ACTIVE 零行为。
 * 不挂 `ctds.auth.enabled` 条件（hifi §9 E-3：auth.enabled=false 为非生产调试开关，
 * 挂接即构成退役绕过面，CHG §3 防绕过优先）。
 */
public class DomainStatusFilter implements Filter {

    private static final Logger log = LoggerFactory.getLogger(DomainStatusFilter.class);

    private final DomainProperties properties;
    private final AntPathMatcher pathMatcher = new AntPathMatcher();
    private final ObjectMapper objectMapper = new ObjectMapper();

    public DomainStatusFilter(final DomainProperties properties) {
        this.properties = properties;
        properties.validateStatus();
    }

    @Override
    public void doFilter(final ServletRequest request, final ServletResponse response,
            final FilterChain chain) throws IOException, ServletException {
        if (!properties.isRetired() || !(request instanceof HttpServletRequest httpRequest)) {
            chain.doFilter(request, response);
            return;
        }
        if (isExempt(uriOf(httpRequest))) {
            chain.doFilter(request, response);
            return;
        }
        final HttpServletResponse httpResponse = (HttpServletResponse) response;
        httpResponse.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
        httpResponse.setContentType(MediaType.APPLICATION_JSON_VALUE);
        httpResponse.setCharacterEncoding("UTF-8");
        try {
            httpResponse.getWriter().write(objectMapper.writeValueAsString(new ApiResult<Void>(
                    DomainErrorCodes.DOMAIN_RETIRED.value(),
                    DomainErrorCodes.DOMAIN_RETIRED_MESSAGE,
                    ApiResult.currentTraceId(), null)));
        } catch (final Exception serializationFailure) {
            log.error("domain retired 503 envelope serialization failed", serializationFailure);
        }
    }

    private String uriOf(final HttpServletRequest request) {
        String path = request.getRequestURI();
        final String context = request.getContextPath();
        if (context != null && !context.isEmpty() && path.startsWith(context)) {
            path = path.substring(context.length());
        }
        return path;
    }

    private boolean isExempt(final String path) {
        final List<String> patterns = properties.getRetired().getPermitPathPatterns();
        return patterns != null && patterns.stream()
                .anyMatch(pattern -> pathMatcher.match(pattern, path));
    }
}
