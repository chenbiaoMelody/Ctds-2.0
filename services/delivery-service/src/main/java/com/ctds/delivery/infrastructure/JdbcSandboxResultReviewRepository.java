package com.ctds.delivery.infrastructure;

import com.ctds.common.pagination.PageQuery;
import com.ctds.delivery.domain.SandboxResultReview;
import com.ctds.delivery.domain.SandboxResultReviewRepository;
import com.ctds.delivery.domain.SandboxResultStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** 审核留痕 JdbcClient 仓储（WBS-3.5.5 hifi §2.2；只追加）。 */
@Repository
public class JdbcSandboxResultReviewRepository implements SandboxResultReviewRepository {

    private static final String COLUMNS = "id, sandbox_no, file_name, review_round, conclusion, "
            + "reason, reviewed_by, created_at";

    private final JdbcClient jdbc;

    public JdbcSandboxResultReviewRepository(final JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insert(final SandboxResultReview review) {
        jdbc.sql("INSERT INTO sandbox_result_review (sandbox_no, file_name, review_round, "
                        + "conclusion, reason, reviewed_by, created_at) VALUES (:sandboxNo, "
                        + ":fileName, :round, :conclusion, :reason, :reviewedBy, :createdAt)")
                .param("sandboxNo", review.sandboxNo())
                .param("fileName", review.fileName())
                .param("round", review.reviewRound())
                .param("conclusion", review.conclusion().name())
                .param("reason", review.reason())
                .param("reviewedBy", review.reviewedBy())
                .param("createdAt", review.createdAt())
                .update();
    }

    @Override
    public List<SandboxResultReview> listBySandbox(final String sandboxNo, final String fileName,
            final PageQuery page) {
        final Map<String, Object> params = new HashMap<>();
        final StringBuilder sql = new StringBuilder("SELECT " + COLUMNS
                + " FROM sandbox_result_review WHERE sandbox_no = :sandboxNo");
        params.put("sandboxNo", sandboxNo);
        if (fileName != null && !fileName.isBlank()) {
            sql.append(" AND file_name = :fileName");
            params.put("fileName", fileName);
        }
        sql.append(" ORDER BY file_name, review_round LIMIT :limit OFFSET :offset");
        params.put("limit", page.pageSize());
        params.put("offset", page.offset());
        final var spec = jdbc.sql(sql.toString());
        params.forEach(spec::param);
        return spec.query(JdbcSandboxResultReviewRepository::map).list();
    }

    @Override
    public long countBySandbox(final String sandboxNo, final String fileName) {
        final Map<String, Object> params = new HashMap<>();
        final StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM sandbox_result_review "
                + "WHERE sandbox_no = :sandboxNo");
        params.put("sandboxNo", sandboxNo);
        if (fileName != null && !fileName.isBlank()) {
            sql.append(" AND file_name = :fileName");
            params.put("fileName", fileName);
        }
        final var spec = jdbc.sql(sql.toString());
        params.forEach(spec::param);
        return spec.query(Long.class).single();
    }

    private static SandboxResultReview map(final ResultSet rs, final int rowNum)
            throws SQLException {
        return new SandboxResultReview(rs.getLong("id"), rs.getString("sandbox_no"),
                rs.getString("file_name"), rs.getInt("review_round"),
                SandboxResultStatus.valueOf(rs.getString("conclusion")), rs.getString("reason"),
                rs.getString("reviewed_by"), rs.getObject("created_at", LocalDateTime.class));
    }
}
