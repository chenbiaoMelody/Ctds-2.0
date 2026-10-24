package com.ctds.delivery.domain;

import java.time.LocalDateTime;

/**
 * 受控作业流水（hifi §2.2）：3.7.5 计量事件源；不含 stdout/stderr、不含产物内容与路径。
 */
public record SandboxJob(Long id, String jobNo, String sandboxNo, String jobId,
        SandboxJobStatus status, Integer exitCode, Long durationMs, String purpose,
        String territory, String idempotencyKey, LocalDateTime createdAt, LocalDateTime finishedAt) {
}
