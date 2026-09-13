package com.ctds.subject.domain;

import java.time.LocalDateTime;

/**
 * 一次状态流转的留痕记录（规格 C-1.1 行为 4 第 2 条：前状态、后状态、触发方、时间 + 操作人与备注）。
 * fromStatus 为 null 表示注册建档（库中记 NONE）。
 */
public record StatusTransition(
        SubjectStatus fromStatus,
        SubjectStatus toStatus,
        TriggerRole triggerRole,
        String operator,
        String remark,
        LocalDateTime createdAt) {
}
