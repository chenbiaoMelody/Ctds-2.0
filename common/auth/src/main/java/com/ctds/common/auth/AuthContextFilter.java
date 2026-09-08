package com.ctds.common.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 身份上下文过滤器：按配置头名解析"网关注入的身份/角色"写入 {@link AuthContext}，
 * 请求结束 finally 清理（模式同 2.4.4 LogContextCleanupFilter，防线程池复用串号，不受组件开关约束）。
 * 边界（ADR-005 §3 第 7 项）：subject ≤128、roles 合计 ≤512，超限整头作废按未认证处理并 WARN（不回显原值）；
 * roles 逗号分隔逐项 trim 去空去重，段数 >32 取前 32 并 WARN。
 */
public class AuthContextFilter extends OncePerRequestFilter {

    static final int MAX_SUBJECT_LENGTH = 128;
    static final int MAX_ROLES_TOTAL_LENGTH = 512;
    static final int MAX_ROLES_ITEMS = 32;

    private static final Logger log = LoggerFactory.getLogger(AuthContextFilter.class);

    private final AuthProperties properties;

    public AuthContextFilter(final AuthProperties properties) {
        this.properties = properties;
    }

    @Override
    protected void doFilterInternal(final HttpServletRequest request, final HttpServletResponse response,
            final FilterChain filterChain) throws ServletException, IOException {
        try {
            AuthContext.set(parse(request));
            filterChain.doFilter(request, response);
        } finally {
            AuthContext.clear();
        }
    }

    private AuthUser parse(final HttpServletRequest request) {
        final String subject = normalize(request.getHeader(properties.getHeader().getSubject()));
        final String rolesRaw = request.getHeader(properties.getHeader().getRoles());
        if (subject == null) {
            return null;
        }
        if (subject.length() > MAX_SUBJECT_LENGTH) {
            log.warn("identity context dropped: subject header exceeds {} chars", MAX_SUBJECT_LENGTH);
            return null;
        }
        if (rolesRaw != null && rolesRaw.length() > MAX_ROLES_TOTAL_LENGTH) {
            log.warn("identity context dropped: roles header exceeds {} chars", MAX_ROLES_TOTAL_LENGTH);
            return null;
        }
        return new AuthUser(subject, parseRoles(rolesRaw));
    }

    private Set<String> parseRoles(final String rolesRaw) {
        final Set<String> roles = new LinkedHashSet<>();
        if (rolesRaw == null) {
            return roles;
        }
        int droppedByCap = 0;
        for (final String segment : rolesRaw.split(",")) {
            final String role = normalize(segment);
            if (role == null) {
                continue;
            }
            if (roles.size() >= MAX_ROLES_ITEMS) {
                droppedByCap++;
                continue;
            }
            roles.add(role);
        }
        if (droppedByCap > 0) {
            log.warn("roles header truncated to first {} items, {} dropped", MAX_ROLES_ITEMS, droppedByCap);
        }
        return roles;
    }

    private String normalize(final String value) {
        if (value == null) {
            return null;
        }
        final String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
