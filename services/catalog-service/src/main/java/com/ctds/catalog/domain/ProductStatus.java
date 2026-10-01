package com.ctds.catalog.domain;

/**
 * 产品状态机（WBS-3.3.4 hifi §3：枚举封闭，测试探针锚定；状态机 = 未上架 → 已上架 ⇄ 已下架 +
 * 已注销，规格行为 4 规则 1）。DB 存枚举名，对外出站用 displayName（业务可读中文——行为 6 规则 3
 * "条目保留但标记产品当前状态"的呈现口径）。
 *
 * <p>写面（封装/上下架/注销）归 3.3.5；本卡消费面：目录检索与详情硬过滤 LISTED（行为 5 规则 1）、
 * 收藏/订阅新发起状态门槛（行为 6 规则 3）、R9/R10 读时计算（Q5-A）。</p>
 */
public enum ProductStatus {

    /** 未上架（初始态；不在目录呈现）。 */
    DRAFT("未上架"),
    /** 已上架（在目录呈现，可被检索/收藏/订阅）。 */
    LISTED("已上架"),
    /** 已下架（不在目录呈现；既有条目保留且标记本状态）。 */
    DELISTED("已下架"),
    /** 已注销（终态不可逆；既有条目保留且标记本状态）。 */
    CANCELLED("已注销");

    private final String displayName;

    ProductStatus(final String displayName) {
        this.displayName = displayName;
    }

    /** 对外出站展示名（业务可读中文）。 */
    public String getDisplayName() {
        return displayName;
    }
}
