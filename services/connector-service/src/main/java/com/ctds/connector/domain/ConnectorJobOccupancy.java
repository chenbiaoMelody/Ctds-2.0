package com.ctds.connector.domain;

import java.time.LocalDateTime;

/**
 * 在途交付作业占用行（hifi §2 Q2-A）：使用方登记"我在用这台连接器"，注销时据此判定前置条件。
 * jobRef 由使用方提供，只记业务可读引用，不含秘密材料。
 */
public record ConnectorJobOccupancy(
        Long id,
        String connectorNo,
        String jobRef,
        OccupancyStatus status,
        LocalDateTime occupiedAt,
        LocalDateTime releasedAt) {
}
