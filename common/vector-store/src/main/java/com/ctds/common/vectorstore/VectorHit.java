package com.ctds.common.vectorstore;

/** 相似度命中（C-11.2 B3）：key + 余弦分数。 */
public record VectorHit(String key, double score) {
}
