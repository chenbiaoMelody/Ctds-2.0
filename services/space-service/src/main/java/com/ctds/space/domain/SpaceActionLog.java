package com.ctds.space.domain;

import java.time.LocalDateTime;

/**
 * 空间域统一操作留痕（一行 = 一次动作/留痕事件；WBS-3.2.2 hifi §1.6，逐列对应 space_action_log 表）。
 *
 * <p>四要素：谁（operator）/ 何时（createdAt）/ 对象（targetType+targetId+spaceId）/ 动作（action），
 * 附结果（result，拒绝动作同样留痕）、变更前后值（fromValue→toValue）与理由（reason）。
 * 留痕只插不改、不含敏感原文（规格边界声明 3）。</p>
 */
public record SpaceActionLog(
        Long id,
        Long spaceId,
        TargetType targetType,
        Long targetId,
        String action,
        String operator,
        String fromValue,
        String toValue,
        ActionResult result,
        String reason,
        LocalDateTime createdAt) {
}
