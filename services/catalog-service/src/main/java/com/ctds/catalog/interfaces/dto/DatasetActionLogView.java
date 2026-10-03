package com.ctds.catalog.interfaces.dto;

import com.ctds.catalog.domain.DatasetActionLog;
import java.time.LocalDateTime;

/**
 * 资源操作留痕视图（WBS-3.3.6 hifi §1.2 R14 出参；四要素 + from→to + 拒绝码——
 * 不含敏感原文与数据本体；读面 = 登记主体本人 + 治理例外 admin）。
 */
public record DatasetActionLogView(Long id, String action, String actorSubjectNo, String result,
        String reasonCode, String fromValue, String toValue, LocalDateTime createdAt) {

    /** 行 → 视图映射（spaceId/datasetId 为行归属定位字段，不外露）。 */
    public static DatasetActionLogView from(final DatasetActionLog log) {
        return new DatasetActionLogView(log.id(), log.action(), log.actorSubjectNo(),
                log.result().name(), log.reasonCode(), log.fromValue(), log.toValue(), log.createdAt());
    }
}
