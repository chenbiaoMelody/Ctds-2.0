package com.ctds.delivery.audit;

/** 沙箱审计动作（hifi §8.1）：DB 动作留痕与审计 JSONL 共用；字段最小必要。 */
public final class SandboxAuditActions {

    public static final String CREATE = "sandbox.create";
    public static final String CLOSE = "sandbox.close";
    public static final String EXPIRE = "sandbox.expire";
    public static final String RECONCILE = "sandbox.reconcile";
    public static final String READ = "sandbox.read";
    public static final String JOB = "sandbox.job";
    public static final String GOVERNANCE_READ = "sandbox.governance.read";
    public static final String DENIED = "sandbox.denied";
    public static final String DATA_DENIED = "sandbox.data.denied";

    private SandboxAuditActions() {
    }
}
