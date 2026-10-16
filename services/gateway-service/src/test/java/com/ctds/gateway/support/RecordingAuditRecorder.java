package com.ctds.gateway.support;

import com.ctds.common.logging.AuditEvent;
import com.ctds.common.logging.AuditRecorder;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 审计录制替身（hifi §9 T8 对账锚）：把 {@code AuditRecorder} 事件收进内存列表，供"放行 1 次 ≡
 * 计量事件 1 条 / 拒绝 0 条"与"事件字段不含数据本体与原文"断言逐条核对。
 *
 * <p>由各测试类以 {@code @TestConfiguration} 声明为 {@code AuditRecorder} 主 Bean（测试禁用真实
 * 异步文件审计器：{@code ctds.audit.enabled=false}）。</p>
 */
public class RecordingAuditRecorder implements AuditRecorder {

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
