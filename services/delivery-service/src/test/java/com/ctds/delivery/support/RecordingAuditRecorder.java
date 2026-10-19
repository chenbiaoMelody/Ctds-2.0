package com.ctds.delivery.support;

import com.ctds.common.logging.AuditEvent;
import com.ctds.common.logging.AuditRecorder;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 审计录制替身（hifi §9 T8）：把 AuditRecorder 事件收进内存列表，
 * 供"拒绝留痕 / create FAILURE / 字段不含本体与路径"断言逐条核对。
 */
public final class RecordingAuditRecorder implements AuditRecorder {

    private final List<AuditEvent> events = new ArrayList<>();

    @Override
    public synchronized void record(final AuditEvent event) {
        if (event != null) {
            events.add(event);
        }
    }

    public synchronized List<AuditEvent> events() {
        return List.copyOf(events);
    }

    public synchronized List<AuditEvent> eventsOf(final String action) {
        return events.stream().filter(event -> action.equals(event.action()))
                .collect(Collectors.toList());
    }

    public synchronized void clear() {
        events.clear();
    }
}
