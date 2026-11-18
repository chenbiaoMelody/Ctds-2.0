-- 产品语义向量缓存表（WBS-3.10.2 B2；规格 C-11.2 V1.0 / ADR-003 §7）
-- 查询时惰性重算 + 回写；下架/注销行保留，检索按 data_product.status = 'LISTED' 硬过滤；
-- 无业务外键（产品 id 逻辑引用）；向量为小端 float32 序列（长度 = dims × 4）。
CREATE TABLE product_embedding (
    product_id   BIGINT            NOT NULL COMMENT '产品 id（逻辑引用 data_product.id，不建外键）',
    source_hash  CHAR(64)          NOT NULL COMMENT '索引源文本 SHA-256（小写十六进制；变更检测用，非安全用途）',
    dims         SMALLINT UNSIGNED NOT NULL COMMENT '向量维度（V1.0 恒 384）',
    vector       BLOB              NOT NULL COMMENT '向量二进制（小端 float32 序列；长度 = dims × 4）',
    updated_at   DATETIME          NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
                                   COMMENT '最近回写时间',
    PRIMARY KEY (product_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4
  COMMENT = '产品语义向量缓存表（WBS-3.10.2；查询时惰性重算+回写；下架/注销行保留，检索按 data_product.status LISTED 硬过滤）';
