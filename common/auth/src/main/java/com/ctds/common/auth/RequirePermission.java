package com.ctds.common.auth;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 声明"调用本方法需要某权限"（服务侧 RBAC 注解式强制，由 {@link PermissionInterceptor} 执行）。
 * 权限名命名规约："对象.动作" 小写点分（与审计 action 同源），如 greeting.delete。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RequirePermission {

    /** 所需权限名（单权限；空值 = 编码错误，请求时按内部错误处理）。 */
    String value();
}
