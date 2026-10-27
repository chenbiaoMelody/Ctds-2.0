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
    /** 3.6.4 生命周期动作。 */
    public static final String UPGRADE_REQUESTED = "connector.upgrade.requested";
    public static final String UPGRADE_APPLIED = "connector.upgrade.applied";
    public static final String UPGRADE_CONFIRMED = "connector.upgrade.confirmed";
    public static final String CANCELLED = "connector.cancelled";
    public static final String OCCUPANCY_REGISTER = "connector.occupancy.register";
    public static final String OCCUPANCY_RELEASE = "connector.occupancy.release";
    /** 3.6.5 源接入动作（登记 / 变更 / 自检 / 读取 / 拒绝）。 */
    public static final String SOURCE_REGISTER = "source.register";
    public static final String SOURCE_AMEND = "source.amend";
    public static final String SOURCE_CHECK = "source.check";
    public static final String SOURCE_READ = "source.read";
    public static final String SOURCE_DENIED = "source.denied";

    private ConnectorAuditActions() {
    }
}
