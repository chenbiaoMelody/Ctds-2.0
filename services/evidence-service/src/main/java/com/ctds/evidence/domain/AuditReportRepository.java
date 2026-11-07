package com.ctds.evidence.domain;

import java.util.Optional;

/**
 * 审计报告登记仓储端口（WBS-3.8.4 hifi §2 / §4.3）：append-only——仅 insert 与按编号只读查询，
 * 无任何更新 / 删除入口（T21 源码断言机器可证）；下载时重算摘要核验由应用层比对，
 * 本端口不提供内容改写路径。
 */
public interface AuditReportRepository {

    /** 只插（编号唯一锚 uk_report_no 兜底并发）。 */
    void insert(AuditReport report);

    /** 按报告编号读取登记行（查无 = empty）。 */
    Optional<AuditReport> findByNo(String reportNo);
}
