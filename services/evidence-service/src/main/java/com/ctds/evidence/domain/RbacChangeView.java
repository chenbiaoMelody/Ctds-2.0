package com.ctds.evidence.domain;

/** 映射变更留痕读面（B1-R4：变更人 / 时点 / 角色 / 权限点 / 前后值 / 变更单号）。 */
public record RbacChangeView(
        String changeNo,
        String changeOrderNo,
        String changedBy,
        String changedAt,
        String roleCode,
        String permissionPoint,
        String beforeValue,
        String afterValue) {
}
