package com.ctds.common.logging;

/**
 * 组合审计出口（WBS-3.7.5 Q1-A file+jdbc 双写）：record 顺序委托、shutdown 级联委托
 * （Bean destroyMethod="shutdown" 兼容）。文件存证职能不中断——ADR-022 §2.6-1 出口语义保持。
 */
public class CompositeAuditRecorder implements AuditRecorder {

    private final AsyncFileAuditRecorder file;
    private final JdbcMeteringAuditRecorder jdbc;

    public CompositeAuditRecorder(final AsyncFileAuditRecorder file,
            final JdbcMeteringAuditRecorder jdbc) {
        this.file = file;
        this.jdbc = jdbc;
    }

    @Override
    public void record(final AuditEvent event) {
        file.record(event);
        jdbc.record(event);
    }

    public void shutdown() {
        file.shutdown();
        jdbc.shutdown();
    }
}
