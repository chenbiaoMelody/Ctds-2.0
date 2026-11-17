package com.ctds.common.vectorstore;

/**
 * 向量化抽象（C-11.2 B1）：文本转固定维度向量；实现必须确定性、无外部调用。
 */
public interface EmbeddingProvider {

    /** 向量维度（V1.0 默认 384）。 */
    int dimensions();

    /** 文本向量化；null/空白/无有效词元 → 零向量。 */
    float[] embed(String text);
}
