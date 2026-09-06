package com.ctds.common.logging;

import com.ctds.common.api.ApiResult;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 * 审计事件默认实现：单后台守护线程 + 有界队列 + 按天滚动 JSONL 文件（ADR-005 §3 第 6 项）。
 * record 在调用线程完成序列化并入队（traceId 由此在调用线程现场快照），任何路径不抛错、不阻塞；
 * 队列满丢弃并计数告警；文件写入失败不影响业务（60 秒限频 ERROR）；shutdown 优雅排空（最多 5 秒）。
 */
public class AsyncFileAuditRecorder implements AuditRecorder {

    private static final Logger log = LoggerFactory.getLogger(AsyncFileAuditRecorder.class);
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .setSerializationInclusion(JsonInclude.Include.NON_NULL);
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("uuuu-MM-dd")
            .withZone(ZoneOffset.UTC);
    private static final long ERROR_LOG_INTERVAL_MILLIS = 60_000L;
    private static final int DROP_WARN_INTERVAL = 100;
    private static final int DETAIL_MAX_ENTRIES = 20;
    private static final int DETAIL_MAX_VALUE_LENGTH = 512;
    private static final String TRUNCATED_SUFFIX = "…[truncated]";

    private final Path fileDir;
    private final String serviceName;
    private final Clock clock;
    private final BlockingQueue<String> queue;
    private final Thread writerThread;
    private final AtomicLong droppedCount = new AtomicLong();
    private volatile boolean accepting = true;
    private volatile long lastErrorLogMillis;

    /** @param fileDir 审计文件目录 @param serviceName 服务名（JSONL service 字段）
     *  @param queueCapacity 有界队列容量 @param clock 时钟（可注入以便测试跨天滚动） */
    public AsyncFileAuditRecorder(final Path fileDir, final String serviceName,
            final int queueCapacity, final Clock clock) {
        this.fileDir = fileDir;
        this.serviceName = serviceName;
        this.clock = clock;
        this.queue = new LinkedBlockingQueue<>(Math.max(1, queueCapacity));
        this.writerThread = new Thread(this::drainLoop, "ctds-audit-writer");
        this.writerThread.setDaemon(true);
        this.writerThread.start();
    }

    @Override
    public void record(final AuditEvent event) {
        if (event == null) {
            return;
        }
        final String line = toJson(enrich(event));
        if (line == null) {
            return;
        }
        if (!queue.offer(line)) {
            warnDrop();
        }
    }

    /** 写出一行 JSON（protected 以便测试注入阻塞/故障；默认追加到按天滚动的 JSONL 文件）。 */
    protected void writeLine(final String json) {
        final String day = DAY.format(clock.instant());
        final Path file = fileDir.resolve("audit-" + day + ".jsonl");
        try {
            Files.createDirectories(fileDir);
            Files.writeString(file, json + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (final IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    /** 优雅停机：停止接收后排空队列，最多等待 5 秒。 */
    public void shutdown() {
        accepting = false;
        writerThread.interrupt();
        try {
            writerThread.join(TimeUnit.SECONDS.toMillis(5));
        } catch (final InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private void drainLoop() {
        while (accepting || !queue.isEmpty()) {
            String line;
            try {
                line = queue.poll(200, TimeUnit.MILLISECONDS);
            } catch (final InterruptedException ex) {
                Thread.currentThread().interrupt();
                line = queue.poll();
            }
            if (line != null) {
                writeSafely(line);
            }
        }
    }

    private void writeSafely(final String line) {
        try {
            writeLine(line);
        } catch (final Exception ex) {
            final long now = System.currentTimeMillis();
            if (now - lastErrorLogMillis >= ERROR_LOG_INTERVAL_MILLIS) {
                lastErrorLogMillis = now;
                log.error("audit file write failed, line dropped", ex);
            }
        }
    }

    private AuditEvent enrich(final AuditEvent event) {
        return new AuditEvent(
                event.eventId() != null ? event.eventId() : java.util.UUID.randomUUID().toString(),
                event.eventTime() != null ? event.eventTime() : clock.instant(),
                event.actor(), event.action(), event.targetType(), event.targetId(),
                event.outcome(), event.detail());
    }

    private String toJson(final AuditEvent event) {
        final Map<String, Object> json = new LinkedHashMap<>();
        json.put("eventId", event.eventId());
        json.put("eventTime", event.eventTime().toString());
        json.put("service", serviceName);
        json.put("actor", event.actor());
        json.put("action", event.action());
        json.put("targetType", event.targetType());
        json.put("targetId", event.targetId());
        json.put("outcome", event.outcome().name());
        json.put("detail", event.detail().isEmpty() ? null : sanitizeDetail(event.detail()));
        json.put("traceId", emptyToNull(MDC.get(ApiResult.TRACE_MDC_KEY)));
        try {
            return MAPPER.writeValueAsString(json);
        } catch (final JsonProcessingException ex) {
            warnDrop();
            return null;
        }
    }

    private Map<String, String> sanitizeDetail(final Map<String, String> detail) {
        final Map<String, String> result = new LinkedHashMap<>();
        int count = 0;
        for (final Map.Entry<String, String> entry : detail.entrySet()) {
            if (count >= DETAIL_MAX_ENTRIES) {
                result.put("_truncated", "true");
                break;
            }
            count = count + 1;
            result.put(entry.getKey(), truncate(entry.getValue()));
        }
        return result;
    }

    private String truncate(final String value) {
        if (value == null || value.length() <= DETAIL_MAX_VALUE_LENGTH) {
            return value;
        }
        return value.substring(0, DETAIL_MAX_VALUE_LENGTH) + TRUNCATED_SUFFIX;
    }

    private String emptyToNull(final String value) {
        return (value == null || value.isEmpty()) ? null : value;
    }

    private void warnDrop() {
        final long drops = droppedCount.incrementAndGet();
        if (drops == 1 || drops % DROP_WARN_INTERVAL == 0) {
            log.warn("audit event dropped, totalDrops={}", drops);
        }
    }
}
