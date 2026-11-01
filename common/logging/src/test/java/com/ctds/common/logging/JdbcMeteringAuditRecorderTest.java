package com.ctds.common.logging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 计量事件落库实现单测（hifi §8 TC1~TC3）：白名单命中/未命中分支；detail 契约取值与
 * 无合约归属事件丢弃 + 计数告警；SQL upsert 幂等语义（mock JdbcTemplate）；SQL 异常限频
 * 丢弃不重排队；队列满丢弃计数。record 契约 = 不抛异常、不阻塞、null 静默。
 */
class JdbcMeteringAuditRecorderTest {

    private static final Instant NOW = Instant.parse("2026-10-11T09:00:00Z");
    private static final String WHITELIST = "gateway.delivery.metering";

    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private JdbcTemplate template;
    private AuditProperties properties;

    @BeforeEach
    void setUp() {
        template = mock(JdbcTemplate.class);
        doReturn(1).when(template).update(anyString(), any(Object[].class));
        properties = new AuditProperties();
        properties.setJdbcUrl("jdbc:mysql://localhost:3306/ctds_metering");
        properties.setJdbcUsername("audit-writer");
        properties.setJdbcPassword("secret-not-in-repo");
    }

    private AuditEvent meteringEvent(final String eventId, final String contractNo) {
        final Map<String, String> detail = new LinkedHashMap<>();
        detail.put("traceId", "trace-1");
        detail.put("subject", "S-req-1");
        detail.put("contractNo", contractNo);
        detail.put("resource", "delivery-catalog-products GET /x");
        detail.put("result", "ALLOWED");
        return new AuditEvent(eventId, NOW, "S-req-1", WHITELIST, "delivery", contractNo,
                AuditOutcome.SUCCESS, detail);
    }

    /** TC1 白名单命中：detail 契约键落列、uk 幂等 upsert 语义、source_no=eventId。 */
    @Test
    void whitelistEventUpsertedWithContractFields() {
        final JdbcMeteringAuditRecorder recorder =
                new JdbcMeteringAuditRecorder(properties, clock, template);
        recorder.record(meteringEvent("evt-1", "CO100001"));
        recorder.shutdown();
        verify(template).update(contains("INSERT INTO metering_flow"),
                eq("CO100001"), eq("S-req-1"), eq("GATEWAY_API"), eq("evt-1"),
                eq(WHITELIST), eq("delivery-catalog-products GET /x"),
                eq(Timestamp.from(NOW)));
        verify(template).update(contains("ON DUPLICATE KEY UPDATE id = id"),
                any(), any(), any(), any(), any(), any(), any());
    }

    /** TC1 兜底 enrich：eventId/eventTime 缺省由实现补填（UUID + 时钟）。 */
    @Test
    void eventIdAndTimeEnrichedWhenAbsent() {
        final AtomicReference<Object[]> args = new AtomicReference<>();
        doAnswer(invocation -> {
            args.set(invocation.getArguments());
            return 1;
        }).when(template).update(anyString(), any(Object[].class));
        final JdbcMeteringAuditRecorder recorder =
                new JdbcMeteringAuditRecorder(properties, clock, template);
        recorder.record(new AuditEvent(null, null, "S-req-1", WHITELIST, "delivery", "CO100002",
                AuditOutcome.SUCCESS, Map.of("contractNo", "CO100002", "subject", "S-req-1")));
        recorder.shutdown();
        verify(template).update(anyString(), any(Object[].class));
        final Object[] captured = args.get();
        // varargs 展开后 = [sql, contract_no, requester_no, source_type, source_no, action,
        //  resource, occurred_at]（upsert 七参 + SQL 一参）
        assertThat(captured).isNotNull();
        assertThat(captured).hasSize(8);
        assertThat(captured[1]).isEqualTo("CO100002");
        assertThat(captured[3]).as("source_type 常量").isEqualTo("GATEWAY_API");
        assertThat((String) captured[4]).as("sourceNo = 补填 eventId（UUID）").isNotBlank();
        assertThat(captured[7]).as("occurred_at = 补填事件时点（时钟）").isEqualTo(Timestamp.from(NOW));
    }

    /** TC2 白名单未命中：denied 等动作不命中不落库（B7-2 拒绝零计量结构性）。 */
    @Test
    void nonWhitelistActionIgnored() {
        final JdbcMeteringAuditRecorder recorder =
                new JdbcMeteringAuditRecorder(properties, clock, template);
        recorder.record(new AuditEvent("evt-2", NOW, "S-req-1", "gateway.delivery.denied",
                "delivery", "CO100001", AuditOutcome.DENIED, Map.of("contractNo", "CO100001")));
        recorder.shutdown();
        verify(template, never()).update(anyString(), any(Object[].class));
    }

    /** record 契约：null 事件静默。 */
    @Test
    void nullEventIgnoredSilently() {
        final JdbcMeteringAuditRecorder recorder =
                new JdbcMeteringAuditRecorder(properties, clock, template);
        recorder.record(null);
        recorder.shutdown();
        verify(template, never()).update(anyString(), any(Object[].class));
    }

    /** TC2 detail 契约边界：contractNo 缺失/兜底哨兵（null/空白/"-"/"unknown"）丢弃 + 计数告警
     *  ——无合约归属的事件行无计量意义不入流水（E2 勘误：网关兜底哨兵实测 "-"）。 */
    @Test
    void unusableContractNoDroppedAndCounted() {
        final JdbcMeteringAuditRecorder recorder =
                new JdbcMeteringAuditRecorder(properties, clock, template);
        recorder.record(meteringEvent("evt-3", null));
        recorder.record(meteringEvent("evt-4", " "));
        recorder.record(meteringEvent("evt-5", "-"));
        recorder.record(meteringEvent("evt-6", "unknown"));
        recorder.shutdown();
        verify(template, never()).update(anyString(), any(Object[].class));
        assertThat(recorder.invalidEventCount()).isEqualTo(4);
    }

    /** SQL 异常：限频 ERROR、事件丢弃不重排队（V1.0 登记演进：重试/死信），后续事件仍处理。 */
    @Test
    void sqlFailureDroppedWithoutThrowing() {
        doThrow(new IllegalStateException("db down")).when(template)
                .update(anyString(), any(Object[].class));
        final JdbcMeteringAuditRecorder recorder =
                new JdbcMeteringAuditRecorder(properties, clock, template);
        recorder.record(meteringEvent("evt-7", "CO100003"));
        verify(template, timeout(2000)).update(anyString(), any(Object[].class));
        doReturn(1).when(template).update(anyString(), any(Object[].class));
        recorder.record(meteringEvent("evt-8", "CO100004"));
        verify(template, timeout(2000).times(2)).update(anyString(), any(Object[].class));
        recorder.shutdown();
    }

    /** 有界队列：满丢弃计数告警（阻塞写线程制造确定性窗口）。 */
    @Test
    void queueOverflowDropsAndCounts() throws Exception {
        properties.setQueueCapacity(1);
        final CountDownLatch blocked = new CountDownLatch(1);
        final AtomicBoolean writerBusy = new AtomicBoolean(false);
        final AtomicInteger writes = new AtomicInteger();
        final JdbcMeteringAuditRecorder recorder =
                new JdbcMeteringAuditRecorder(properties, clock, template) {
                    @Override
                    protected void writeRow(final Row row) {
                        writes.incrementAndGet();
                        writerBusy.set(true);
                        try {
                            blocked.await(5, TimeUnit.SECONDS);
                        } catch (final InterruptedException ex) {
                            Thread.currentThread().interrupt();
                        }
                    }
                };
        recorder.record(meteringEvent("evt-9", "CO100005"));
        awaitTrue(writerBusy::get, "写线程未进入阻塞写");
        recorder.record(meteringEvent("evt-10", "CO100005"));
        recorder.record(meteringEvent("evt-11", "CO100005"));
        assertThat(recorder.droppedEventCount()).as("队列满丢弃恰 1 条").isEqualTo(1);
        blocked.countDown();
        recorder.shutdown();
        assertThat(writes.get()).as("已入队事件照常落库").isEqualTo(2);
    }

    /** shutdown 优雅排空：停止接收后排空在途事件（沿文件实现 5 秒预算）。 */
    @Test
    void shutdownDrainsPending() {
        final JdbcMeteringAuditRecorder recorder =
                new JdbcMeteringAuditRecorder(properties, clock, template);
        recorder.record(meteringEvent("evt-12", "CO100006"));
        recorder.shutdown();
        verify(template, timeout(2000)).update(anyString(), any(Object[].class));
    }

    /** 表名配置化（hifi §5.1）：ctds.audit.metering-table 生效。 */
    @Test
    void tableFromConfiguration() {
        properties.setMeteringTable("custom_metering_flow");
        final JdbcMeteringAuditRecorder recorder =
                new JdbcMeteringAuditRecorder(properties, clock, template);
        recorder.record(meteringEvent("evt-13", "CO100007"));
        recorder.shutdown();
        verify(template).update(contains("INSERT INTO custom_metering_flow"),
                any(), any(), any(), any(), any(), any(), any());
    }

    /** 配置 fail-fast：表名非标识符 / jdbc-url 缺失 = 构造即失败（不留运行时注入面）。 */
    @Test
    void invalidConfigurationFailsFast() {
        properties.setMeteringTable("bad name");
        assertThatThrownBy(() -> new JdbcMeteringAuditRecorder(properties, clock, template))
                .isInstanceOf(IllegalArgumentException.class);
        properties.setMeteringTable("metering_flow");
        properties.setJdbcUrl(" ");
        // URL 校验在公共构造器（自持数据源构建）——三参测试构造器不经该校验
        assertThatThrownBy(() -> new JdbcMeteringAuditRecorder(properties, clock))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static void awaitTrue(final BooleanSupplier condition, final String message) {
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        try {
            while (!condition.getAsBoolean()) {
                if (System.nanoTime() > deadline) {
                    throw new AssertionError(message);
                }
                TimeUnit.MILLISECONDS.sleep(10);
            }
        } catch (final InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new AssertionError(message);
        }
    }
}
