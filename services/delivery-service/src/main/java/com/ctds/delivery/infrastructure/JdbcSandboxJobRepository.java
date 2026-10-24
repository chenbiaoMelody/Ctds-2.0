package com.ctds.delivery.infrastructure;

import com.ctds.common.pagination.PageQuery;
import com.ctds.delivery.domain.SandboxJob;
import com.ctds.delivery.domain.SandboxJobRepository;
import com.ctds.delivery.domain.SandboxJobStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** 沙箱作业 JdbcClient 仓储（hifi §2.2；不含 stdout/stderr 与产物内容）。 */
@Repository
public class JdbcSandboxJobRepository implements SandboxJobRepository {

    private static final String COLUMNS = "id, job_no, sandbox_no, job_id, status, exit_code, "
            + "duration_ms, purpose, territory, created_at, finished_at";
    private static final int SCAN_LIMIT = 200;

    private final JdbcClient jdbc;

    public JdbcSandboxJobRepository(final JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<SandboxJob> findByIdempotencyKey(final String sandboxNo,
            final String idempotencyKey) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM sandbox_job WHERE sandbox_no = :sandbox "
                        + "AND idempotency_key = :key")
                .param("sandbox", sandboxNo).param("key", idempotencyKey)
                .query(JdbcSandboxJobRepository::map).optional();
    }

    @Override
    public Optional<SandboxJob> findByJobNo(final String jobNo) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM sandbox_job WHERE job_no = :no")
                .param("no", jobNo).query(JdbcSandboxJobRepository::map).optional();
    }

    @Override
    @Transactional
    public String nextJobNo() {
        jdbc.sql("UPDATE sandbox_job_no_seq SET next_no = LAST_INSERT_ID(next_no + 1) WHERE id = 1")
                .update();
        final Long next = jdbc.sql("SELECT LAST_INSERT_ID()").query(Long.class).single();
        return String.format("SJ%06d", next);
    }

    @Override
    public void insert(final SandboxJob job) {
        jdbc.sql("INSERT INTO sandbox_job (job_no, sandbox_no, job_id, status, purpose, territory, "
                        + "idempotency_key, created_at) VALUES (:jobNo, :sandboxNo, :jobId, :status, "
                        + ":purpose, :territory, :idem, :createdAt)")
                .param("jobNo", job.jobNo())
                .param("sandboxNo", job.sandboxNo())
                .param("jobId", job.jobId())
                .param("status", job.status().name())
                .param("purpose", job.purpose())
                .param("territory", job.territory())
                .param("idem", job.idempotencyKey() == null ? "" : job.idempotencyKey())
                .param("createdAt", job.createdAt())
                .update();
    }

    @Override
    public void finish(final String jobNo, final SandboxJobStatus status, final Integer exitCode,
            final Long durationMs, final LocalDateTime finishedAt) {
        jdbc.sql("UPDATE sandbox_job SET status = :status, exit_code = :exitCode, "
                        + "duration_ms = :durationMs, finished_at = :finishedAt WHERE job_no = :no")
                .param("status", status.name())
                .param("exitCode", exitCode)
                .param("durationMs", durationMs)
                .param("finishedAt", finishedAt)
                .param("no", jobNo)
                .update();
    }

    @Override
    public List<SandboxJob> listBySandbox(final String sandboxNo, final PageQuery page) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM sandbox_job WHERE sandbox_no = :sandbox "
                        + "ORDER BY id DESC LIMIT :limit OFFSET :offset")
                .param("sandbox", sandboxNo)
                .param("limit", page.pageSize())
                .param("offset", page.offset())
                .query(JdbcSandboxJobRepository::map).list();
    }

    @Override
    public long countBySandbox(final String sandboxNo) {
        return jdbc.sql("SELECT COUNT(*) FROM sandbox_job WHERE sandbox_no = :sandbox")
                .param("sandbox", sandboxNo).query(Long.class).single();
    }

    @Override
    public int failRunning(final LocalDateTime finishedAt) {
        return jdbc.sql("UPDATE sandbox_job SET status = 'FAILED', finished_at = :finishedAt "
                        + "WHERE status = 'RUNNING' LIMIT :limit")
                .param("finishedAt", finishedAt).param("limit", SCAN_LIMIT)
                .update();
    }

    private static SandboxJob map(final ResultSet rs, final int rowNum) throws SQLException {
        final int exitCode = rs.getInt("exit_code");
        final Integer nullableExit = rs.wasNull() ? null : exitCode;
        final long duration = rs.getLong("duration_ms");
        final Long nullableDuration = rs.wasNull() ? null : duration;
        return new SandboxJob(rs.getLong("id"), rs.getString("job_no"), rs.getString("sandbox_no"),
                rs.getString("job_id"), SandboxJobStatus.valueOf(rs.getString("status")),
                nullableExit, nullableDuration, rs.getString("purpose"), rs.getString("territory"),
                null, rs.getObject("created_at", LocalDateTime.class),
                rs.getObject("finished_at", LocalDateTime.class));
    }
}
