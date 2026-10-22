package com.ctds.delivery.interfaces.dto;

import com.ctds.delivery.domain.SandboxJob;
import com.ctds.delivery.domain.SandboxTask;
import com.ctds.delivery.infrastructure.SandboxWorkspaceStore.ResultFile;
import java.time.LocalDateTime;

/**
 * 沙箱读面视图（hifi §7.1）：<b>不含</b>容器 ID/名、镜像、宿主机路径、输入文件名与任何内容；
 * 结果物只出元数据。
 */
public final class SandboxViews {

    private SandboxViews() {
    }

    /** 沙箱任务视图（配额 + 有效期 + 状态 + 数据文件数）。 */
    public record SandboxView(String sandboxNo, String contractNo, long productId,
            String productName, String requesterNo, String providerNo, String status, double cpus,
            int memoryMb, int ttlSeconds, LocalDateTime expiresAt, int dataFileCount,
            String closeReason, LocalDateTime createdAt) {

        public static SandboxView from(final SandboxTask task) {
            return new SandboxView(task.sandboxNo(), task.contractNo(), task.productId(),
                    task.productName(), task.requesterNo(), task.providerNo(), task.status().name(),
                    task.cpus(), task.memoryMb(), task.ttlSeconds(), task.expiresAt(),
                    task.dataFileCount(), task.closeReason(), task.createdAt());
        }
    }

    /** 作业视图（只有状态 / 退出码 / 耗时；无 stdout 与内容）。 */
    public record JobView(String jobNo, String sandboxNo, String jobId, String status,
            Integer exitCode, Long durationMs, String purpose, String territory,
            LocalDateTime createdAt, LocalDateTime finishedAt) {

        public static JobView from(final SandboxJob job) {
            return new JobView(job.jobNo(), job.sandboxNo(), job.jobId(), job.status().name(),
                    job.exitCode(), job.durationMs(), job.purpose(), job.territory(),
                    job.createdAt(), job.finishedAt());
        }
    }

    /** 结果物元数据（SM3 小写 hex；超上限文件 checksum 为空）。 */
    public record ResultView(String fileName, long sizeBytes, String checksum,
            LocalDateTime createdAt) {

        public static ResultView from(final ResultFile file) {
            return new ResultView(file.fileName(), file.sizeBytes(), file.checksum(),
                    file.createdAt());
        }
    }
}
