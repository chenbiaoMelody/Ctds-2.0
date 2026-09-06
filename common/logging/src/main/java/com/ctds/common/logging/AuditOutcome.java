package com.ctds.common.logging;

/**
 * 审计事件结果（ADR-005 §3 第 6 项）：SUCCESS=业务操作成功；DENIED=业务操作被拒绝（如参数/权限拦截）；
 * FAILURE=业务操作执行失败。
 */
public enum AuditOutcome {
    SUCCESS,
    DENIED,
    FAILURE
}
