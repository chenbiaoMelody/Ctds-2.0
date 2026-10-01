package com.ctds.catalog.infrastructure;

import com.ctds.catalog.domain.CatalogBizException;
import com.ctds.catalog.domain.CatalogErrorCodes;
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
 * 成员校验 = 归一化差集（hifi §4.3，差集在 DB 侧一次算出）：入参经 {@link DatasetNameNormalizer}
 * 归一化后与册内 {@code normalized_term} 做折叠比对，大小写折叠由列排序规则 0900_ai_ci 承担
 * （应用层不额外 toLowerCase——单一口径），返回未命中的候选原文。</p>
 */
@Repository
public class JdbcTagTermRepository implements TagTermPort {

    private static final String VOCABULARY_COLUMNS = "vocabulary_code, vocabulary_name";
    private static final String TERM_COLUMNS = "term_code, term_name, normalized_term";
    /** LIKE 转义符（keyword 中的 %、_、! 一律转义——用户输入的通配符不作为通配符使用；
     * 取 '!' 而不用 '\'，免与 MySQL 字符串转义语义纠缠）。 */
    private static final char ESCAPE = '!';    private final JdbcClient jdbc;

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
        // 未命中差集在 DB 侧一次算出（WBS-3.3.3 hifi §4.3 评审循环 1 补正）：命中判定只经
        // normalized_term = 候选 的 0900_ai_ci 折叠比对这一道口径。不可"查命中列值再在 Java 侧减"——
        // DB 判命中（'smartcity' 折叠命中 'SmartCity'）而 Java 按列值精确比对会误判为未命中（评审实证）。
        // VALUES 派生表携带候选原文，NOT EXISTS 过滤后返回未命中的候选原文。
        final String candidateRows = String.join(", ", Collections.nCopies(candidates.size(), "ROW(?)"));
        JdbcClient.StatementSpec spec = jdbc.sql("SELECT v.candidate FROM (VALUES " + candidateRows
                + ") AS v(candidate) WHERE NOT EXISTS (SELECT 1 FROM tag_term t "
                + "WHERE t.vocabulary_id = ? AND t.normalized_term = v.candidate)");
        for (final String candidate : candidates) {
            spec = spec.param(candidate);
        }
        return spec.param(vocabularyId.get())
                .query((rs, rowNum) -> rs.getString(1))
                .list();
    }

    /** 册码 → 技术主键（册不存在 = 读面路径码未命中 → 1007C0010/404，hifi §1.1 R4；
     * 与"册存在但 keyword 无命中 → 200 空列表"可分辨）。 */
    private long requireVocabularyId(final String vocabularyCode) {
        final Long id = findVocabularyId(vocabularyCode)
                .orElseThrow(() -> new CatalogBizException(CatalogErrorCodes.TAG_VOCABULARY_NOT_FOUND,
                        CatalogErrorCodes.TAG_VOCABULARY_NOT_FOUND_MESSAGE));
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

    /** LIKE 转义：%、_ 与转义符本身失去通配语义（防用户输入的通配符放大或畸形结果）。
     * 包内共享（JdbcDataProductRepository 检索同用——DB-33 收敛方向：不新增第二副本）。 */
    static String escapeLike(final String keyword) {
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
