package com.ctds.connector.domain;

/**
 * 升级兼容性声明（规格行为 9-2）：COMPATIBLE = 兼容（平台原子替换）；
 * INCOMPATIBLE = 不兼容（须运营方显式确认后才替换，确认前旧版本继续有效）。
 */
public enum Compatibility {
    COMPATIBLE,
    INCOMPATIBLE;

    /** 请求值 → 枚举；非法取值返回 null（由调用方映射参数码，不抛内部异常）。 */
    public static Compatibility parse(final String value) {
        for (final Compatibility compatibility : values()) {
            if (compatibility.name().equals(value)) {
                return compatibility;
            }
        }
        return null;
    }
}
