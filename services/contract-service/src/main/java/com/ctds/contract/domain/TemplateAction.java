package com.ctds.contract.domain;

/**
 * 模板留痕动作码（contract_template_action_log.action 值域，封闭枚举；hifi §4）。
 * 拒绝类统一 DENIED_MANAGE（维护面拒绝：未入驻维护 / 非运营方维护 / 同态启停），
 * 理由差异由 reason_code（1008 码位尾号）区分——DB-31 口径：不设第二套理由枚举。
 */
public enum TemplateAction {

    /** 新增模板（含首版本）。 */
    CREATE("新增"),
    /** 修订模板（产生新版本）。 */
    REVISE("修订"),
    /** 启用。 */
    ENABLE("启用"),
    /** 停用。 */
    DISABLE("停用"),
    /** 维护动作被拒（拒绝留痕——规则 1；含未入驻维护 DENIED，理由码 C0003）。 */
    DENIED_MANAGE("维护被拒");

    private final String displayName;

    TemplateAction(final String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}
