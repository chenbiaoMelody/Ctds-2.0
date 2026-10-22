package com.ctds.delivery.domain;

import java.time.LocalDateTime;

/**
 * 沙箱任务（hifi §2.1）：只存业务要素与配额快照；无宿主机路径、无容器 ID/名、无镜像。
 */
public record SandboxTask(Long id, String sandboxNo, String contractNo, long productId,
        String productName, String requesterNo, String providerNo, SandboxStatus status,
        double cpus, int memoryMb, int ttlSeconds, LocalDateTime expiresAt, int dataFileCount,
        String closeReason, String idempotencyKey, LocalDateTime createdAt) {

    /** 状态终止（关闭 / 到期 / 对账纠偏）。 */
    public SandboxTask closed(final SandboxStatus next, final String reason) {
        return new SandboxTask(id, sandboxNo, contractNo, productId, productName, requesterNo,
                providerNo, next, cpus, memoryMb, ttlSeconds, expiresAt, dataFileCount, reason,
                idempotencyKey, createdAt);
    }
}
