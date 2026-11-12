package com.ctds.evidence.application;

import java.util.List;

/** 角色 → 权限点集合只读读面（B1-1 / B1-2）。 */
public record RbacRoleView(String role, List<String> permissions) {
}
