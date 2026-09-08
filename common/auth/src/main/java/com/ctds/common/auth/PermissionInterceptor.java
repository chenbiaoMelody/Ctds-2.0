package com.ctds.common.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 注解式 RBAC 强制点：方法带 {@link RequirePermission} 时经 {@link AccessControl#require} 判定，
 * 错误与审计行为同代码内调用写法。无注解方法直接放行（V1.0 按接口控制，不做全站强制）。
 */
public class PermissionInterceptor implements HandlerInterceptor {

    private final AccessControl accessControl;

    public PermissionInterceptor(final AccessControl accessControl) {
        this.accessControl = accessControl;
    }

    @Override
    public boolean preHandle(final HttpServletRequest request, final HttpServletResponse response,
            final Object handler) {
        if (!(handler instanceof HandlerMethod method)) {
            return true;
        }
        final RequirePermission annotation = method.getMethodAnnotation(RequirePermission.class);
        if (annotation == null) {
            return true;
        }
        final String permission = annotation.value();
        if (permission == null || permission.isBlank()) {
            // 配置错误（规格外行为不静默）：对外只见 500 通用文案，细节留在服务端
            throw new IllegalStateException("@RequirePermission on " + method + " declares blank permission");
        }
        accessControl.require(permission);
        return true;
    }
}
