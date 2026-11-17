package com.ctds.common.vectorstore;

import java.util.Collection;
import java.util.Map;

/**
 * 向量存储与检索抽象（C-11.2 B1；ADR-003 §5）：首版实现 = MySQL 缓存表；未来 pgvector/Milvus
 * 适配器可覆写 {@link #search} 使用原生索引检索。
 */
public interface VectorStore {

    /** 按 key 批量读取；缺失 key 不返回。 */
    Map<String, VectorRecord> findAll(Collection<String> keys);

    /** 按 key 幂等写入（覆盖同 key）。 */
    void upsertAll(Collection<VectorRecord> records);

    /** 默认检索 = 加载候选向量 + 纯函数排序；适配器可按需覆写。 */
    default VectorSearchResult search(final float[] queryVector, final Collection<String> keys) {
        final Map<String, VectorRecord> vectors = findAll(keys);
        return new VectorSearchResult(vectors, VectorMath.rank(queryVector, vectors));
    }
}
