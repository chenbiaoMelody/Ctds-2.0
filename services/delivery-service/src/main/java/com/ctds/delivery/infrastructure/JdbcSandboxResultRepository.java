package com.ctds.delivery.infrastructure;

import com.ctds.delivery.domain.SandboxResult;
import com.ctds.delivery.domain.SandboxResultRepository;
import com.ctds.delivery.domain.SandboxResultStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** 结果物登记 JdbcClient 仓储（WBS-3.5.5 hifi §2.1；无内容/路径/密钥材料）。 */
@Repository
public class JdbcSandboxResultRepository implements SandboxResultRepository {

    private static final String COLUMNS = "id, sandbox_no, file_name, status, review_round, "
            + "size_bytes, checksum, submitted_by, submitted_at, reviewed_by, reviewed_at, "
            + "reject_reason, idempotency_key, created_at, updated_at";

    private final JdbcClient jdbc;

    public JdbcSandboxResultRepository(final JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<SandboxResult> find(final String sandboxNo, final String fileName) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM sandbox_result WHERE sandbox_no = :sandbox "
                        + "AND file_name = :fileName")
                .param("sandbox", sandboxNo).param("fileName", fileName)
                .query(JdbcSandboxResultRepository::map).optional();
    }

    @Override
    public List<SandboxResult> findBySandbox(final String sandboxNo) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM sandbox_result WHERE sandbox_no = :sandbox "
                        + "ORDER BY file_name")
                .param("sandbox", sandboxNo)
                .query(JdbcSandboxResultRepository::map).list();
    }

    @Override
    public void insert(final SandboxResult result) {
        jdbc.sql("INSERT INTO sandbox_result (sandbox_no, file_name, status, review_round, "
                        + "size_bytes, checksum, submitted_by, submitted_at, idempotency_key, "
                        + "created_at, updated_at) VALUES (:sandboxNo, :fileName, :status, :round, "
                        + ":sizeBytes, :checksum, :submittedBy, :submittedAt, :idempotencyKey, "
                        + ":now, :now)")
                .param("sandboxNo", result.sandboxNo())
                .param("fileName", result.fileName())
                .param("status", result.status().name())
                .param("round", result.reviewRound())
                .param("sizeBytes", result.sizeBytes())
                .param("checksum", result.checksum())
                .param("submittedBy", result.submittedBy())
                .param("submittedAt", result.submittedAt())
                .param("idempotencyKey", result.idempotencyKey())
                .param("now", result.submittedAt())
                .update();
    }

    @Override
    public int resubmit(final SandboxResult result) {
        return jdbc.sql("UPDATE sandbox_result SET status = 'PENDING', review_round = :round, "
                        + "size_bytes = :sizeBytes, checksum = :checksum, submitted_by = "
                        + ":submittedBy, submitted_at = :submittedAt, reviewed_by = NULL, "
                        + "reviewed_at = NULL, reject_reason = NULL, idempotency_key = "
                        + ":idempotencyKey, updated_at = :updatedAt WHERE sandbox_no = :sandbox "
                        + "AND file_name = :fileName AND status = 'REJECTED'")
                .param("round", result.reviewRound())
                .param("sizeBytes", result.sizeBytes())
                .param("checksum", result.checksum())
                .param("submittedBy", result.submittedBy())
                .param("submittedAt", result.submittedAt())
                .param("idempotencyKey", result.idempotencyKey())
                .param("updatedAt", result.submittedAt())
                .param("sandbox", result.sandboxNo())
                .param("fileName", result.fileName())
                .update();
    }

    @Override
    public int conclude(final SandboxResult concluded) {
        return jdbc.sql("UPDATE sandbox_result SET status = :status, reviewed_by = :reviewedBy, "
                        + "reviewed_at = :reviewedAt, reject_reason = :rejectReason, updated_at = "
                        + ":reviewedAt WHERE sandbox_no = :sandbox AND file_name = :fileName "
                        + "AND status = 'PENDING'")
                .param("status", concluded.status().name())
                .param("reviewedBy", concluded.reviewedBy())
                .param("reviewedAt", concluded.reviewedAt())
                .param("rejectReason", concluded.rejectReason())
                .param("sandbox", concluded.sandboxNo())
                .param("fileName", concluded.fileName())
                .update();
    }

    private static SandboxResult map(final ResultSet rs, final int rowNum) throws SQLException {
        return new SandboxResult(rs.getLong("id"), rs.getString("sandbox_no"),
                rs.getString("file_name"), SandboxResultStatus.valueOf(rs.getString("status")),
                rs.getInt("review_round"), rs.getLong("size_bytes"), rs.getString("checksum"),
                rs.getString("submitted_by"), rs.getObject("submitted_at", LocalDateTime.class),
                rs.getString("reviewed_by"), rs.getObject("reviewed_at", LocalDateTime.class),
                rs.getString("reject_reason"), rs.getString("idempotency_key"),
                rs.getObject("created_at", LocalDateTime.class),
                rs.getObject("updated_at", LocalDateTime.class));
    }
}
