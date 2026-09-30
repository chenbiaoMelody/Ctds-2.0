package com.ctds.catalog.infrastructure;

import com.ctds.catalog.domain.DatasetNameNormalizer;
import com.ctds.catalog.domain.TagTerm;
import com.ctds.catalog.domain.TagTermPort;
import com.ctds.catalog.domain.TagVocabulary;
import com.ctds.common.pagination.PageQuery;
import com.ctds.common.pagination.PageResult;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * 受控词表仓储（JdbcClient，ADR-009 迁移规范建表 V2；沿 {@code JdbcDatasetRepository} 先例）。
 *
 * <p>读面（R3/R4）与写面成员校验共用同一数据源：选词入口与准入口径同源，不可能漂移。
 * 成员校验 = 归一化差集（hifi §4.3）：入参经 {@link DatasetNameNormalizer} 归一化后与
 * 册内 {@code normalized_term} 做 {@code IN} 比对，大小写折叠由列排序规则 0900_ai_ci 承担
 * （应用层不额外 toLowerCase——单一口径）。</p>
 */
@Repository
public class JdbcTagTermRepository implements TagTermPort {

    private static final String VOCABULARY_COLUMNS = "vocabulary_code, vocabulary_name";
    private static final String TERM_COLUMNS = "term_code, term_name, normalized_term";
    /** LIKE 转义符（keyword 中的 %、_、! 一律转义——用户输入的通配符不作为通配符使用；
     * 取 '!' 而不用 '\'，免与 MySQL 字符串转义语义纠缠）。 */
    private static final char ESCAPE = '!';

    private final JdbcClient jdbc;

    public JdbcTagTermRepository(final JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<TagVocabulary> listVocabularies() {
        return jdbc.sql("SELECT " + VOCABULARY_COLUMNS + " FROM tag_vocabulary ORDER BY id ASC")
                .query((rs, rowNum) -> mapVocabulary(rs))
                .list();
    }

    @Override
    public PageResult<TagTerm> pageTerms(final String vocabularyCode, final String keyword,
            final PageQuery page) {
        final long vocabularyId = requireVocabularyId(vocabularyCode);
        final StringBuilder where = new StringBuilder("vocabulary_id = ?");
        final List<Object> params = new ArrayList<>();
        params.add(vocabularyId);
        if (keyword != null && !keyword.isBlank()) {
            where.append(" AND term_name LIKE ? ESCAPE '" + ESCAPE + "'");
            params.add("%" + escapeLike(keyword) + "%");
        }
        final long total = jdbc.sql("SELECT COUNT(*) FROM tag_term WHERE " + where)
                .params(params)
                .query(Long.class)
                .single();
        final List<Object> pageParams = new ArrayList<>(params);
        pageParams.add(page.pageSize());
        pageParams.add(page.offset());
        final List<TagTerm> list = jdbc.sql("SELECT " + TERM_COLUMNS + " FROM tag_term WHERE " + where
                        + " ORDER BY term_code ASC LIMIT ? OFFSET ?")
                .params(pageParams)
                .query((rs, rowNum) -> mapTerm(rs))
                .list();
        return PageResult.of(list, total, page);
    }

    @Override
    public List<String> findUnmatched(final String vocabularyCode, final List<String> tags) {
        if (tags == null || tags.isEmpty()) {
            return List.of();
        }
        final List<String> candidates = tags.stream()
                .map(DatasetNameNormalizer::normalize)
                .distinct()
                .toList();
        if (candidates.isEmpty()) {
            return List.of();
        }
        final Optional<Long> vocabularyId = findVocabularyId(vocabularyCode);
        if (vocabularyId.isEmpty()) {
            // 册缺失 = 无从判定成员 → 一律视为未命中（fail-closed：不放行未验证标签，业务 unavailable 也不冒充通过）
            return candidates;
        }
        final String placeholders = String.join(",", Collections.nCopies(candidates.size(), "?"));
        JdbcClient.StatementSpec spec = jdbc.sql("SELECT normalized_term FROM tag_term "
                        + "WHERE vocabulary_id = ? AND normalized_term IN (" + placeholders + ")")
                .param(vocabularyId.get());
        for (final String candidate : candidates) {
            spec = spec.param(candidate);
        }
        final List<String> matched = spec.query((rs, rowNum) -> rs.getString(1)).list();
        return candidates.stream().filter(candidate -> !matched.contains(candidate)).toList();
    }

    /** 册码 → 技术主键（册不存在 = 编码/维护缺陷，快速暴露而非静默空结果）。 */
    private long requireVocabularyId(final String vocabularyCode) {
        final Long id = findVocabularyId(vocabularyCode)
                .orElseThrow(() -> new IllegalStateException("受控词表册不存在：" + vocabularyCode));
        return id;
    }

    private Optional<Long> findVocabularyId(final String vocabularyCode) {
        return jdbc.sql("SELECT id FROM tag_vocabulary WHERE vocabulary_code = ?")
                .param(vocabularyCode)
                .query(Long.class)
                .optional();
    }

    private static TagVocabulary mapVocabulary(final ResultSet rs) throws SQLException {
        return new TagVocabulary(rs.getString("vocabulary_code"), rs.getString("vocabulary_name"));
    }

    private static TagTerm mapTerm(final ResultSet rs) throws SQLException {
        return new TagTerm(rs.getString("term_code"), rs.getString("term_name"),
                rs.getString("normalized_term"));
    }

    /** LIKE 转义：%、_ 与转义符本身失去通配语义（防用户输入的通配符放大或畸形结果）。 */
    private static String escapeLike(final String keyword) {
        final StringBuilder escaped = new StringBuilder(keyword.length() * 2);
        for (int i = 0; i < keyword.length(); i++) {
            final char ch = keyword.charAt(i);
            if (ch == '%' || ch == '_' || ch == ESCAPE) {
                escaped.append(ESCAPE);
            }
            escaped.append(ch);
        }
        return escaped.toString();
    }
}
