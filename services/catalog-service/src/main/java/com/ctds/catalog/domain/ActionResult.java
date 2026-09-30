package com.ctds.catalog.domain;

/**
 * 留痕结果（dataset_action_log.result 值域，沿 space ActionResult 语义）。
 * 拒绝动作同样留痕（规格行为 1 规则 4 拒收留痕 / 行为 2 规则 5 越权留痕 / 行为 7 规则 4 越权探测留痕）。
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
