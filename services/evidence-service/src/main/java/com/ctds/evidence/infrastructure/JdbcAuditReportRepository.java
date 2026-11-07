package com.ctds.evidence.infrastructure;

import com.ctds.evidence.domain.AuditReport;
import com.ctds.evidence.domain.AuditReportRepository;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * 审计报告登记仓储（WBS-3.8.4 hifi §2；append-only——仅 insert 与按编号只读查询，
 * 无更新 / 删除入口，T21 源码断言机器可证；uk_report_no 唯一锚兜底并发）。
 */
@Repository
public class JdbcAuditReportRepository implements AuditReportRepository {

    private final JdbcClient jdbc;

    public JdbcAuditReportRepository(final JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insert(final AuditReport report) {
        jdbc.sql("INSERT INTO evidence_audit_report (report_no, scope_json, record_count, "
                        + "conclusion_summary_json, anomaly_count, report_content_json, "
                        + "content_digest, remark, generated_by, generated_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")
                .params(report.reportNo(), report.scopeJson(), report.recordCount(),
                        report.conclusionSummaryJson(), report.anomalyCount(),
                        report.reportContentJson(), report.contentDigest(), report.remark(),
                        report.generatedBy(), toLocal(report.generatedAt()))
                .update();
    }

    @Override
    public Optional<AuditReport> findByNo(final String reportNo) {
        return jdbc.sql("SELECT id, report_no, scope_json, record_count, "
                        + "conclusion_summary_json, anomaly_count, report_content_json, "
                        + "content_digest, remark, generated_by, generated_at "
                        + "FROM evidence_audit_report WHERE report_no = ?")
                .param(reportNo)
                .query((rs, rowNum) -> new AuditReport(rs.getLong("id"), rs.getString("report_no"),
                        rs.getString("scope_json"), rs.getInt("record_count"),
                        rs.getString("conclusion_summary_json"), rs.getInt("anomaly_count"),
                        rs.getString("report_content_json"), rs.getString("content_digest"),
                        rs.getString("remark"), rs.getString("generated_by"),
                        toInstant(rs.getObject("generated_at", LocalDateTime.class))))
                .optional();
    }

    private static LocalDateTime toLocal(final Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static Instant toInstant(final LocalDateTime value) {
        return value.toInstant(ZoneOffset.UTC);
    }
}
