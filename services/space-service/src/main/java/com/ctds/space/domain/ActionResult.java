package com.ctds.space.domain;

/**
 * 留痕结果（space_action_log.result 值域）。
 * 拒绝动作同样留痕（规格行为 2 规则 6 / 行为 6 规则 5：谁/何时/目标/结果）。
 */
public enum ActionResult {

    /** 动作成功。 */
    SUCCESS("成功"),
    /** 动作被拒绝（拒绝留痕，理由见 reason）。 */
    DENIED("被拒绝");

    private final String displayName;

    ActionResult(final String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}
