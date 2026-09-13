-- 主体库（WBS-3.1.2，hifi"库表设计"定稿；规格 C-1.1 行为 1/4）。证照影像/OCR 结果表随 3.1.3 建表
-- （影像存储介质 = SM4 加密落库，lofi Q2 裁决），政务 CA 材料表随 3.1.4。
CREATE TABLE subject (
    id            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '技术主键',
    subject_no    VARCHAR(24)  NOT NULL COMMENT '申请编号（业务标识，S+日期+6位当日序号）',
    subject_name  VARCHAR(128) NOT NULL COMMENT '主体名称',
    uscc          VARCHAR(18)  NOT NULL COMMENT '统一社会信用代码（全平台唯一，唯一索引兜底）',
    subject_type  VARCHAR(16)  NOT NULL COMMENT '主体类型：ENTERPRISE企业/INSTITUTION机构/GOV政府部门',
    reg_address   VARCHAR(256) NOT NULL COMMENT '注册地址',
    contact_name  VARCHAR(64)  NOT NULL COMMENT '联系人姓名',
    contact_phone VARCHAR(32)  NOT NULL COMMENT '联系电话（存储明文，展示层脱敏保留前3后4；L4 字段加密口径见 3.1.3）',
    admin_account VARCHAR(64)  NOT NULL COMMENT '管理员账号（V1.0 仅信息收集，不涉账号开通）',
    status        VARCHAR(20)  NOT NULL COMMENT '状态：PENDING_CERT待认证/PENDING_REVIEW待审核/ADMITTED已入驻/CERT_FAILED认证失败/REJECTED已驳回',
    created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_subject_no (subject_no),
    UNIQUE KEY uk_uscc (uscc),
    KEY idx_status (status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '主体表（一行=一个主体，重报复用行不新建，见流转留痕）';

-- 状态流转留痕（规格行为 4 第 2 条：前状态/后状态/触发方/时间 + 操作人与备注）
CREATE TABLE subject_status_log (
    id           BIGINT       NOT NULL AUTO_INCREMENT COMMENT '技术主键',
    subject_id   BIGINT       NOT NULL COMMENT '主体 id（subject.id）',
    from_status  VARCHAR(20)  NOT NULL COMMENT '流转前状态（注册建档记 NONE）',
    to_status    VARCHAR(20)  NOT NULL COMMENT '流转后状态',
    trigger_role VARCHAR(16)  NOT NULL COMMENT '触发方：APPLICANT申请人/SYSTEM系统/REVIEWER审核员',
    operator     VARCHAR(64)  NOT NULL COMMENT '操作人标识（AuthContext.subject()）',
    remark       VARCHAR(256) NULL COMMENT '备注（撤销标记契约：最新留痕 remark=申请人撤销，待重新提交）',
    created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '流转时间',
    PRIMARY KEY (id),
    KEY idx_subject (subject_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '主体状态流转留痕';

-- 申请编号当日序号（行级原子自增取号：INSERT ... ON DUPLICATE KEY UPDATE）
CREATE TABLE subject_daily_seq (
    seq_date DATE NOT NULL COMMENT '序号日期',
    seq_val  INT  NOT NULL COMMENT '当日已发序号',
    PRIMARY KEY (seq_date)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '申请编号当日序号';
