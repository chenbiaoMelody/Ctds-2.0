package com.ctds.catalog.domain;

/**
 * 语义检索候选行（WBS-3.10.2 hifi §1）：产品目录行 + 来源资源语义标签原文
 * （dataset.semantic_tags JSON 数组字符串；标点由分词器忽略）。
 */
public record SemanticCandidateRow(CatalogProductRow product, String semanticTags) {
}
