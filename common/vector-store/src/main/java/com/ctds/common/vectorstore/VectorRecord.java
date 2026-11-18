package com.ctds.common.vectorstore;

/** 向量存储记录（C-11.2 B1）：key + 索引源文本指纹 + 向量。 */
public record VectorRecord(String key, String sourceHash, float[] vector) {
}
