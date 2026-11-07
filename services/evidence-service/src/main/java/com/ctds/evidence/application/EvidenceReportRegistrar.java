package com.ctds.evidence.application;

import com.ctds.common.auth.AuthContext;
import com.ctds.common.errorcode.BizException;
import com.ctds.evidence.domain.AuditReport;
import com.ctds.evidence.domain.AuditReportRepository;
import com.ctds.evidence.domain.EvidenceErrorCodes;
import com.ctds.evidence.domain.ReportNoGenerator;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 审计报告取号 + 登记事务段（WBS-3.8.4 hifi §4.2）：取号（单行原子自增）与报告落库
 * 同一事务——落库失败整体回滚（1012S0002，可重试，重试 = 新编号不覆盖）；独立组件承载
 * 事务边界（避免服务内自调用绕过代理）。
 */
@Component
public class EvidenceReportRegistrar {

    private static final Logger log = LoggerFactory.getLogger(EvidenceReportRegistrar.class);

    private final ReportNoGenerator generator;
    private final AuditReportRepository reportRepository;

    public EvidenceReportRegistrar(final ReportNoGenerator generator,
            final AuditReportRepository reportRepository) {
        this.generator = generator;
        this.reportRepository = reportRepository;
    }

    /** 取号 + 登记同事务（取号失败同映射 1012S0002，沿 EvidenceWriteService 先例）；返回报告编号。 */
    @Transactional
    public String register(final String scopeJson, final int recordCount,
            final String conclusionSummaryJson, final int anomalyCount,
            final String reportContentJson, final String contentDigest, final String remark,
            final Instant generatedAt) {
        final String reportNo;
        try {
            reportNo = generator.next(LocalDate.ofInstant(generatedAt, ZoneOffset.UTC));
            reportRepository.insert(AuditReport.draft(reportNo, scopeJson, recordCount,
                    conclusionSummaryJson, anomalyCount, reportContentJson, contentDigest,
                    remark, AuthContext.subject(), generatedAt));
        } catch (final DataAccessException failure) {
            log.error("audit report register failed (sequence or insert)", failure);
            throw new BizException(EvidenceErrorCodes.STORAGE_FAILED,
                    EvidenceErrorCodes.STORAGE_FAILED_MESSAGE);
        }
        return reportNo;
    }
}
