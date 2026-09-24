package com.ctds.did.infrastructure;

import com.ctds.did.domain.InteropLog;
import com.ctds.did.domain.InteropLogRepository;
import java.sql.Timestamp;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * 互认留痕 MySQL 仓储（JdbcClient，V3 迁移建表）：只写四要素与原因，不写业务数据原文。
 * 时间戳经应用时钟传入，不走 DB NOW()（ADR-017 §2.2 / DB-22 教训）。
 */
@Repository
public class InteropLogJdbcRepository implements InteropLogRepository {

    private final JdbcClient jdbc;

    public InteropLogJdbcRepository(final JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insert(final InteropLog log) {
        jdbc.sql("INSERT INTO did_interop_log (direction, peer_space, did, result, reason, occurred_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?)")
                .params(log.direction().name(), log.peerSpace(), log.did(), log.result().name(),
                        log.reason() == null ? null : log.reason().name(), Timestamp.valueOf(log.occurredAt()))
                .update();
    }
}
