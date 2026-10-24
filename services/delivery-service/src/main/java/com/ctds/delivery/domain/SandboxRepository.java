package com.ctds.delivery.domain;

import com.ctds.common.pagination.PageQuery;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/** 沙箱任务仓储端口（hifi §2；只读业务要素，不含容器 / 路径 / 内容）。 */
public interface SandboxRepository {

    Optional<SandboxTask> findByIdempotencyKey(String requesterNo, String idempotencyKey);

    Optional<SandboxTask> findBySandboxNo(String sandboxNo);

    String nextSandboxNo();

    void insert(SandboxTask task);

    /** 条件更新（仅 RUNNING 可终止）：返回受影响行数（0 = 已被并发终止）。 */
    int close(String sandboxNo, SandboxStatus status, String closeReason, LocalDateTime updatedAt);

    /** 供到期 / 对账使用的候选集（上限由实现限制）。 */
    List<SandboxTask> listExpired(LocalDateTime now, int limit);

    List<SandboxTask> listRunning(int limit);

    List<SandboxTask> listForParticipant(String callerNo, String contractNo, SandboxStatus status,
            PageQuery page);

    long countForParticipant(String callerNo, String contractNo, SandboxStatus status);

    List<SandboxTask> listGovernance(String contractNo, SandboxStatus status, PageQuery page);

    long countGovernance(String contractNo, SandboxStatus status);

    /** 单需求方活跃任务数（RUNNING 且未到期）。 */
    int countActiveByRequester(String requesterNo, LocalDateTime now);

    /** 全局活跃任务数（RUNNING 且未到期）。 */
    int countActiveGlobal(LocalDateTime now);

    void insertActionLog(SandboxActionLog log);
}
