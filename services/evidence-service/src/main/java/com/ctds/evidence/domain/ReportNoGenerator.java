package com.ctds.evidence.domain;

import java.time.LocalDate;

/**
 * 审计报告编号取号端口（WBS-3.8.4 hifi §2）：EV-RPT + yyyyMMdd + 4 位当日序列，
 * 沿 {@link EvidenceNoGenerator} 取号范式（LAST_INSERT_ID 原子自增，取号不回收 → 终身不复用）。
 */
public interface ReportNoGenerator {

    /** 取下一个报告编号（与报告登记行同事务调用，hifi §4.2）。 */
    String next(LocalDate businessDate);
}
