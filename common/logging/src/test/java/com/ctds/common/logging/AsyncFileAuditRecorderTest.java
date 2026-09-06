package com.ctds.common.logging;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.ctds.common.api.ApiResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.MDC;

/**
 * B4/B5/B6：审计事件异步落盘——JSONL 行格式、按天滚动、队列满丢弃、IO 故障不连坐业务（ADR-005 §3 第 6 项）。
 */
class AsyncFileAuditRecorderTest {

    private static final Instant NOW = Instant.parse("2026-09-06T10:15:30Z");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ListAppender<ILoggingEvent> logCapture = new ListAppender<>();
    private Logger recorderLogger;

    @AfterEach
    void detachLogCapture() {
        if (recorderLogger != null) {
            recorderLogger.detachAppender(logCapture);
        }
    }

    @Test
    void recordShouldWriteJsonLineWithAllFields(@TempDir final Path tempDir) throws Exception {
        final AsyncFileAuditRecorder recorder = newRecorder(tempDir, new SettableClock(NOW));
        try {
            recorder.record(AuditEvent.of("alice", "greeting.create", "greeting", "g-1",
                    AuditOutcome.SUCCESS, Map.of("channel", "web")));
        } finally {
            recorder.shutdown();
        }

        final Path file = tempDir.resolve("audit-2026-09-06.jsonl");
        final List<String> lines = Files.readAllLines(file);
        assertEquals(1, lines.size());
        final JsonNode json = MAPPER.readTree(lines.get(0));
        assertFalse(json.get("eventId").asText().isBlank());
        assertEquals("2026-09-06T10:15:30Z", json.get("eventTime").asText());
        assertEquals("test-service", json.get("service").asText());
        assertEquals("alice", json.get("actor").asText());
        assertEquals("greeting.create", json.get("action").asText());
        assertEquals("greeting", json.get("targetType").asText());
        assertEquals("g-1", json.get("targetId").asText());
        assertEquals("SUCCESS", json.get("outcome").asText());
        assertEquals("web", json.get("detail").get("channel").asText());
        assertFalse(json.hasNonNull("traceId"));
    }

    @Test
    void recordShouldCaptureMdcTraceIdAtCallTime(@TempDir final Path tempDir) throws Exception {
        final AsyncFileAuditRecorder recorder = newRecorder(tempDir, new SettableClock(NOW));
        MDC.put(ApiResult.TRACE_MDC_KEY, "t-9");
        try {
            recorder.record(AuditEvent.of("alice", "greeting.create", "greeting", "g-1",
                    AuditOutcome.SUCCESS, null));
        } finally {
            MDC.remove(ApiResult.TRACE_MDC_KEY);
            recorder.shutdown();
        }
        final JsonNode json = readSingleLine(tempDir.resolve("audit-2026-09-06.jsonl"));
        assertEquals("t-9", json.get("traceId").asText());
    }

    @Test
    void recordNullEventShouldBeIgnored(@TempDir final Path tempDir) {
        final AsyncFileAuditRecorder recorder = newRecorder(tempDir, new SettableClock(NOW));
        try {
            assertDoesNotThrow(() -> recorder.record(null));
        } finally {
            recorder.shutdown();
        }
        try (var files = Files.list(tempDir)) {
            assertEquals(0, files.count());
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    @Test
    void recordShouldRollToNewFileNextDay(@TempDir final Path tempDir) throws Exception {
        final SettableClock clock = new SettableClock(NOW);
        final AsyncFileAuditRecorder recorder = newRecorder(tempDir, clock);
        try {
            recorder.record(event("day-1"));
            clock.set(NOW.plus(Duration.ofDays(1)));
            recorder.record(event("day-2"));
        } finally {
            recorder.shutdown();
        }
        assertEquals(1, Files.readAllLines(tempDir.resolve("audit-2026-09-06.jsonl")).size());
        assertEquals(1, Files.readAllLines(tempDir.resolve("audit-2026-09-07.jsonl")).size());
    }

    @Test
    void overflowShouldDropWithoutBlockingOrThrowing(@TempDir final Path tempDir) throws Exception {
        final CountDownLatch enteredWrite = new CountDownLatch(1);
        final CountDownLatch writerGate = new CountDownLatch(1);
        final AsyncFileAuditRecorder recorder = new AsyncFileAuditRecorder(
                tempDir, "test-service", 1, new SettableClock(NOW)) {
            @Override
            protected void writeLine(final String json) {
                enteredWrite.countDown();
                try {
                    writerGate.await(2, TimeUnit.SECONDS);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
                super.writeLine(json);
            }
        };
        try {
            recorder.record(event("blocked"));
            assertTrue(enteredWrite.await(2, TimeUnit.SECONDS), "writer 应已进入写入");
            recorder.record(event("queued"));
            final long startNanos = System.nanoTime();
            assertDoesNotThrow(() -> recorder.record(event("dropped")));
            final long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
            assertTrue(elapsedMillis < 500, "队列满时 record 不得阻塞");
        } finally {
            writerGate.countDown();
            recorder.shutdown();
        }
        final List<String> lines = Files.readAllLines(tempDir.resolve("audit-2026-09-06.jsonl"));
        assertEquals(2, lines.size());
        assertTrue(lines.get(0).contains("blocked"));
        assertTrue(lines.get(1).contains("queued"));
    }

    @Test
    void ioFailureShouldNotAffectBusinessAndLogRateLimited(@TempDir final Path tempDir) throws Exception {
        attachLogCapture();
        final AsyncFileAuditRecorder recorder = new AsyncFileAuditRecorder(
                tempDir, "test-service", 100, new SettableClock(NOW)) {
            @Override
            protected void writeLine(final String json) {
                throw new IllegalStateException("disk broken");
            }
        };
        try {
            assertDoesNotThrow(() -> {
                recorder.record(event("one"));
                recorder.record(event("two"));
                recorder.record(event("three"));
            });
            Thread.sleep(300);
        } finally {
            recorder.shutdown();
        }
        final long errors = logCapture.list.stream().filter(e -> e.getLevel() == Level.ERROR).count();
        assertEquals(1, errors, "60 秒窗口内最多 1 条 ERROR");
    }

    @Test
    void shutdownShouldDrainPendingEvents(@TempDir final Path tempDir) throws Exception {
        final AsyncFileAuditRecorder recorder = newRecorder(tempDir, new SettableClock(NOW));
        recorder.record(event("e-1"));
        recorder.record(event("e-2"));
        recorder.record(event("e-3"));
        recorder.record(event("e-4"));
        recorder.record(event("e-5"));
        recorder.shutdown();
        assertEquals(5, Files.readAllLines(tempDir.resolve("audit-2026-09-06.jsonl")).size());
    }

    private AsyncFileAuditRecorder newRecorder(final Path tempDir, final Clock clock) {
        return new AsyncFileAuditRecorder(tempDir, "test-service", 100, clock);
    }

    private AuditEvent event(final String actionSuffix) {
        return AuditEvent.of("tester", "greeting." + actionSuffix, "greeting", "g-1",
                AuditOutcome.SUCCESS, null);
    }

    private JsonNode readSingleLine(final Path file) throws Exception {
        final List<String> lines = Files.readAllLines(file);
        assertEquals(1, lines.size());
        return MAPPER.readTree(lines.get(0));
    }

    private void attachLogCapture() {
        recorderLogger = (Logger) org.slf4j.LoggerFactory.getLogger(AsyncFileAuditRecorder.class);
        logCapture.start();
        recorderLogger.addAppender(logCapture);
    }

    /** 可拨动的测试时钟：支撑跨天滚动断言。 */
    private static final class SettableClock extends Clock {

        private Instant current;

        SettableClock(final Instant initial) {
            this.current = initial;
        }

        void set(final Instant instant) {
            this.current = instant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(final ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return current;
        }
    }
}
