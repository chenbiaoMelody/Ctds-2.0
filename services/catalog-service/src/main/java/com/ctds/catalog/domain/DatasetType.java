package com.ctds.catalog.domain;

/**
 * 资源类型（规格 §7 Q4 裁决四类受控枚举；资源是登记与元数据载体，不承载本体数据的存储与传输）。
 */
public enum DatasetType {

    /** 数据集。 */
    DATASET("数据集"),
    /** API 接口。 */
    API("API接口"),
    /** 报告。 */
    REPORT("报告"),
    /** 模型。 */
    MODEL("模型");

    private final String displayName;

    DatasetType(final String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}
