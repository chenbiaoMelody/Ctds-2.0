package com.ctds.common.auth;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 当前请求的身份主体（网关注入上下文头解析而来，ADR-005 §3 第 7 项）。
 * roles 做防御性拷贝为不可变集合（保序，沿 2.4.4 "键序确定"口径）。
 */
public record AuthUser(String subject, Set<String> roles) {

    public AuthUser {
        roles = roles == null ? Set.of() : Collections.unmodifiableSet(new LinkedHashSet<>(roles));
    }
}
