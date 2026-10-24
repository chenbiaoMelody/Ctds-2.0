package com.ctds.delivery.application;

/** 沙箱申请开通命令（发起人取 AuthContext；幂等键取 X-Idempotency-Key）。 */
public record SandboxCommand(String requesterNo, String contractNo, String idempotencyKey) {
}
