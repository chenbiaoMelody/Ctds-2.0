-- 实名认证集成（WBS-3.1.3，设计定稿 docs/designs/WBS-3.1.3-hifi.md 库表设计节；规格 C-1.1 行为 2/3/7）。
-- ① subject 表加申请人身份列（ADR-016 §2.6 裁决方案①：对象级归属断言的数据基础）；
--    存量行回填迁移占位标识 legacy-demo（演示前可整库重建，新建主体一律取注册时真实身份）。
-- ② 证照材料表：影像与 OCR 原始结果按 L4 管控（SM4 加密落库，唯一入口 common-crypto，ADR-006）。
-- ③ 认证渠道调用记录表：渠道标识/请求流水号/结论三要素 + 耗时全程留痕（规格行为 7 第 3 条）。

ALTER TABLE subject
    ADD COLUMN applicant VARCHAR(64) NOT NULL DEFAULT 'legacy-demo'
    COMMENT '申请人身份（注册建档取 AuthContext 当前身份；归属断言依据，ADR-016 §2.6）' AFTER admin_account;

CREATE TABLE cert_material (
    id                BIGINT       NOT NULL AUTO_INCREMENT COMMENT '技术主键',
    subject_id        BIGINT       NOT NULL COMMENT '主体 id（subject.id）',
    material_type     VARCHAR(32)  NOT NULL COMMENT '材料类型：BUSINESS_LICENSE 营业执照',
    file_name         VARCHAR(256) NOT NULL COMMENT '上传文件名',
    content_sm3      CHAR(64)     NOT NULL COMMENT '影像 SM3 摘要（完整性锚点，唯一入口 common-crypto，ADR-006 Q2 裁决）',
    content_cipher    LONGBLOB     NOT NULL COMMENT '影像 SM4 密文（ADR-006 唯一入口加密后落库）',
    ocr_raw_cipher    LONGBLOB     NULL COMMENT 'OCR 原始识别结果 JSON 的 SM4 密文（L4 双要素之二）',
    ocr_uscc          VARCHAR(18)  NULL COMMENT 'OCR 识别的统一社会信用代码（差异比对锚点，组织信息非 L4）',
    ocr_legal_person  VARCHAR(64)  NULL COMMENT 'OCR 识别的法定代表人姓名（核验一致性比对锚点）',
    ocr_recognizable  TINYINT      NOT NULL DEFAULT 1 COMMENT '渠道是否识别成功（0=无法识别，不产生部分识别结果）',
    confirmed_name    VARCHAR(128) NULL COMMENT '申请人确认后的主体名称（核对确认值，明文）',
    confirmed_uscc    VARCHAR(18)  NULL COMMENT '确认后的统一社会信用代码',
    confirmed_legal_person VARCHAR(64) NULL COMMENT '确认后的法定代表人姓名',
    confirmed_reg_address  VARCHAR(256) NULL COMMENT '确认后的注册地址',
    confirmed_at      DATETIME     NULL COMMENT '确认时间',
    created_at        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '上传时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_subject_type (subject_id, material_type)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '证照材料（一行=一张影像及 OCR 结果；重复上传替换保留最近一次）';

CREATE TABLE cert_verification_log (
    id                     BIGINT       NOT NULL AUTO_INCREMENT COMMENT '技术主键',
    subject_id             BIGINT       NOT NULL COMMENT '主体 id（subject.id）',
    verify_type            VARCHAR(32)  NOT NULL COMMENT '调用类型：LEGAL_PERSON 法人核验 / OCR_LICENSE 证照 OCR',
    channel_code           VARCHAR(32)  NOT NULL COMMENT '渠道标识（mock-certification）',
    channel_request_no     VARCHAR(64)  NULL COMMENT '渠道请求流水号（技术异常时可能为空）',
    legal_person_name      VARCHAR(64)  NULL COMMENT '提交的法人姓名（仅核验调用）',
    legal_person_id_cipher VARCHAR(512) NULL COMMENT '提交的身份证号 SM4 密文（L4，仅核验调用）',
    conclusion             VARCHAR(16)  NOT NULL COMMENT '结论：PASS通过/FAIL不通过/CHANNEL_ERROR渠道异常/UNRECOGNIZABLE不可识别',
    fail_reason            VARCHAR(256) NULL COMMENT '失败原因（业务可读）',
    cost_ms                INT          NOT NULL COMMENT '渠道调用耗时毫秒',
    counted                TINYINT      NOT NULL DEFAULT 0 COMMENT '是否计入当日核验失败次数（仅 LEGAL_PERSON 且 FAIL=1）',
    created_at             DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '发起时间',
    PRIMARY KEY (id),
    KEY idx_subject_day (subject_id, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '认证渠道调用记录（三要素+耗时全程留痕；渠道异常不计失败次数）';
