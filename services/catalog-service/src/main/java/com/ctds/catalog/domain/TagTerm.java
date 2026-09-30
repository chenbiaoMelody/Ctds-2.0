package com.ctds.catalog.domain;

import java.util.regex.Pattern;

/**
 * 受控词条（WBS-3.3.3 hifi §3.2：tag_term 行 = 一个可选语义标签）。
 *
 * <p>术语：对外称"词条 / 标签词"，不引入第二套叫法（如"字典项""枚举值"——§6 边界声明 6）。
 * 归一化名 {@code normalizedTerm} 为成员校验口径唯一来源，由应用侧经
 * {@link DatasetNameNormalizer} 产出后写入DB（DB 与二次归一化均不再加工）。</p>
 */
public record TagTerm(String termCode, String termName, String normalizedTerm) {

    /** 词条编号形态：TT + 4 位序号（沿 data_no / subject_no 业务编号先例）。 */
    private static final Pattern TERM_CODE = Pattern.compile("^TT\\d{4}$");

    public TagTerm {
        if (termCode == null || !TERM_CODE.matcher(termCode).matches()) {
            throw new IllegalArgumentException("词条编号形态非法（TT + 4 位序号）");
        }
        if (termName == null || termName.isBlank()) {
            throw new IllegalArgumentException("词条名称不能为空");
        }
        if (normalizedTerm == null || normalizedTerm.isBlank()) {
            throw new IllegalArgumentException("归一化词条名不能为空");
        }
    }
}
