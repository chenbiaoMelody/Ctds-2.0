package com.ctds.contract.domain;

/**
 * 留痕结果（contract_template_action_log 语义值域，沿 catalog ActionResult 语义）。
 * 拒绝动作同样留痕（行为 1 规则 1"其他任何主体一律拒绝并留痕"）。
 */
public enum ActionResult {

    /** 动作成功。 */
    SUCCESS("成功"),
    /** 动作被拒绝（拒绝留痕，理由码见 reason_code）。 */
    DENIED("被拒绝");

    private final String displayName;

    ActionResult(final String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}
