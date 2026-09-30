package com.ctds.catalog.domain;

/**
 * 资源状态机（WBS-3.3.2 hifi §4.1）：ACTIVE（生效）→ DELETED（已注销，终态不可逆，无出边）。
 * 注销 = 两写事务（status→DELETED + name_lock 写入）；DELETED 后一切变更/注销再动作拒绝（1007C0007）。
 */
public enum DatasetStatus {

    /** 生效中（登记成功初始态；可变更、可注销）。 */
    ACTIVE("生效中"),
    /** 已注销（终态不可逆；名称同空间锁定）。 */
    DELETED("已注销");

    private final String displayName;

    DatasetStatus(final String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}
