package com.ctds.common.auth;

/**
 * RBAC 判断接口（代码内调用写法，与 {@link RequirePermission} 注解共用同一判定内核）。
 */
public interface AccessControl {

    /** 当前身份是否具备指定权限；只判断，不抛错、不记审计。未认证 = false。 */
    boolean hasPermission(String permission);

    /**
     * 断言当前身份具备指定权限：未认证 → 抛 {@link AuthException}(UNAUTHORIZED)；
     * 有身份无权限 → 记 DENIED 审计（开关开启时）并抛 {@link AuthException}(FORBIDDEN)。
     */
    void require(String permission);
}
