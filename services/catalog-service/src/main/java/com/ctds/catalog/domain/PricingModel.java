package com.ctds.catalog.domain;

/**
 * 定价模型四档（WBS-3.3.4 hifi §3 = 规格行为 3 规则 3，§7 Q4 裁决：免费 / 按次 / 包月 / 交易额分成，
 * 对齐 3.7.6 计费引擎口径——免费档不产生计费流水）。DB 存枚举名，对外出站用 displayName。
 * 价格数值字段归 3.3.5 落定（其迁移增列），本卡只承载定价模型档位。
 */
public enum PricingModel {

    /** 免费（须显式选择，不得空缺）。 */
    FREE("免费"),
    /** 按次计费。 */
    PER_CALL("按次"),
    /** 包月计费。 */
    MONTHLY("包月"),
    /** 交易额分成。 */
    REVENUE_SHARE("交易额分成");

    private final String displayName;

    PricingModel(final String displayName) {
        this.displayName = displayName;
    }

    /** 对外出站展示名（业务可读中文）。 */
    public String getDisplayName() {
        return displayName;
    }
}
