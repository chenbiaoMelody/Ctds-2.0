package com.ctds.catalog.domain;

import java.time.LocalDateTime;

/**
 * 资源域统一操作留痕（一行 = 一次动作/留痕事件；WBS-3.3.2 hifi §3.3，逐列对应 dataset_action_log 表）。
 *
 * <p>四要素：谁（actorSubjectNo）/ 何时（createdAt）/ 对象（spaceId+datasetId）/ 动作（action），
 * 附结果（result，拒绝动作同样留痕）、变更前后值（fromValue→toValue）与拒绝理由码（reasonCode）。
 * 留痕只插不改、不含敏感原文与数据本体（规格边界声明 3 / 行为 1 规则 7）。</p>
 */
public record DatasetActionLog(
        Long id,
        String actorSubjectNo,
        long spaceId,
        Long datasetId,
        String action,
        String fromValue,
        String toValue,
        ActionResult result,
        String reasonCode,
        LocalDateTime createdAt) {
}
