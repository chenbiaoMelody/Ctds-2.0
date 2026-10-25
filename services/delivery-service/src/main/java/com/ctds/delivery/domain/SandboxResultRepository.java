package com.ctds.delivery.domain;

import java.util.List;
import java.util.Optional;

/** 结果物登记仓储（hifi §2.1）；快照与状态机写入的唯一入口。 */
public interface SandboxResultRepository {

    Optional<SandboxResult> find(String sandboxNo, String fileName);

    List<SandboxResult> findBySandbox(String sandboxNo);

    void insert(SandboxResult result);

    /** 驳回后重新提交（仅 REJECTED 可迁移；条件更新防并发重复，0 行 = 状态已变）。 */
    int resubmit(SandboxResult result);

    /** 审核落论（仅 PENDING 可迁移；条件更新防并发重复，0 行 = 已定论或状态已变）。 */
    int conclude(SandboxResult concluded);
}
