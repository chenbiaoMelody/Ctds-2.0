-- WBS-3.5.3 交付服务首版迁移（hifi §2）：
-- delivery = 交付记录（API / FILE 两类；只存业务要素与校验值，不存文件本体/原文/路径/密钥材料）；
-- delivery_action_log = 域内动作留痕（创建/读取/下载/拒绝；与 common-logging 审计并行）；
-- delivery_no_seq = DL+6 位全局序号（沿 contract_no_seq 原子取号先例）。

CREATE TABLE delivery (
    id                BIGINT        NOT NULL AUTO_INCREMENT,
    delivery_no       VARCHAR(16)   NOT NULL COMMENT 'DL+6位全局序号',
    contract_no       VARCHAR(32)   NOT NULL COMMENT '交付凭据（已生效合约编号）',
    product_id        BIGINT        NOT NULL COMMENT '合约产品 id 快照',
    product_name      VARCHAR(128)  NOT NULL COMMENT '产品名快照',
    delivery_type     VARCHAR(8)    NOT NULL COMMENT 'API/FILE',
    requester_no      VARCHAR(32)   NOT NULL COMMENT '合约需求方快照（发起人）',
    provider_no       VARCHAR(32)   NOT NULL COMMENT '合约提供方快照',
    file_id           VARCHAR(64)   NULL COMMENT '文件清单精确键（FILE）',
    file_name         VARCHAR(256)  NULL COMMENT '展示名快照（FILE）',
    file_size         BIGINT        NULL COMMENT '明文字节数（FILE）',
    package_id        VARCHAR(64)   NULL COMMENT '加密包标识（FILE）',
    checksum          VARCHAR(64)   NULL COMMENT 'SM3 小写 hex（明文，FILE）',
    key_ref           VARCHAR(64)   NULL COMMENT '加密密钥编号（非密钥材料，FILE）',
    result            VARCHAR(16)   NOT NULL COMMENT 'SUCCESS',
    idempotency_key   VARCHAR(128)  NOT NULL,
    created_at        DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_delivery_no (delivery_no),
    UNIQUE KEY uk_idem (requester_no, idempotency_key),
    KEY idx_contract (contract_no, created_at),
    KEY idx_requester (requester_no, created_at),
    KEY idx_provider (provider_no, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '交付记录（API/文件；不含本体与原文）';

CREATE TABLE delivery_action_log (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    delivery_no  VARCHAR(16)  NULL COMMENT '拒绝类动作可为空',
    contract_no  VARCHAR(32)  NULL,
    operator_no  VARCHAR(32)  NOT NULL,
    action       VARCHAR(64)  NOT NULL,
    result       VARCHAR(16)  NOT NULL,
    reason_code  VARCHAR(16)  NULL,
    created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_log_delivery (delivery_no, created_at),
    KEY idx_log_contract (contract_no, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '交付域动作留痕（不含本体与原文）';

CREATE TABLE delivery_no_seq (
    id       BIGINT NOT NULL,
    next_no  BIGINT NOT NULL,
    PRIMARY KEY (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '交付编号全局序号（1 行原子取号）';

INSERT INTO delivery_no_seq (id, next_no) VALUES (1, 1);
