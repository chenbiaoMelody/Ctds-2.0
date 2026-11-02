-- 计费域五表（WBS-3.7.6 hifi §2 定稿；规格 docs/specs/C-7.1-7.2-门户与计费.md V1.0 B9/B10/B11/B12）：
-- bill = 账单主表（金额/明细生成后不可改；状态仅确认/冻结/解冻流转）；
-- bill_detail = 明细（按次逐笔映射 metering_flow，包月/分成单行）；
-- bill_action_log = 动作留痕（CREATE/CONFIRM/FREEZE/UNFREEZE，不承载金额修改）；
-- reconciliation = 对账记录（每次执行一行，四方数值 + 复算金额 + 结论 + 差异明细，不自动修数）；
-- bill_no_seq = 账单号序号（单行原子取号，沿 contract_no_seq 先例）。
-- 计费依据全部为上游只读快照（ctds_contract.contract），本域不写上游任何表。
-- 数据分级落级（分级规范 §6.1 口径）：五表均 L1（交易元数据，无内容本体与敏感原文）。

CREATE TABLE billing_bill (
    id                    BIGINT        NOT NULL AUTO_INCREMENT,
    bill_no               VARCHAR(32)   NOT NULL COMMENT '对外账单号（BL+6位，全局唯一、终身不复用）',
    bill_type             VARCHAR(16)   NOT NULL COMMENT 'NORMAL / CORRECTION',
    contract_no           VARCHAR(32)   NOT NULL COMMENT '合约编号（计费对象；快照）',
    requester_no          VARCHAR(32)   NOT NULL COMMENT '需方快照',
    provider_no           VARCHAR(32)   NOT NULL COMMENT '供方快照',
    product_id            BIGINT        NOT NULL COMMENT '产品 id 快照（读面过滤）',
    product_name          VARCHAR(128)  NOT NULL COMMENT '产品名快照（读面展示）',
    `period`              CHAR(7)       NOT NULL COMMENT '自然月 YYYY-MM',
    pricing_model         VARCHAR(16)   NOT NULL COMMENT 'FREE/PER_CALL/MONTHLY/REVENUE_SHARE 快照（免费档不落单）',
    quantity              INT           NULL     COMMENT '按次=本期事件数；包月/分成=NULL',
    unit_price            DECIMAL(12,2) NULL     COMMENT '按次/包月=快照单价；分成=NULL',
    base_amount           DECIMAL(12,2) NULL     COMMENT '分成=约定交易额；其余 NULL',
    share_rate            DECIMAL(12,2) NULL     COMMENT '分成=费率百分比 0~100；其余 NULL',
    amount                DECIMAL(12,2) NOT NULL COMMENT '账单金额（HALF_UP 两位）',
    status                VARCHAR(16)   NOT NULL COMMENT 'ISSUED / CONFIRMED / FROZEN',
    status_before_freeze  VARCHAR(16)   NULL     COMMENT '冻结前状态（解冻恢复用）',
    correction_of_bill_no VARCHAR(32)   NULL     COMMENT '更正单指向原账单号；普通单 NULL',
    created_by            VARCHAR(32)   NOT NULL COMMENT '操作主体；自动出账=system',
    created_at            DATETIME      NOT NULL,
    active_normal_key     VARCHAR(80)   GENERATED ALWAYS AS
        (CASE WHEN bill_type = 'NORMAL' THEN CONCAT(contract_no, '#', `period`) ELSE NULL END) STORED,
    PRIMARY KEY (id),
    UNIQUE KEY uk_bill_no (bill_no),
    UNIQUE KEY uk_active_normal_key (active_normal_key),
    KEY idx_contract_period (contract_no, `period`),
    KEY idx_requester (requester_no, created_at),
    KEY idx_provider (provider_no, created_at),
    KEY idx_product (product_id, created_at),
    KEY idx_status (status, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '账单主表（金额/明细生成后不可改；状态冻结/确认留痕）';

CREATE TABLE billing_bill_detail (
    id               BIGINT        NOT NULL AUTO_INCREMENT,
    bill_no          VARCHAR(32)   NOT NULL,
    line_no          INT           NOT NULL,
    line_type        VARCHAR(24)   NOT NULL COMMENT 'PER_CALL_EVENT / PERIOD_FEE / REVENUE_SHARE_BASE',
    description      VARCHAR(256)  NOT NULL COMMENT '业务可读行说明（常量文案）',
    metering_flow_id BIGINT        NULL     COMMENT '按次事件行→metering_flow.id（逐笔可溯）；其他 NULL',
    source_type      VARCHAR(24)   NULL     COMMENT '按次事件行快照',
    source_no        VARCHAR(64)   NULL     COMMENT '按次事件行快照',
    occurred_at      DATETIME      NULL     COMMENT '按次事件时点；其他 NULL',
    quantity         INT           NOT NULL DEFAULT 1,
    unit_price       DECIMAL(12,2) NULL,
    amount           DECIMAL(12,2) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_bill_line (bill_no, line_no),
    KEY idx_bill_no (bill_no)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '账单明细（按次逐笔映射计量流水；包月/分成单行）';

CREATE TABLE billing_bill_action_log (
    id                  BIGINT       NOT NULL AUTO_INCREMENT,
    bill_no             VARCHAR(32)  NOT NULL,
    action              VARCHAR(24)  NOT NULL COMMENT 'CREATE / CONFIRM / FREEZE / UNFREEZE',
    from_status         VARCHAR(16)  NULL,
    to_status           VARCHAR(16)  NULL,
    reason              VARCHAR(512) NULL     COMMENT '业务可读原因（冻结/更正/确认说明；无敏感原文）',
    operator_subject_no VARCHAR(32)  NOT NULL COMMENT '操作主体（自动=system）',
    created_at          DATETIME     NOT NULL,
    PRIMARY KEY (id),
    KEY idx_bill_action (bill_no, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '账单动作留痕（确认/冻结/解冻/创建；不承载金额修改）';

CREATE TABLE billing_reconciliation (
    id                         BIGINT        NOT NULL AUTO_INCREMENT,
    contract_no                VARCHAR(32)   NOT NULL,
    `period`                   CHAR(7)       NOT NULL,
    counter_used_count         INT           NOT NULL COMMENT '全量余额口径（无 counter 行=0）',
    usage_log_allowed_count    INT           NOT NULL COMMENT '本期 ALLOWED 流水数',
    metering_flow_count        INT           NOT NULL COMMENT '本期计量流水数',
    usage_log_allowed_all_time INT           NOT NULL COMMENT '全量 ALLOWED 流水数',
    metering_flow_all_time     INT           NOT NULL COMMENT '全量计量流水数',
    bill_quantity              INT           NULL     COMMENT '按次账单数量；其他 NULL',
    bill_total_amount          DECIMAL(12,2) NOT NULL,
    recomputed_amount          DECIMAL(12,2) NOT NULL COMMENT '按账单快照独立复算金额',
    conclusion                 VARCHAR(16)   NOT NULL COMMENT 'PASS / DIFFERENCE',
    details_json               TEXT          NULL     COMMENT '差异明细 JSON（类型/腿/期望/实际/条目）',
    created_by                 VARCHAR(32)   NOT NULL,
    created_at                 DATETIME      NOT NULL,
    PRIMARY KEY (id),
    KEY idx_recon_contract_period (contract_no, `period`, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '对账记录（每次执行一行；不自动修数）';

CREATE TABLE billing_bill_no_seq (
    id      INT    NOT NULL,
    next_no BIGINT NOT NULL,
    PRIMARY KEY (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '账单号序号（单行原子取号，沿 contract_no_seq 先例）';

INSERT INTO billing_bill_no_seq (id, next_no) VALUES (1, 1);
