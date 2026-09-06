package com.ctds.common.logging;

import java.time.Instant;
import java.util.Map;

/**
 * 审计事件值对象："谁、何时、对什么、做了什么、结果如何"（ADR-005 §3 第 6 项）。
 * eventId/eventTime 允许业务缺省（由接收组件补填）；actor 缺省记 "anonymous"；
 * detail 为防御性拷贝的不可变映射，禁止携带密码、令牌、个人信息。
 */
public record AuditEvent(
        String eventId,
        Instant eventTime,
        String actor,
        String action,
        String targetType,
        String targetId,
        AuditOutcome outcome,
        Map<String, String> detail) {

    /** 紧凑构造器：action/outcome 校验、actor 缺省、detail 防御性拷贝（实现于测试确认后）。 */
    public AuditEvent {
    }

    /** 业务侧便捷工厂（实现于测试确认后）。 */
    public static AuditEvent of(final String actor, final String action, final String targetType,
            final String targetId, final AuditOutcome outcome, final Map<String, String> detail) {
        return new AuditEvent(null, null, actor, action, targetType, targetId, outcome, detail);
    }
}
