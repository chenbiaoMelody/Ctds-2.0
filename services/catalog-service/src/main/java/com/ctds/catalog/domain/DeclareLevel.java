package com.ctds.catalog.domain;

/**
 * 分级申报级别（分级规范 §4.1 取值域 L1~L4；提供方申报、平台复核，CAT-07）。
 *
 * <p>ordinal 即"级别高低"序（L1 最低、L4 最高）：行为 2 规则 1"变更只能收紧就高" =
 * 仅允许 {@code newLevel.ordinal() >= oldLevel.ordinal()}（hifi §4.3），下调一律拒绝。</p>
 */
public enum DeclareLevel {

    /** L1：公开级（最低）。 */
    L1("L1"),
    /** L2：内部级。 */
    L2("L2"),
    /** L3：敏感级。 */
    L3("L3"),
    /** L4：重要敏感级（最高）。 */
    L4("L4");

    private final String displayName;

    DeclareLevel(final String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }

    /** 是否为相对基准级别的收紧方向（就高，不放宽）——行为 2 规则 1 判定入口。 */
    public boolean isTightenedFrom(final DeclareLevel baseline) {
        return ordinal() >= baseline.ordinal();
    }
}
