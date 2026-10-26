package com.ctds.connector.domain;

import java.time.LocalDateTime;

/**
 * 连接器域动作留痕（谁 / 何时 / 对象 / 动作四要素 + 结果与理由码）。
 * detail 只记业务要素摘要，不含任何秘密材料（章程红线 7）。写入时 createdAt 由库默认值填充（传 null）。
 */
public record ConnectorActionLog(
        String applicationNo,
        String connectorNo,
        String operatorNo,
        String action,
        String result,
        String reasonCode,
        String detail,
        LocalDateTime createdAt) {
}
