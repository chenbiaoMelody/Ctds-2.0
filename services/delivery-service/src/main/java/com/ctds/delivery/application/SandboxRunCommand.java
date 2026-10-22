package com.ctds.delivery.application;

/** 受控作业命令（请求只给清单内 jobId；用途 / 地域为可选策略要素）。 */
public record SandboxRunCommand(String sandboxNo, String jobId, String purpose, String territory,
        String requesterNo, String idempotencyKey) {
}
