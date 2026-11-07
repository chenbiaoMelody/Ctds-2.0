-- 监管审计读面三表（WBS-3.8.4 hifi §2 定稿；规格 docs/specs/C-8.1-8.2-存证与审计.md V1.1 B6-3 / B7-2）：
-- ① evidence_access_log 调阅与导出动作留痕（append-only；调阅人 / 时点 / 条件 / 返回条数可查可核验）；
-- ② evidence_audit_report 审计报告登记（生成时快照 + SM3 内容摘要 + 唯一编号；append-only 只插不改，
--    下载时重算摘要与登记比对，不一致 = 1012S0003 显式报错）；
-- ③ evidence_report_no_seq 报告编号当日取号（EV-RPT + yyyyMMdd + 4 位序列，沿 evidence_no_seq 取号范式）。
-- 数据分级落级（分级规范 §6.1 口径）：L1（业务元数据；conditions_json / report_content_json 仅存
-- 摘要与元数据白名单，零原文 / 个人信息 / 密钥材料列）。

CREATE TABLE evidence_access_log (
    id              BIGINT        NOT NULL AUTO_INCREMENT,
    access_kind     VARCHAR(16)   NOT NULL COMMENT 'TRAIL_ACCESS / REPORT_EXPORT（两值封闭）',
    accessed_by     VARCHAR(128)  NOT NULL COMMENT '调阅人 / 导出人（主体编号）',
    conditions_json VARCHAR(1024) NULL     COMMENT '调阅条件（脱敏后键值；键 ≤20 项、值 ≤512 截断）',
    result_count    INT           NOT NULL COMMENT '返回条数（时间线节点数或清单条数）',
    purpose         VARCHAR(64)   NULL     COMMENT '结论用途（可空，V1.0 端点不传即 NULL）',
    created_at      DATETIME(6)   NOT NULL,
    PRIMARY KEY (id),
    KEY idx_access_actor (accessed_by, created_at),
    KEY idx_access_kind (access_kind, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '审计调阅与导出动作留痕（append-only，B6-3 / B7-3）';

CREATE TABLE evidence_audit_report (
    id                     BIGINT       NOT NULL AUTO_INCREMENT,
    report_no              VARCHAR(32)  NOT NULL COMMENT '报告编号（EV-RPT-yyyymmdd-xxxx，唯一）',
    scope_json             VARCHAR(1024) NOT NULL COMMENT '范围口径（条件键值，脱敏后）',
    record_count           INT          NOT NULL COMMENT '快照内存证清单条数',
    conclusion_summary_json VARCHAR(1024) NOT NULL COMMENT '核验结论分布（PASS / MISMATCH / NOT_ON_CHAIN / 未核验）',
    anomaly_count          INT          NOT NULL COMMENT '快照内异常项条数',
    report_content_json    MEDIUMTEXT   NOT NULL COMMENT '生成时快照（仅存证要素 / 摘要 / 元数据，无原文），下载即以此重算摘要',
    content_digest         CHAR(64)     NOT NULL COMMENT 'SM3(report_content_json)，64 位小写 hex，登记后不更新',
    remark                 VARCHAR(255) NULL     COMMENT '生成备注（请求可传，可空）',
    generated_by           VARCHAR(128) NOT NULL COMMENT '生成人（主体编号）',
    generated_at           DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_report_no (report_no)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '审计报告登记（append-only 只插不改；重复生成 = 新编号）';

CREATE TABLE evidence_report_no_seq (
    seq_date DATE NOT NULL COMMENT 'yyyyMMdd（UTC 业务日）',
    next_val INT  NOT NULL COMMENT '当日已用最大序号（只增不回收）',
    PRIMARY KEY (seq_date)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '审计报告编号当日取号（EV-RPT + 日期 + 4 位序列，终身不复用）';
