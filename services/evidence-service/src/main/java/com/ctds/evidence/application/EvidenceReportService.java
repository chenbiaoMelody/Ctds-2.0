package com.ctds.evidence.application;

import com.ctds.common.auth.AuthContext;
import com.ctds.common.crypto.Sm3Service;
import com.ctds.common.errorcode.BizException;
import com.ctds.common.errorcode.ErrorCodes;
import com.ctds.evidence.domain.AccessLogEntry;
import com.ctds.evidence.domain.AccessLogRepository;
import com.ctds.evidence.domain.AuditQueryCriteria;
import com.ctds.evidence.domain.AuditReport;
import com.ctds.evidence.domain.AuditReportRepository;
import com.ctds.evidence.domain.EvidenceAuditQueryRepository;
import com.ctds.evidence.domain.EvidenceAuditQueryRepository.VerdictRow;
import com.ctds.evidence.domain.EvidenceDigests;
import com.ctds.evidence.domain.EvidenceErrorCodes;
import com.ctds.evidence.domain.EvidencePage;
import com.ctds.evidence.domain.EvidenceRecord;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

/**
 * 审计报告生成与下载应用服务（WBS-3.8.4 hifi §1 E4 / E5 / §3 流程 4~5；B7-1~B7-4；剧本 S2）：
 * 生成 = 条件校验（≥1 条件 / 窗 ≤90 天 / 清单 ≤10000）→ 查询快照（事务外先行）→ 取号 + 登记同事务
 * （重复生成 = 新编号不覆盖）→ 返回报告要素；下载 = 重算 SM3 与登记比对（不一致 = 1012S0003
 * 显式报错且不输出文件）→ 按登记快照原样输出 CSV/JSON → 落 1 条导出留痕。
 */
@Service
public class EvidenceReportService {

    private static final Logger log = LoggerFactory.getLogger(EvidenceReportService.class);

    private static final String CONCLUSION_NOT_VERIFIED = "NOT_VERIFIED";
    private static final String CSV_CONTENT_TYPE = "text/csv;charset=UTF-8";
    private static final String JSON_CONTENT_TYPE = "application/json";
    private static final String[] CSV_COLUMNS = {"evidenceNo", "stageType", "businessNo",
        "operationType", "subjectDidDigest", "occurredAt", "status", "chainTxId",
        "contentDigest", "verifyConclusion", "anomalyType"};

    private final EvidenceAuditQueryRepository auditRepository;
    private final AuditReportRepository reportRepository;
    private final AccessLogRepository accessLogRepository;
    private final EvidenceReportRegistrar registrar;
    private final Sm3Service sm3;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public EvidenceReportService(final EvidenceAuditQueryRepository auditRepository,
            final AuditReportRepository reportRepository,
            final AccessLogRepository accessLogRepository,
            final EvidenceReportRegistrar registrar, final Sm3Service sm3,
            final ObjectMapper objectMapper, final Clock clock) {
        this.auditRepository = auditRepository;
        this.reportRepository = reportRepository;
        this.accessLogRepository = accessLogRepository;
        this.registrar = registrar;
        this.sm3 = sm3;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /** E4 报告生成：条件 ≥1、窗 ≤90 天、清单 ≤10000；重复生成 = 新编号不覆盖。 */
    public EvidenceAuditViews.ReportView generate(final String stageType, final String businessNo,
            final String subjectDigest, final String from, final String to,
            final String remark) {
        final AuditQueryCriteria criteria = EvidenceAuditConditions.parse(
                stageType, businessNo, subjectDigest, from, to, true);
        // 快照在事务外先行（hifi §4.2）：读多行不占取号事务
        final EvidencePage page = auditRepository.pageByCriteria(criteria, 0,
                EvidenceAuditLimits.MAX_REPORT_RECORDS + 1);
        if (page.total() > EvidenceAuditLimits.MAX_REPORT_RECORDS) {
            throw new BizException(EvidenceErrorCodes.PARAM_INVALID,
                    EvidenceAuditLimits.OVER_LIMIT_MESSAGE);
        }
        final List<EvidenceRecord> records = page.list();
        final Map<String, VerdictRow> verdicts = new HashMap<>();
        for (final VerdictRow verdict : auditRepository.latestVerdicts(criteria)) {
            verdicts.put(verdict.evidenceNo(), verdict);
        }
        final var anomalies = auditRepository.pageAnomalies(criteria, 0,
                EvidenceAuditLimits.MAX_REPORT_RECORDS + 1);

        final Instant generatedAt = clock.instant();
        final String generatedBy = AuthContext.subject();
        final ObjectNode content = objectMapper.createObjectNode();
        // 范围口径（hifi E4 出参）：与登记 scope_json 同源同形，E4 响应与 E5 JSON 导出一致
        final Map<String, String> scopeMap = new LinkedHashMap<>();
        final ObjectNode scope = content.putObject("scope");
        if (criteria.stageType() != null) {
            scopeMap.put("stageType", criteria.stageType().name());
        }
        if (criteria.businessNo() != null) {
            scopeMap.put("businessNo", criteria.businessNo());
        }
        if (criteria.subjectDigest() != null) {
            scopeMap.put("subjectDigest", criteria.subjectDigest());
        }
        if (criteria.from() != null) {
            scopeMap.put("from", criteria.from().toString());
        }
        if (criteria.to() != null) {
            scopeMap.put("to", criteria.to().toString());
        }
        scopeMap.forEach(scope::put);
        content.put("generatedBy", generatedBy);
        content.put("generatedAt", EvidenceDigests.occurredAtIso(generatedAt));
        final Map<String, Integer> conclusions =
                conclusionSummary(records, verdicts);
        final ObjectNode summary = content.putObject("summary");
        summary.put("recordCount", records.size());
        final ObjectNode conclusionNode = summary.putObject("conclusions");
        conclusions.forEach(conclusionNode::put);
        summary.put("anomalyCount", anomalies.total());
        final ArrayNode anomalyList = content.putArray("anomalies");
        for (final var row : anomalies.list()) {
            final ObjectNode item = anomalyList.addObject();
            item.put("evidenceNo", row.record().evidenceNo());
            item.put("anomalyType", row.anomalyType());
        }
        final ArrayNode recordList = content.putArray("records");
        for (final EvidenceRecord record : records) {
            final VerdictRow verdict = verdicts.get(record.evidenceNo());
            final ObjectNode node = (ObjectNode) objectMapper.valueToTree(
                    EvidenceRecordView.from(record));
            node.put("verifyConclusion",
                    verdict == null ? CONCLUSION_NOT_VERIFIED : verdict.conclusion());
            node.put("anomalyType", anomalyTypeOf(record, verdict));
            recordList.add(node);
        }
        final String contentJson = content.toString();
        final String contentDigest = sm3.digestHex(contentJson.getBytes(StandardCharsets.UTF_8));
        final String scopeJson = scope.toString();
        final String conclusionSummaryJson = conclusionNode.toString();
        final String reportNo = registrar.register(scopeJson, records.size(),
                conclusionSummaryJson, (int) anomalies.total(), contentJson, contentDigest,
                remark, generatedAt);
        return new EvidenceAuditViews.ReportView(reportNo, scopeMap, records.size(), conclusions,
                (int) anomalies.total(), contentDigest, remark, generatedBy,
                EvidenceDigests.occurredAtIso(generatedAt));
    }

    /** E5 报告下载：重算摘要核验（不一致 = 1012S0003 且不输出文件）→ 登记快照原样输出。 */
    public Export export(final String reportNo, final String format) {
        final String resolvedFormat = format == null || format.isBlank() ? "json" : format;
        if (!"csv".equals(resolvedFormat) && !"json".equals(resolvedFormat)) {
            throw new BizException(EvidenceErrorCodes.PARAM_INVALID, "导出格式不合法");
        }
        final AuditReport report = reportRepository.findByNo(reportNo)
                .orElseThrow(() -> new BizException(ErrorCodes.RESOURCE_NOT_FOUND,
                        EvidenceAuditAccessGuard.AUDIT_RECORD_NOT_FOUND_MESSAGE));
        final String recomputed =
                sm3.digestHex(report.reportContentJson().getBytes(StandardCharsets.UTF_8));
        if (!recomputed.equals(report.contentDigest())) {
            log.error("audit report integrity check failed: reportNo={}", reportNo);
            throw new BizException(EvidenceErrorCodes.REPORT_INTEGRITY_FAILED,
                    EvidenceErrorCodes.REPORT_INTEGRITY_FAILED_MESSAGE);
        }
        final JsonNode content = parse(report.reportContentJson());
        final String body;
        final String contentType;
        if ("csv".equals(resolvedFormat)) {
            body = renderCsv(content.path("records"));
            contentType = CSV_CONTENT_TYPE;
        } else {
            body = report.reportContentJson();
            contentType = JSON_CONTENT_TYPE;
        }
        final ObjectNode conditions = objectMapper.createObjectNode();
        conditions.put("reportNo", reportNo);
        conditions.put("format", resolvedFormat);
        insertExportLog(conditions.toString(), content.path("records").size());
        return new Export(body, contentType, reportNo + "." + resolvedFormat);
    }

    /** 导出载体（body 已按格式渲染；fileName = 报告编号 + 扩展名）。 */
    public record Export(String body, String contentType, String fileName) {
    }

    // ==== 内部 ====

    private static Map<String, Integer> conclusionSummary(final List<EvidenceRecord> records,
            final Map<String, VerdictRow> verdicts) {
        final Map<String, Integer> summary = new LinkedHashMap<>();
        summary.put("PASS", 0);
        summary.put("MISMATCH", 0);
        summary.put("NOT_ON_CHAIN", 0);
        summary.put(CONCLUSION_NOT_VERIFIED, 0);
        for (final EvidenceRecord record : records) {
            final VerdictRow verdict = verdicts.get(record.evidenceNo());
            final String conclusion =
                    verdict == null ? CONCLUSION_NOT_VERIFIED : verdict.conclusion();
            summary.merge(conclusion, 1, Integer::sum);
        }
        return summary;
    }

    /** 异常类型口径与异常清单一致：FAILED / PENDING 优先（核验互斥），否则最新核验不一致。 */
    private static String anomalyTypeOf(final EvidenceRecord record, final VerdictRow verdict) {
        if (EvidenceStatusNames.FAILED.equals(record.status().name())) {
            return "FAILED";
        }
        if (EvidenceStatusNames.PENDING.equals(record.status().name())) {
            return "PENDING";
        }
        return verdict != null && "MISMATCH".equals(verdict.conclusion()) ? "MISMATCH" : null;
    }

    /** 手写最小 CSV 转义（P8 无先例：逗号 / 引号 / 换行 → 引号包裹 + 内部引号翻倍，RFC 4180 口径）。 */
    private String renderCsv(final JsonNode records) {
        final StringBuilder csv = new StringBuilder(String.join(",", CSV_COLUMNS)).append('\n');
        for (final JsonNode record : records) {
            final String[] fields = new String[CSV_COLUMNS.length];
            for (int i = 0; i < CSV_COLUMNS.length; i++) {
                final JsonNode value = record.path(CSV_COLUMNS[i]);
                fields[i] = value.isMissingNode() || value.isNull()
                        ? "" : value.asText();
            }
            for (int i = 0; i < fields.length; i++) {
                if (i > 0) {
                    csv.append(',');
                }
                csv.append(escape(fields[i]));
            }
            csv.append('\n');
        }
        return csv.toString();
    }

    private static String escape(final String field) {
        if (field.indexOf(',') >= 0 || field.indexOf('"') >= 0 || field.indexOf('\n') >= 0
                || field.indexOf('\r') >= 0) {
            return '"' + field.replace("\"", "\"\"") + '"';
        }
        return field;
    }

    private JsonNode parse(final String contentJson) {
        try {
            return objectMapper.readTree(contentJson);
        } catch (final Exception failure) {
            log.error("audit report content parse failed", failure);
            throw new BizException(EvidenceErrorCodes.REPORT_INTEGRITY_FAILED,
                    EvidenceErrorCodes.REPORT_INTEGRITY_FAILED_MESSAGE);
        }
    }

    private void insertExportLog(final String conditionsJson, final int resultCount) {
        try {
            accessLogRepository.insert(new AccessLogEntry(AccessLogEntry.KIND_REPORT_EXPORT,
                    AuthContext.subject(), conditionsJson, resultCount,
                    null, clock.instant()));
        } catch (final DataAccessException failure) {
            log.error("audit export log write failed", failure);
            throw new BizException(EvidenceErrorCodes.STORAGE_FAILED,
                    EvidenceErrorCodes.STORAGE_FAILED_MESSAGE);
        }
    }

    /** 状态名常量（避免在静态方法链中引入枚举依赖）。 */
    private static final class EvidenceStatusNames {

        private static final String PENDING = "PENDING";
        private static final String FAILED = "FAILED";
    }
}
