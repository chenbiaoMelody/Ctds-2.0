-- KMS 密钥库（WBS-2.6.3，hifi §3.2）：密钥材料列只有根密钥 SM4 信封的 Base64，无明文列（规格行为 1）。
CREATE TABLE kms_key (
    key_ref         VARCHAR(64)  NOT NULL,
    status          VARCHAR(16)  NOT NULL,
    current_version INT          NOT NULL,
    created_at      DATETIME     NOT NULL,
    PRIMARY KEY (key_ref)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE kms_key_version (
    key_ref         VARCHAR(64)  NOT NULL,
    version         INT          NOT NULL,
    material_cipher VARCHAR(128) NOT NULL,
    created_at      DATETIME     NOT NULL,
    PRIMARY KEY (key_ref, version)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 轮换/创建审计（规格行为 2：操作者、时间、编号、新旧版本四要素）
CREATE TABLE kms_key_audit (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    action      VARCHAR(16)  NOT NULL,
    key_ref     VARCHAR(64)  NOT NULL,
    old_version INT          NULL,
    new_version INT          NULL,
    operator    VARCHAR(128) NOT NULL,
    occurred_at DATETIME     NOT NULL,
    PRIMARY KEY (id),
    KEY idx_kms_key_audit_ref (key_ref)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
