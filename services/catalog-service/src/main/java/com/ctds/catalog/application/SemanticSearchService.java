package com.ctds.catalog.application;

import com.ctds.catalog.domain.CatalogBizException;
import com.ctds.catalog.domain.CatalogErrorCodes;
import com.ctds.catalog.domain.CatalogProductRow;
import com.ctds.catalog.domain.CategoryPort;
import com.ctds.catalog.domain.DataProductRepository;
import com.ctds.catalog.domain.SemanticCandidateRow;
import com.ctds.common.pagination.PageQuery;
import com.ctds.common.pagination.PageResult;
import com.ctds.common.vectorstore.EmbeddingProvider;
import com.ctds.common.vectorstore.EmbeddingTexts;
import com.ctds.common.vectorstore.VectorHit;
import com.ctds.common.vectorstore.VectorMath;
import com.ctds.common.vectorstore.VectorRecord;
import com.ctds.common.vectorstore.VectorSearchResult;
import com.ctds.common.vectorstore.VectorStore;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * 目录语义检索应用服务（WBS-3.10.2 hifi §6）：资格 → 参数校验 → 候选 → 查询向量 →
 * 惰性补算/回写 → 排序 → 分页；仅 LISTED；不写业务留痕、不记录查询原文。
 */
@Service
public class SemanticSearchService {

    static final int QUERY_MAX_LENGTH = 64;

    private static final Comparator<ScoredCandidate> RANKING = Comparator
            .comparingDouble(ScoredCandidate::score).reversed()
            .thenComparing(sc -> sc.candidate().product().listedAt(),
                    Comparator.nullsLast(Comparator.<LocalDateTime>reverseOrder()))
            .thenComparing(Comparator.comparingLong(
                    (ScoredCandidate sc) -> sc.candidate().product().productId()).reversed());

    private final CatalogAccessGuard guard;
    private final CategoryPort categoryPort;
    private final DataProductRepository dataProductRepository;
    private final VectorStore vectorStore;
    private final EmbeddingProvider embeddingProvider;

    public SemanticSearchService(final CatalogAccessGuard guard, final CategoryPort categoryPort,
            final DataProductRepository dataProductRepository, final VectorStore vectorStore,
            final EmbeddingProvider embeddingProvider) {
        this.guard = guard;
        this.categoryPort = categoryPort;
        this.dataProductRepository = dataProductRepository;
        this.vectorStore = vectorStore;
        this.embeddingProvider = embeddingProvider;
    }

    public PageResult<CatalogProductRow> search(final String q, final String categoryCode,
            final PageQuery page) {
        final String subject = guard.requireSubject();
        guard.requireAdmitted(subject);
        final String query = q == null ? "" : q.trim();
        if (query.isEmpty() || query.length() > QUERY_MAX_LENGTH) {
            throw invalidParam();
        }
        List<String> subtreeCodes = null;
        if (categoryCode != null && !categoryCode.isBlank()) {
            if (!categoryPort.existsByCode(categoryCode)) {
                throw invalidParam();
            }
            subtreeCodes = categoryPort.selfAndDescendantCodes(categoryCode);
        }
        final List<SemanticCandidateRow> candidates =
                dataProductRepository.listListedSemanticCandidates(subtreeCodes);
        if (candidates.isEmpty()) {
            return PageResult.empty(page);
        }
        final float[] queryVector = embeddingProvider.embed(query);
        if (VectorMath.isZero(queryVector)) {
            return PageResult.empty(page);
        }
        final List<String> keys = new ArrayList<>(candidates.size());
        for (final SemanticCandidateRow candidate : candidates) {
            keys.add(String.valueOf(candidate.product().productId()));
        }
        VectorSearchResult result = vectorStore.search(queryVector, keys);
        final List<VectorRecord> upserts = new ArrayList<>();
        for (final SemanticCandidateRow candidate : candidates) {
            final String key = String.valueOf(candidate.product().productId());
            final String sourceText = sourceTextOf(candidate);
            final String sourceHash =
                    EmbeddingTexts.sourceHash(EmbeddingTexts.canonicalize(sourceText));
            final VectorRecord cached = result.vectors().get(key);
            if (cached == null || !sourceHash.equals(cached.sourceHash())
                    || cached.vector().length != embeddingProvider.dimensions()) {
                upserts.add(new VectorRecord(key, sourceHash, embeddingProvider.embed(sourceText)));
            }
        }
        if (!upserts.isEmpty()) {
            vectorStore.upsertAll(upserts);
            result = vectorStore.search(queryVector, keys);
        }
        final Map<String, Double> scores = new HashMap<>();
        for (final VectorHit hit : result.hits()) {
            scores.put(hit.key(), hit.score());
        }
        final List<ScoredCandidate> scored = new ArrayList<>(candidates.size());
        for (final SemanticCandidateRow candidate : candidates) {
            final Double score = scores.get(String.valueOf(candidate.product().productId()));
            if (score != null) {
                scored.add(new ScoredCandidate(candidate, score));
            }
        }
        scored.sort(RANKING);
        final int fromIndex = (int) Math.min(page.offset(), scored.size());
        final int toIndex = Math.min(fromIndex + page.pageSize(), scored.size());
        final List<CatalogProductRow> pageRows = scored.subList(fromIndex, toIndex).stream()
                .map(sc -> sc.candidate().product()).toList();
        return PageResult.of(pageRows, scored.size(), page);
    }

    private static CatalogBizException invalidParam() {
        return new CatalogBizException(CatalogErrorCodes.CATALOG_SEARCH_PARAM_INVALID,
                CatalogErrorCodes.CATALOG_SEARCH_PARAM_INVALID_MESSAGE);
    }

    private static String sourceTextOf(final SemanticCandidateRow candidate) {
        final String intro = candidate.product().intro() == null ? "" : candidate.product().intro();
        final String tags = candidate.semanticTags() == null ? "" : candidate.semanticTags();
        return candidate.product().productName() + "\n" + intro + "\n" + tags;
    }

    private record ScoredCandidate(SemanticCandidateRow candidate, double score) {
    }
}
