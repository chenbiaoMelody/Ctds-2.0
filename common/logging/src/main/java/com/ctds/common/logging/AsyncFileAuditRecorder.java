package com.ctds.common.logging;

import java.nio.file.Path;
import java.time.Clock;

/**
 * 审计事件默认实现：单后台守护线程 + 有界队列 + 按天滚动 JSONL 文件（ADR-005 §3 第 6 项）。
 * 行为契约（docs/designs/WBS-2.4.4-hifi.md B4/B5/B6）：
 * record 立即返回（队列满丢弃并计数告警）；写文件失败不影响业务（限频 ERROR）；
 * 跨天自动切换新文件；shutdown 优雅排空（最多等 5 秒）。
 */
public class AsyncFileAuditRecorder implements AuditRecorder {

    /** @param fileDir 审计文件目录 @param serviceName 服务名（写入 JSONL service 字段）
     *  @param queueCapacity 有界队列容量 @param clock 时钟（可注入以便测试跨天滚动） */
    public AsyncFileAuditRecorder(final Path fileDir, final String serviceName,
            final int queueCapacity, final Clock clock) {
    }

    @Override
    public void record(final AuditEvent event) {
    }

    /** 写出一行 JSON（protected 以便测试注入阻塞/故障，实现于测试确认后）。 */
    protected void writeLine(final String json) {
    }

    /** 优雅停机：停止接收后排空队列，最多等待 5 秒（实现于测试确认后）。 */
    public void shutdown() {
    }
}
