package com.ctds.delivery.application;

/** 提交审核命令（WBS-3.5.5 hifi §3.2）：参数只含业务要素。 */
public record SandboxResultCommand(String sandboxNo, String fileName, String requesterNo,
        String idempotencyKey) {
}
