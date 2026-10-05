package com.ctds.contract.domain;

/**
 * 模板状态机（WBS-3.4.2 hifi §6；lofi §6：新增即启用，无草稿态——最小状态机）。
 * DISABLED = 停用（退出已入驻浏览列表 + 不可用于新发起，不影响既有合约与协商中草案——
 * 行为 1 规则 4；"协商中草案按 V1 快照继续"的兑现归 3.4.3 快照机制）。
 */
public enum TemplateStatus {

    /** 启用中（可浏览、可被新发起）。 */
    ENABLED("启用中"),
    /** 停用（浏览不可见 + 不可新发起，仅运营方维护视图可查以支持重新启用——Q6-A）。 */
    DISABLED("停用");

    private final String displayName;

    TemplateStatus(final String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}
