package com.ctds.delivery.domain;

import java.time.LocalDateTime;

/**
 * 沙箱结果物登记（hifi §2.1）：一任务一文件名一行；提交审核时快照（SM3/字节数）为导出
 * 复核锚点。不含文件内容、路径、容器、镜像与密钥材料。
 */
public record SandboxResult(Long id, String sandboxNo, String fileName,
        SandboxResultStatus status, int reviewRound, long sizeBytes, String checksum,
        String submittedBy, LocalDateTime submittedAt, String reviewedBy,
        LocalDateTime reviewedAt, String rejectReason, String idempotencyKey,
        LocalDateTime createdAt, LocalDateTime updatedAt) {

    /** 审核落论（状态机唯一迁移；结论一经作出不可修改）。 */
    public SandboxResult concluded(final SandboxResultStatus next, final String reviewer,
            final LocalDateTime reviewedAt, final String rejectReason,
            final LocalDateTime updatedAt) {
        return new SandboxResult(id, sandboxNo, fileName, next, reviewRound, sizeBytes, checksum,
                submittedBy, submittedAt, reviewer, reviewedAt, rejectReason, idempotencyKey,
                createdAt, updatedAt);
    }
}
