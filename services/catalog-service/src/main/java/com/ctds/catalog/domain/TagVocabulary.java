package com.ctds.catalog.domain;

/**
 * 受控词表册（WBS-3.3.3 hifi §3.1：tag_vocabulary 行 = 一册受控词集合）。
 *
 * <p>本版平台只有 {@link #SEMANTIC_TAG} 一册语义标签词表（TT + 4 位词条编号）；
 * 册码字段已预留多册扩展（如 3.3.4 分类类目若需另一册），但 V1.0 不做多册维护写面（Q7-A）。
 * 不含数据本体、主体信息与个人信息（行为 7 规则 5）。</p>
 */
public record TagVocabulary(String vocabularyCode, String vocabularyName) {

    /** 语义标签受控词表册码（V1.0 唯一册；常量化避免散落字面量）。 */
    public static final String SEMANTIC_TAG = "SEMANTIC_TAG";

    public TagVocabulary {
        if (vocabularyCode == null || vocabularyCode.isBlank()) {
            throw new IllegalArgumentException("词表册码不能为空");
        }
        if (vocabularyName == null || vocabularyName.isBlank()) {
            throw new IllegalArgumentException("词表册名不能为空");
        }
    }
}
