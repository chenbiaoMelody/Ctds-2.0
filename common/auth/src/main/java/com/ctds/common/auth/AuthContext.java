package com.ctds.common.auth;

import java.util.Set;

/**
 * 身份上下文读取入口（静态工具，用法同 common-logging 的 LogContext）：
 * 业务代码一行取用"当前用户是谁/有哪些角色"。由 {@link AuthContextFilter} 在请求入口写入、
 * 请求结束清理（防线程池复用串号），本类不自带写入的公共通道（set 仅包内可见）。
 */
public final class AuthContext {

    private static final ThreadLocal<AuthUser> HOLDER = new ThreadLocal<>();

    private AuthContext() {
    }

    /** 当前身份；未认证 = null。 */
    public static AuthUser user() {
        return HOLDER.get();
    }

    /** 当前用户/主体标识；空上下文 = null。 */
    public static String subject() {
        final AuthUser user = HOLDER.get();
        return user == null ? null : user.subject();
    }

    /** 当前角色清单；空上下文 = 空集（不抛错）。 */
    public static Set<String> roles() {
        final AuthUser user = HOLDER.get();
        return user == null ? Set.of() : user.roles();
    }

    public static boolean isAuthenticated() {
        return subject() != null;
    }

    static void set(final AuthUser user) {
        HOLDER.set(user);
    }

    public static void clear() {
        HOLDER.remove();
    }
}
