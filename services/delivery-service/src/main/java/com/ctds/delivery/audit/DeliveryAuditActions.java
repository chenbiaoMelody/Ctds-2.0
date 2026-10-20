package com.ctds.delivery.audit;

/** 交付域审计动作（JSONL + DB 动作留痕共用；不含本体/原文/路径/密钥）。 */
public final class DeliveryAuditActions {

    public static final String CREATE = "delivery.create";
    public static final String READ = "delivery.read";
    public static final String GOVERNANCE_READ = "delivery.governance.read";
    public static final String PACKAGE_DOWNLOAD = "delivery.package.download";
    public static final String DENIED = "delivery.denied";

    private DeliveryAuditActions() {
    }
}
