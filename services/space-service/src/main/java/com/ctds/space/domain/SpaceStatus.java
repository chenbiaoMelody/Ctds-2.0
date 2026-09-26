package com.ctds.space.domain;

/**
 * 空间状态机（规格 C-2.1 §7 Q4 裁决；实施包不得增删）。
 * 流转：CREATED→ACTIVE（启用）；ACTIVE⇄FROZEN（冻结/恢复）；任一非终态（含未启用）→DISSOLVED（解散，终态不可逆）。
 * 流转判定与动作实现归 3.2.3，本枚举为值域契约。
 */
public enum SpaceStatus {

    /** 已创建（未启用）：创建成功初始态，不可接纳成员、策略不生效。 */
    CREATED("已创建"),
    /** 已启用：可接纳成员、策略生效。 */
    ACTIVE("已启用"),
    /** 已冻结：拒绝新成员准入与新增资源，可恢复至已启用。 */
    FROZEN("已冻结"),
    /** 已解散：终态不可逆；名称全平台锁定（space_name_lock），策略归档不可变。 */
    DISSOLVED("已解散");

    private final String displayName;

    SpaceStatus(final String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}
