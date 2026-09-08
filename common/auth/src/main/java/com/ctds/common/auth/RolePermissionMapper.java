package com.ctds.common.auth;

import java.util.Set;

/**
 * "角色→权限"映射解析器（模式 A，来源可插拔）：V1.0 为配置文件实现
 * {@link ConfigRolePermissionMapper}；3.9.2 库表来源以覆盖本接口 Bean 无缝切换（组件零改动）。
 */
public interface RolePermissionMapper {

    /** 指定角色的权限集合；未知角色 = 空集（不抛错）。 */
    Set<String> permissionsOf(String role);
}
