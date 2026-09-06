package com.ctds.common.logging;

import com.ctds.common.errorcode.BizException;
import com.ctds.common.errorcode.ErrorCodes;
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

    /** 紧凑构造器：action/outcome 必填校验（服务端常量文案，不回显业务输入）、actor 缺省、detail 防御性拷贝。 */
    public AuditEvent {
        if (action == null || action.isBlank()) {
            throw new BizException(ErrorCodes.PARAM_INVALID, "audit action must not be null or blank");
        }
        if (outcome == null) {
            throw new BizException(ErrorCodes.PARAM_INVALID, "audit outcome must not be null");
        }
        actor = (actor == null || actor.isBlank()) ? "anonymous" : actor;
        detail = (detail == null) ? Map.of() : Map.copyOf(detail);
    }

    /** 业务侧便捷工厂：eventId/eventTime 留空由组件补填。 */
    public static AuditEvent of(final String actor, final String action, final String targetType,
            final String targetId, final AuditOutcome outcome, final Map<String, String> detail) {
        return new AuditEvent(null, null, actor, action, targetType, targetId, outcome, detail);
    }
}
