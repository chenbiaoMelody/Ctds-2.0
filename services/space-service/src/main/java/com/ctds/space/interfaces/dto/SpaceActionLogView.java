package com.ctds.space.interfaces.dto;

import com.ctds.space.domain.SpaceActionLog;
import java.time.LocalDateTime;

/**
 * 空间操作留痕视图（WBS-3.2.6 hifi §1 端点 25 出参）：四要素（操作者 / 时间 / 对象 / 动作）
 * + 结果（SUCCESS/DENIED）、理由、变更前后值。字段白名单由本记录固定（表格本身不含敏感原文）。
 */
public record SpaceActionLogView(
        Long id,
        String action,
        String operator,
        String result,
        String reason,
        String fromValue,
        String toValue,
        String targetType,
        Long targetId,
        LocalDateTime createdAt) {

    public static SpaceActionLogView from(final SpaceActionLog log) {
        return new SpaceActionLogView(log.id(), log.action(), log.operator(), log.result().name(),
                log.reason(), log.fromValue(), log.toValue(), log.targetType().name(), log.targetId(),
                log.createdAt());
    }
}
