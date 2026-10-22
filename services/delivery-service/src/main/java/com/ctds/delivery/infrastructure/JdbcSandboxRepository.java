package com.ctds.delivery.infrastructure;

import com.ctds.common.errorcode.BizException;
import com.ctds.common.errorcode.ErrorCodes;
import com.ctds.common.pagination.PageQuery;
import com.ctds.delivery.domain.SandboxActionLog;
import com.ctds.delivery.domain.SandboxRepository;
import com.ctds.delivery.domain.SandboxStatus;
import com.ctds.delivery.domain.SandboxTask;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** 沙箱任务 JdbcClient 仓储（hifi §2.1；无容器 / 路径 / 内容 / 密钥材料）。 */
@Repository
public class JdbcSandboxRepository implements SandboxRepository {

    private static final String COLUMNS = "id, sandbox_no, contract_no, product_id, product_name, "
            + "requester_no, provider_no, status, cpus, memory_mb, ttl_seconds, expires_at, "
            + "data_file_count, close_reason, idempotency_key, created_at";
    private static final Map<String, String> SORT_COLUMNS = Map.of(
            "id", "id",
            "createdAt", "created_at",
            "created_at", "created_at",
            "sandboxNo", "sandbox_no",
            "sandbox_no", "sandbox_no",
            "status", "status");
    private static final int SCAN_LIMIT = 100;

    private final JdbcClient jdbc;

    public JdbcSandboxRepository(final JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<SandboxTask> findByIdempotencyKey(final String requesterNo,
            final String idempotencyKey) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM sandbox_task WHERE requester_no = :requester "
                        + "AND idempotency_key = :key")
                .param("requester", requesterNo).param("key", idempotencyKey)
                .query(JdbcSandboxRepository::map).optional();
    }

    @Override
    public Optional<SandboxTask> findBySandboxNo(final String sandboxNo) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM sandbox_task WHERE sandbox_no = :no")
                .param("no", sandboxNo).query(JdbcSandboxRepository::map).optional();
    }

    @Override
    @Transactional
    public String nextSandboxNo() {
        jdbc.sql("UPDATE sandbox_no_seq SET next_no = LAST_INSERT_ID(next_no + 1) WHERE id = 1")
                .update();
        final Long next = jdbc.sql("SELECT LAST_INSERT_ID()").query(Long.class).single();
        return String.format("SB%06d", next);
    }

    @Override
    public void insert(final SandboxTask task) {
        jdbc.sql("INSERT INTO sandbox_task (sandbox_no, contract_no, product_id, product_name, "
                        + "requester_no, provider_no, status, cpus, memory_mb, ttl_seconds, expires_at, "
                        + "data_file_count, idempotency_key, created_at, updated_at) VALUES "
                        + "(:sandboxNo, :contractNo, :productId, :productName, :requester, :provider, "
                        + ":status, :cpus, :memoryMb, :ttlSeconds, :expiresAt, :dataFileCount, :idem, "
                        + ":createdAt, :createdAt)")
                .param("sandboxNo", task.sandboxNo())
                .param("contractNo", task.contractNo())
                .param("productId", task.productId())
                .param("productName", task.productName())
                .param("requester", task.requesterNo())
                .param("provider", task.providerNo())
                .param("status", task.status().name())
                .param("cpus", task.cpus())
                .param("memoryMb", task.memoryMb())
                .param("ttlSeconds", task.ttlSeconds())
                .param("expiresAt", task.expiresAt())
                .param("dataFileCount", task.dataFileCount())
                .param("idem", task.idempotencyKey() == null ? "" : task.idempotencyKey())
                .param("createdAt", task.createdAt())
                .update();
    }

    @Override
    public int close(final String sandboxNo, final SandboxStatus status, final String closeReason,
            final LocalDateTime updatedAt) {
        return jdbc.sql("UPDATE sandbox_task SET status = :status, close_reason = :reason, "
                        + "updated_at = :updatedAt WHERE sandbox_no = :no AND status = 'RUNNING'")
                .param("status", status.name())
                .param("reason", closeReason)
                .param("updatedAt", updatedAt)
                .param("no", sandboxNo)
                .update();
    }

    @Override
    public int closeExpired(final LocalDateTime now, final String reason) {
        return jdbc.sql("UPDATE sandbox_task SET status = 'EXPIRED', close_reason = :reason, "
                        + "updated_at = :now WHERE status = 'RUNNING' AND expires_at <= :now")
                .param("reason", reason)
                .param("now", now)
                .update();
    }

    @Override
    public List<SandboxTask> listExpired(final LocalDateTime now, final int limit) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM sandbox_task WHERE status = 'RUNNING' "
                        + "AND expires_at <= :now ORDER BY id LIMIT :limit")
                .param("now", now).param("limit", Math.max(1, Math.min(limit, SCAN_LIMIT)))
                .query(JdbcSandboxRepository::map).list();
    }

    @Override
    public List<SandboxTask> listRunning(final int limit) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM sandbox_task WHERE status = 'RUNNING' "
                        + "ORDER BY id LIMIT :limit")
                .param("limit", Math.max(1, Math.min(limit, SCAN_LIMIT)))
                .query(JdbcSandboxRepository::map).list();
    }

    @Override
    public List<SandboxTask> listForParticipant(final String callerNo, final String contractNo,
            final SandboxStatus status, final PageQuery page) {
        final Map<String, Object> params = new HashMap<>();
        final StringBuilder sql = new StringBuilder("SELECT " + COLUMNS + " FROM sandbox_task WHERE "
                + "(requester_no = :caller OR provider_no = :caller)");
        params.put("caller", callerNo);
        appendFilters(sql, params, contractNo, status);
        sql.append(orderClause(page)).append(" LIMIT :limit OFFSET :offset");
        params.put("limit", page.pageSize());
        params.put("offset", page.offset());
        final var spec = jdbc.sql(sql.toString());
        params.forEach(spec::param);
        return spec.query(JdbcSandboxRepository::map).list();
    }

    @Override
    public long countForParticipant(final String callerNo, final String contractNo,
            final SandboxStatus status) {
        final Map<String, Object> params = new HashMap<>();
        final StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM sandbox_task WHERE "
                + "(requester_no = :caller OR provider_no = :caller)");
        params.put("caller", callerNo);
        appendFilters(sql, params, contractNo, status);
        final var spec = jdbc.sql(sql.toString());
        params.forEach(spec::param);
        return spec.query(Long.class).single();
    }

    @Override
    public List<SandboxTask> listGovernance(final String contractNo, final SandboxStatus status,
            final PageQuery page) {
        final Map<String, Object> params = new HashMap<>();
        final StringBuilder sql = new StringBuilder("SELECT " + COLUMNS + " FROM sandbox_task WHERE 1 = 1");
        appendFilters(sql, params, contractNo, status);
        sql.append(orderClause(page)).append(" LIMIT :limit OFFSET :offset");
        params.put("limit", page.pageSize());
        params.put("offset", page.offset());
        final var spec = jdbc.sql(sql.toString());
        params.forEach(spec::param);
        return spec.query(JdbcSandboxRepository::map).list();
    }

    @Override
    public long countGovernance(final String contractNo, final SandboxStatus status) {
        final Map<String, Object> params = new HashMap<>();
        final StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM sandbox_task WHERE 1 = 1");
        appendFilters(sql, params, contractNo, status);
        final var spec = jdbc.sql(sql.toString());
        params.forEach(spec::param);
        return spec.query(Long.class).single();
    }

    @Override
    public int countActiveByRequester(final String requesterNo, final LocalDateTime now) {
        return jdbc.sql("SELECT COUNT(*) FROM sandbox_task WHERE requester_no = :requester "
                        + "AND status = 'RUNNING' AND expires_at > :now")
                .param("requester", requesterNo).param("now", now)
                .query(Integer.class).single();
    }

    @Override
    public int countActiveGlobal(final LocalDateTime now) {
        return jdbc.sql("SELECT COUNT(*) FROM sandbox_task WHERE status = 'RUNNING' "
                        + "AND expires_at > :now")
                .param("now", now).query(Integer.class).single();
    }

    @Override
    public void insertActionLog(final SandboxActionLog log) {
        jdbc.sql("INSERT INTO sandbox_action_log (sandbox_no, job_no, contract_no, operator_no, "
                        + "action, result, reason_code) VALUES (:sandboxNo, :jobNo, :contractNo, "
                        + ":operator, :action, :result, :reason)")
                .param("sandboxNo", log.sandboxNo())
                .param("jobNo", log.jobNo())
                .param("contractNo", log.contractNo())
                .param("operator", log.operatorNo())
                .param("action", log.action())
                .param("result", log.result())
                .param("reason", log.reasonCode())
                .update();
    }

    private static void appendFilters(final StringBuilder sql, final Map<String, Object> params,
            final String contractNo, final SandboxStatus status) {
        if (contractNo != null && !contractNo.isBlank()) {
            sql.append(" AND contract_no = :contractNo");
            params.put("contractNo", contractNo);
        }
        if (status != null) {
            sql.append(" AND status = :status");
            params.put("status", status.name());
        }
    }

    private static String orderClause(final PageQuery page) {
        if (page.sortField() == null) {
            return " ORDER BY id DESC";
        }
        final String column = SORT_COLUMNS.get(page.sortField());
        if (column == null) {
            throw new BizException(ErrorCodes.PARAM_INVALID, "orderBy 字段不支持");
        }
        final String direction = "desc".equalsIgnoreCase(page.sortDirection()) ? "DESC" : "ASC";
        return "id".equals(column)
                ? " ORDER BY id " + direction
                : " ORDER BY " + column + " " + direction + ", id DESC";
    }

    private static SandboxTask map(final ResultSet rs, final int rowNum) throws SQLException {
        return new SandboxTask(rs.getLong("id"), rs.getString("sandbox_no"),
                rs.getString("contract_no"), rs.getLong("product_id"), rs.getString("product_name"),
                rs.getString("requester_no"), rs.getString("provider_no"),
                SandboxStatus.valueOf(rs.getString("status")),
                rs.getBigDecimal("cpus") == null ? 0D : rs.getBigDecimal("cpus").doubleValue(),
                rs.getInt("memory_mb"), rs.getInt("ttl_seconds"),
                rs.getObject("expires_at", LocalDateTime.class), rs.getInt("data_file_count"),
                rs.getString("close_reason"), rs.getString("idempotency_key"),
                rs.getObject("created_at", LocalDateTime.class));
    }
}
