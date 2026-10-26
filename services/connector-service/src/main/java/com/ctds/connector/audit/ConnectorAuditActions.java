package com.ctds.connector.audit;

/** 连接器域审计动作（JSONL + DB 动作留痕共用；不含秘密材料）。 */
public final class ConnectorAuditActions {

    public static final String APPLY = "connector.apply";
    public static final String AMEND = "connector.amend";
    public static final String VERIFY_APPROVED = "connector.verify.approved";
    public static final String VERIFY_REJECTED = "connector.verify.rejected";
    public static final String BINDING_READ = "connector.binding.read";
    public static final String HEARTBEAT = "connector.heartbeat";
    public static final String DENIED = "connector.denied";

    private ConnectorAuditActions() {
    }
}
