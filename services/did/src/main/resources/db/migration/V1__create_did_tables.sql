-- DID 身份注册表 + 操作留痕（WBS-3.1.8，hifi §2.1/§2.2）：无私钥列（规格行为 1 规则 3）。
CREATE TABLE did_identity (
    id             BIGINT        NOT NULL AUTO_INCREMENT,
    subject_no     VARCHAR(32)   NOT NULL,
    issuance_seq   INT           NOT NULL,
    did            VARCHAR(128)  NULL,
    status         VARCHAR(16)   NOT NULL,
    public_key_hex VARCHAR(130)  NULL,
    key_ref        VARCHAR(64)   NULL,
    document_json  VARCHAR(2048) NULL,
    guard_key      VARCHAR(32)   NULL,
    created_at     DATETIME      NOT NULL,
    updated_at     DATETIME      NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_did (did),
    UNIQUE KEY uk_guard (guard_key)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 签发/重签/吊销留痕（一表承载，nullable 列区分；签发四要素/吊销五要素）
CREATE TABLE did_operation_log (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    did         VARCHAR(128) NULL,
    subject_no  VARCHAR(32)  NOT NULL,
    operation   VARCHAR(16)  NOT NULL,
    operator    VARCHAR(128) NOT NULL,
    reason      VARCHAR(256) NULL,
    key_ref     VARCHAR(64)  NULL,
    status_from VARCHAR(16)  NULL,
    status_to   VARCHAR(16)  NULL,
    occurred_at DATETIME     NOT NULL,
    PRIMARY KEY (id),
    KEY idx_did_op_log_subject (subject_no)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
