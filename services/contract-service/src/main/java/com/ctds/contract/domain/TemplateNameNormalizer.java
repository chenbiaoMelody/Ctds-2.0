package com.ctds.contract.domain;

import java.util.Locale;

/**
 * 模板名称归一化（同类型唯一性判定入参口径；沿 catalog DatasetNameNormalizer 命名先例）。
 * 归一化单点 = DB 生成列 LOWER(TRIM(template_name))（迁移 V1 注释口径）；本类与之等价，
 * 仅用于应用层幂等键与判重预查（DB 唯一索引 uk_type_norm_name 仍为兜底硬约束）。
 */
public final class TemplateNameNormalizer {

    private TemplateNameNormalizer() {
    }

    /** 归一化：去首尾空白 + 小写（Locale.ROOT，与 MySQL LOWER() 对常规字符等价）。 */
    public static String normalize(final String name) {
        return name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
    }
}
