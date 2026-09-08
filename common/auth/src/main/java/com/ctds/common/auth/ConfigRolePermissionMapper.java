package com.ctds.common.auth;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * 模式 A 的配置文件来源映射（ctds.auth.permissions.&lt;角色&gt;=逗号分隔权限）。
 * 边界（hifi"数据结构与边界值"）：角色/权限名 trim 后 >64 字符或为空白 → 按"不命中"处理，不抛错。
 */
public class ConfigRolePermissionMapper implements RolePermissionMapper {

    static final int MAX_NAME_LENGTH = 64;

    private final Map<String, Set<String>> rolePermissions;

    public ConfigRolePermissionMapper(final Map<String, String> rawPermissions) {
        final Map<String, Set<String>> parsed = new LinkedHashMap<>();
        if (rawPermissions != null) {
            rawPermissions.forEach((role, permissionList) -> {
                final String normalizedRole = normalize(role);
                if (normalizedRole == null) {
                    return;
                }
                final Set<String> permissions = new LinkedHashSet<>();
                if (permissionList != null) {
                    for (final String segment : permissionList.split(",")) {
                        final String permission = normalize(segment);
                        if (permission != null) {
                            permissions.add(permission);
                        }
                    }
                }
                parsed.put(normalizedRole, Collections.unmodifiableSet(permissions));
            });
        }
        this.rolePermissions = Collections.unmodifiableMap(parsed);
    }

    @Override
    public Set<String> permissionsOf(final String role) {
        if (role == null) {
            return Set.of();
        }
        return rolePermissions.getOrDefault(role.trim(), Set.of());
    }

    private String normalize(final String value) {
        if (value == null) {
            return null;
        }
        final String trimmed = value.trim();
        return trimmed.isEmpty() || trimmed.length() > MAX_NAME_LENGTH ? null : trimmed;
    }
}
