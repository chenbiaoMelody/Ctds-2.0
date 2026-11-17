package com.ctds.common.vectorstore;

import java.util.List;
import java.util.Map;

/** 向量检索结果（C-11.2 B1/B3）：本次加载的向量记录（供缓存变更检测）+ 排序命中。 */
public record VectorSearchResult(Map<String, VectorRecord> vectors, List<VectorHit> hits) {

    public VectorSearchResult {
        vectors = vectors == null ? Map.of() : Map.copyOf(vectors);
        hits = hits == null ? List.of() : List.copyOf(hits);
    }
}
