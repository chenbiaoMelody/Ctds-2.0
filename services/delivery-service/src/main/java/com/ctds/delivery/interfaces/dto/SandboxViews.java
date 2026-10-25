package com.ctds.delivery.interfaces.dto;

import com.ctds.delivery.domain.SandboxJob;
import com.ctds.delivery.domain.SandboxResult;
import com.ctds.delivery.domain.SandboxResultReview;
import com.ctds.delivery.domain.SandboxResultStatus;
import com.ctds.delivery.domain.SandboxTask;
import com.ctds.delivery.infrastructure.SandboxWorkspaceStore.ResultFile;
import java.time.LocalDateTime;

/**
 * 沙箱读面视图（hifi §7.1）：<b>不含</b>容器 ID/名、镜像、宿主机路径、输入文件名与任何内容；
 * 结果物只出元数据与审核状态（未提交的结果物状态四字段为 null）。
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

    /**
     * 结果物元数据 + 审核状态（WBS-3.5.5 hifi §7.1 加性四字段：status / conclusion /
     * rejectReason / reviewRound——未提交的结果物为 null；SM3 小写 hex）。
     */
    public record ResultView(String fileName, long sizeBytes, String checksum,
            LocalDateTime createdAt, String status, String conclusion, String rejectReason,
            Integer reviewRound) {

        public static ResultView from(final ResultFile file) {
            return new ResultView(file.fileName(), file.sizeBytes(), file.checksum(),
                    file.createdAt(), null, null, null, null);
        }

        /** 工作区元数据 + 登记状态合成（登记存在而文件缺失时回退登记快照值）。 */
        public static ResultView of(final ResultFile file, final SandboxResult registration) {
            if (registration == null) {
                return file == null ? null : from(file);
            }
            final String fileName = registration.fileName();
            final long sizeBytes = file == null ? registration.sizeBytes() : file.sizeBytes();
            final String checksum = file == null ? registration.checksum() : file.checksum();
            final LocalDateTime createdAt =
                    file == null || file.createdAt() == null ? registration.submittedAt()
                            : file.createdAt();
            final String conclusion = registration.status() == SandboxResultStatus.PENDING ? null
                    : registration.status().name();
            return new ResultView(fileName, sizeBytes, checksum, createdAt,
                    registration.status().name(), conclusion, registration.rejectReason(),
                    registration.reviewRound());
        }
    }

    /** 审核留痕视图（治理读面；结论与理由齐备，无内容与路径）。 */
    public record ReviewView(String sandboxNo, String fileName, int reviewRound, String conclusion,
            String reason, String reviewedBy, LocalDateTime createdAt) {

        public static ReviewView from(final SandboxResultReview review) {
            return new ReviewView(review.sandboxNo(), review.fileName(), review.reviewRound(),
                    review.conclusion().name(), review.reason(), review.reviewedBy(),
                    review.createdAt());
        }
    }
}
