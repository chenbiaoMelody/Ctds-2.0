package com.ctds.evidence.interfaces;

/** W1 / W2 入参：角色 + 权限点 + 变更单号（三者构成幂等唯一键）。 */
public record RbacGrantRequest(String role, String permission, String changeOrderNo) {
}
