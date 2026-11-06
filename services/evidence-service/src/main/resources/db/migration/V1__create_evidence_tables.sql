-- 存证服务四表（WBS-3.8.2 hifi §2 定稿；规格 docs/specs/C-8.1-8.2-存证与审计.md V1.1 B1~B4）：
-- ① evidence_record 存证记录（六要素 + 内容摘要 + 状态；uk_evidence_idem 幂等锚 = 环节 + 单据号 + 操作类型）；
-- ② evidence_record_action_log 状态流转与核验留痕（append-only，六类 action）；
-- ③ evidence_chain_block 本地不可变账本（append-only 哈希链；uk_chain_payload 保证重放幂等）；
-- ④ evidence_no_seq 当日取号（EV + 日期 + 6 位序列，取号不回收 → 编号终身不复用）。
-- 列面 = ADR-004 §3-3 上链白名单五项 + 本地留痕所需最小元数据，零原文 / 个人信息 / 密钥材料列。
-- 数据分级落级（分级规范 §6.1 口径）：L1（业务元数据，无内容本体与敏感原文）。

CREATE TABLE evidence_no_seq (
    seq_date   CHAR(8) NOT NULL COMMENT 'yyyyMMdd（UTC 业务日）',
    next_val   INT     NOT NULL COMMENT '当日已用最大序号（只增不回收）',
    PRIMARY KEY (seq_date)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '存证编号当日取号（EV + 日期 + 6 位序列，终身不复用）';

CREATE TABLE evidence_record (
    id                  BIGINT       NOT NULL AUTO_INCREMENT,
    evidence_no         VARCHAR(32)  NOT NULL COMMENT '存证编号（EV + yyyyMMdd + 6 位当日序列）',
    stage_type          VARCHAR(24)  NOT NULL COMMENT 'AUTHORIZATION / CONTRACT / DELIVERY / USAGE（四环节封闭枚举）',
    business_no         VARCHAR(64)  NOT NULL COMMENT '业务单据号（上游逐字透传，不 trim / 不改写）',
    operation_type      VARCHAR(40)  NOT NULL COMMENT '操作类型（六类封闭枚举，与环节匹配）',
    subject_did_digest  CHAR(64)     NOT NULL COMMENT 'SM3("CTDS-DID-1|" + did)，不存 DID 原值',
    occurred_at         DATETIME(3)  NOT NULL COMMENT '业务事件时点（UTC）',
    content_digest      CHAR(64)     NOT NULL COMMENT 'SM3(CTDS-EV-1 规范化串)，64 位小写 hex',
    status              VARCHAR(16)  NOT NULL COMMENT 'PENDING / ON_CHAIN / FAILED',
    chain_tx_id         VARCHAR(64)  NULL     COMMENT '链上交易标识（只由适配器产生，禁手工填写）',
    chain_block_no      BIGINT       NULL     COMMENT '账本块号（本地适配器产生）',
    retry_count         INT          NOT NULL DEFAULT 0 COMMENT '手动重试次数',
    last_error_summary  VARCHAR(200) NULL     COMMENT '失败原因摘要（脱敏，不含内部实现与堆栈）',
    created_at          DATETIME(3)  NOT NULL,
    updated_at          DATETIME(3)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_evidence_no (evidence_no),
    UNIQUE KEY uk_evidence_idem (stage_type, business_no, operation_type),
    KEY idx_evidence_status (status, occurred_at),
    KEY idx_evidence_business (business_no)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '存证记录（证据层；只增不删，仅状态列可推进）';

CREATE TABLE evidence_record_action_log (
    id                BIGINT      NOT NULL AUTO_INCREMENT,
    evidence_no       VARCHAR(32) NOT NULL,
    action            VARCHAR(32) NOT NULL COMMENT 'CREATED / CHAIN_SUCCEEDED / CHAIN_FAILED / RETRY_SUCCEEDED / RETRY_FAILED / VERIFIED',
    from_status       VARCHAR(16) NULL     COMMENT '流转前状态（CREATED 为 NULL）',
    to_status         VARCHAR(16) NULL     COMMENT '流转后状态（VERIFIED 不变）',
    actor_subject_no  VARCHAR(32) NOT NULL COMMENT '操作人 / 核验人（主体编号）',
    reason_code       VARCHAR(16) NULL     COMMENT '1012 段码值或 NONE',
    detail            VARCHAR(512) NULL    COMMENT '结论 / 原因摘要（禁原文与个人信息）',
    created_at        DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    KEY idx_log_evidence (evidence_no, id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '存证状态流转与核验留痕（append-only）';

CREATE TABLE evidence_chain_block (
    block_no        BIGINT       NOT NULL AUTO_INCREMENT,
    chain_tx_id     VARCHAR(64)  NOT NULL COMMENT 'LTX + 12 位块号',
    payload_digest  CHAR(64)     NOT NULL COMMENT '上链载荷中的内容摘要（同 content_digest）',
    payload_json    VARCHAR(512) NOT NULL COMMENT '白名单五项，固定键序、无空白',
    prev_block_hash CHAR(64)     NOT NULL COMMENT '前序块哈希（创世块 = 64 个 0）',
    block_hash      CHAR(64)     NOT NULL COMMENT 'SM3(块号|载荷摘要|载荷|前序哈希|创建时点)',
    created_at      DATETIME(3)  NOT NULL,
    PRIMARY KEY (block_no),
    UNIQUE KEY uk_chain_tx (chain_tx_id),
    UNIQUE KEY uk_chain_payload (payload_digest)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '本地不可变账本（append-only 哈希链；uk_chain_payload 保证重放幂等）';
