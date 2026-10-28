-- WBS-3.6.5 连接器源接入登记迁移（hifi §2）：
-- connector_source         = 源接入登记（四类源全量；凭据 = 引用 + SM4 信封密文 + 掩码三列并存）
-- connector_source_no_seq  = SR+6 位源编号全局序号（1 行原子取号）
-- 自检结论不建表：落 connector_action_log（action = source.check，detail 首段固定 sourceNo），详情读面取最近一条派生。
-- 零外键（跨表只逻辑引用，沿 ADR-009 与 3.6.3 口径）。

CREATE TABLE connector_source (
    id                    BIGINT        NOT NULL AUTO_INCREMENT COMMENT '技术主键（自增）',
    source_no             VARCHAR(16)   NOT NULL COMMENT 'SR+6位源编号（登记即签发，终身不复用）',
    connector_no          VARCHAR(16)   NOT NULL COMMENT '所属连接器编号（CN+6位）',
    subject_no            VARCHAR(32)   NOT NULL COMMENT '所属主体快照（登记时属主，服务端从鉴权上下文取）',
    source_type           VARCHAR(24)   NOT NULL COMMENT 'GOV_EXCHANGE/DATABASE/OBJECT_STORAGE/API_GATEWAY',
    endpoint              VARCHAR(512)  NOT NULL COMMENT '源地址（mock:// 仅政务源；生产形态 http(s)://）',
    credential_ref        VARCHAR(48)   NOT NULL COMMENT '凭据不透明引用（SC+32位十六进制，对外唯一凭据标识）',
    credential_ciphertext VARCHAR(2048) NOT NULL COMMENT 'SM4 信封密文（common-crypto 唯一入口；明文永不落库）',
    credential_masked     VARCHAR(32)   NOT NULL COMMENT '凭据掩码（展示 / 留痕唯一形态）',
    created_at            DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '登记时间',
    updated_at            DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最近变更时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_source_no (source_no),
    UNIQUE KEY uk_connector_type_endpoint (connector_no, source_type, endpoint(128)),
    KEY idx_source_connector (connector_no, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '连接器源接入登记（凭据加密存储，只显掩码）';

CREATE TABLE connector_source_no_seq (
    id       BIGINT NOT NULL COMMENT '固定为 1（单行取号表）',
    next_no  BIGINT NOT NULL COMMENT '下一个可签发序号（LAST_INSERT_ID 原子自增；终身不复用）',
    PRIMARY KEY (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '连接器源编号全局序号（1 行原子取号）';

INSERT INTO connector_source_no_seq (id, next_no) VALUES (1, 1);

-- 自检失败原因枚举名最长 22 字符（INSUFFICIENT_PERMISSION），留痕 reason_code 由 16 扩至 32：
-- 失败原因须机读可查（审计口径），且不新增表列（仅放宽既有列宽，加性、零语义变化）。
ALTER TABLE connector_action_log
    MODIFY COLUMN reason_code VARCHAR(32) NULL COMMENT '拒绝码 / 失败码（1010 段码或自检失败原因枚举）';
