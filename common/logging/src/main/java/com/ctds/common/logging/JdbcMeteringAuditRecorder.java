package com.ctds.common.logging;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * 计量事件落库实现（ADR-022 §2.6 / 规格 C-7.1-7.2 B7-3 移交 3.7.5——WBS-3.7.5 hifi §5.1）：
 * action 白名单命中时异步 upsert metering_flow；file+jdbc 双写组合的 jdbc 腿。
 * record 沿 AsyncFileAuditRecorder 契约：不抛异常、不阻塞业务线程、null 静默。
 * 白名单在装配层承载——denied 等动作不命中不落库（B7-2 拒绝零计量结构性）；
 * 无合约归属的事件行（contractNo 缺失/兜底哨兵）丢弃 + 计数告警；SQL 异常 60 秒限频 ERROR
 * 后丢弃不重排队（V1.0 演示量级接受，文件侧双写存根可人工核，登记演进：重试/死信）。
 */
public class JdbcMeteringAuditRecorder implements AuditRecorder {

    static final String WHITELIST_ACTION = "gateway.delivery.metering";
    static final String SOURCE_TYPE = "GATEWAY_API";
    private static final long ERROR_LOG_INTERVAL_MILLIS = 60_000L;
    /** 网关 attributeOrUnknown 兜底哨兵（实测亲读 DeliveryMeteringGlobalFilter.UNKNOWN="-"）。 */
    private static final String UNKNOWN_CONTRACT = "-";
    /** 设计文档曾记 "unknown"——防御性并入（E2 勘误，hifi §5.1 注记）。 */
    private static final String UNKNOWN_CONTRACT_ALT = "unknown";

    private final JdbcTemplate jdbcTemplate;
    private final String insertSql;
    private final Clock clock;
    private final BlockingQueue<Row> queue;
    private final Thread writerThread;
    private final AtomicLong droppedCount = new AtomicLong();
    private final AtomicLong invalidCount = new AtomicLong();
    private volatile boolean accepting = true;
    private volatile long lastErrorLogMillis;

    /** 入队元素：落库一行所需契约字段（ADR-022 §2.6-2——无敏感原文）。 */
    record Row(String contractNo, String requesterNo, String sourceNo, String resource,
            Instant occurredAt) {
    }

    public JdbcMeteringAuditRecorder(final AuditProperties properties, final Clock clock) {
        this(properties, clock, new JdbcTemplate(new DriverManagerDataSource(
                requireJdbcUrl(properties.getJdbcUrl()), properties.getJdbcUsername(),
                properties.getJdbcPassword())));
    }

    JdbcMeteringAuditRecorder(final AuditProperties properties, final Clock clock,
            final JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        this.insertSql = "INSERT INTO " + requireTable(properties.getMeteringTable())
                + " (contract_no, requester_no, source_type, source_no, action, resource, "
                + "occurred_at) VALUES (?, ?, ?, ?, ?, ?, ?) ON DUPLICATE KEY UPDATE id = id";
        this.clock = clock;
        this.queue = new LinkedBlockingQueue<>(Math.max(1, properties.getQueueCapacity()));
        this.writerThread = new Thread(this::drainLoop, "ctds-metering-jdbc-writer");
        this.writerThread.setDaemon(true);
        this.writerThread.start();
    }

    @Override
    public void record(final AuditEvent event) {
        if (event == null) {
            return;
        }
        if (!WHITELIST_ACTION.equals(event.action())) {
            return; // 白名单未命中：文件腿（组合实现）已记录，落库职责不承担
        }
        final Map<String, String> detail = event.detail();
        final String contractNo = detail.get("contractNo");
        if (isUnusableContractNo(contractNo)) {
            warnInvalid();
            return; // 无合约归属的事件行无计量意义，不入流水
        }
        final String requesterNo = firstNonBlank(detail.get("subject"), event.actor());
        final String sourceNo = event.eventId() != null ? event.eventId()
                : UUID.randomUUID().toString();
        final Instant occurredAt = event.eventTime() != null ? event.eventTime() : clock.instant();
        if (!queue.offer(new Row(contractNo, requesterNo, sourceNo, detail.get("resource"),
                occurredAt))) {
            warnDrop();
        }
    }

    /** 落库一行（protected 以便测试注入阻塞/故障；SQL 语义 = uk 幂等 upsert）。 */
    protected void writeRow(final Row row) {
        jdbcTemplate.update(insertSql, row.contractNo(), row.requesterNo(), "GATEWAY_API",
                row.sourceNo(), "gateway.delivery.metering", row.resource(),
                java.sql.Timestamp.from(row.occurredAt()));
    }

    /** 优雅停机：停止接收后排空队列，最多等待 5 秒（沿文件实现模式）。 */
    public void shutdown() {
        accepting = false;
        writerThread.interrupt();
        try {
            writerThread.join(TimeUnit.SECONDS.toMillis(5));
        } catch (final InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    long droppedEventCount() {
        return droppedCount.get();
    }

    long invalidEventCount() {
        return invalidCount.get();
    }

    private static boolean isUnusableContractNo(final String contractNo) {
        return contractNo == null || contractNo.isBlank()
                || UNKNOWN_CONTRACT.equals(contractNo) || UNKNOWN_CONTRACT_ALT.equals(contractNo);
    }

    private static String firstNonBlank(final String primary, final String fallback) {
        if (primary != null && !primary.isBlank()) {
            return primary;
        }
        return (fallback == null || fallback.isBlank()) ? "anonymous" : fallback;
    }

    private void warnInvalid() {
        final long invalid = invalidCount.incrementAndGet();
        if (invalid == 1 || invalid % 100 == 0) {
            LoggerFactory.getLogger(JdbcMeteringAuditRecorder.class)
                    .warn("metering event without usable contractNo dropped, totalInvalid={}",
                            invalid);
        }
    }

    private void warnDrop() {
        final long drops = droppedCount.incrementAndGet();
        if (drops == 1 || drops % 100 == 0) {
            LoggerFactory.getLogger(JdbcMeteringAuditRecorder.class)
                    .warn("metering event queue full, event dropped, totalDrops={}", drops);
        }
    }

    private void drainLoop() {
        while (accepting || !queue.isEmpty()) {
            Row row;
            try {
                row = queue.poll(200, TimeUnit.MILLISECONDS);
            } catch (final InterruptedException ex) {
                Thread.currentThread().interrupt();
                row = queue.poll();
            }
            if (row != null) {
                writeSafely(row);
            }
        }
    }

    private void writeSafely(final Row row) {
        try {
            writeRow(row);
        } catch (final Exception ex) {
            final long now = clock.millis();
            if (now - lastErrorLogMillis >= ERROR_LOG_INTERVAL_MILLIS) {
                lastErrorLogMillis = now;
                org.slf4j.LoggerFactory.getLogger(JdbcMeteringAuditRecorder.class)
                        .error("metering flow upsert failed, event dropped", ex);
            }
        }
    }

    static String requireTable(final String table) {
        if (table == null || !table.matches("[A-Za-z0-9_]{1,64}")) {
            throw new IllegalArgumentException("ctds.audit.metering-table 必须是标识符形态");
        }
        return table;
    }

    static String requireJdbcUrl(final String jdbcUrl) {
        if (jdbcUrl == null || jdbcUrl.isBlank()) {
            throw new IllegalArgumentException("ctds.audit.jdbc-url 未配置（sink=metering-db 必填）");
        }
        return jdbcUrl;
    }
}
