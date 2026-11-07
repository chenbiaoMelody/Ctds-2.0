package com.ctds.evidence.infrastructure;

import com.ctds.evidence.domain.AccessLogEntry;
import com.ctds.evidence.domain.AccessLogRepository;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * 审计调阅 / 导出留痕仓储（WBS-3.8.4 hifi §2；append-only——仅 insert，无更新 / 删除入口，
 * T21 源码断言机器可证）。
 */
@Repository
public class JdbcAccessLogRepository implements AccessLogRepository {

    private final JdbcClient jdbc;

    public JdbcAccessLogRepository(final JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insert(final AccessLogEntry entry) {
        jdbc.sql("INSERT INTO evidence_access_log (access_kind, accessed_by, conditions_json, "
                        + "result_count, purpose, created_at) VALUES (?, ?, ?, ?, ?, ?)")
                .params(entry.accessKind(), entry.accessedBy(), entry.conditionsJson(),
                        entry.resultCount(), entry.purpose(), toLocal(entry.createdAt()))
                .update();
    }

    private static LocalDateTime toLocal(final Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
