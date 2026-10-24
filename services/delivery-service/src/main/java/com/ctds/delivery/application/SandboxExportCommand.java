package com.ctds.delivery.application;

/** 审批后导出命令（WBS-3.5.5 hifi §5）：用途 / 地域为可选策略要素。 */
public record SandboxExportCommand(String sandboxNo, String fileName, String purpose,
        String territory, String requesterNo, String idempotencyKey) {
}
