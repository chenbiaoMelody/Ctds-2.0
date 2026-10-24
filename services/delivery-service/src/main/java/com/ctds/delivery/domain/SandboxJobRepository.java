package com.ctds.delivery.domain;

import com.ctds.common.pagination.PageQuery;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/** 沙箱作业仓储端口（hifi §2.2；一次作业 = 一次数据使用，3.7.5 事件源）。 */
public interface SandboxJobRepository {

    Optional<SandboxJob> findByIdempotencyKey(String sandboxNo, String idempotencyKey);

    Optional<SandboxJob> findByJobNo(String jobNo);

    String nextJobNo();

    void insert(SandboxJob job);

    void finish(String jobNo, SandboxJobStatus status, Integer exitCode, Long durationMs,
            LocalDateTime finishedAt);

    List<SandboxJob> listBySandbox(String sandboxNo, PageQuery page);

    long countBySandbox(String sandboxNo);

    /** 启动对账：残留 RUNNING 作业（服务中断）统一置 FAILED，返回受影响行数。 */
    int failRunning(LocalDateTime finishedAt);
}
