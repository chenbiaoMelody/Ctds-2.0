package com.ctds.common.logging;

/**
 * 审计事件埋点接口：业务代码唯一依赖点（ADR-005 §3 第 6 项）。
 * 实现铁律：任何情况下不抛异常、不阻塞业务线程。
 */
public interface AuditRecorder {

    /**
     * 记录一条审计事件；event 为 null 时静默忽略。
     *
     * @param event 审计事件（不允许为 null 之外的业务前置条件）
     */
    void record(AuditEvent event);
}
