-- WBS-3.6.4 连接器生命周期（升级 / 版本兼容 / 注销）迁移（hifi §2）：
-- connector_version_change = 升级登记 + 版本历史链（PENDING_CONFIRM / APPLIED / VOIDED；单表承双职责，不双写）
-- connector_job_occupancy  = 在途交付作业占用台账（注销前置检查；由使用方登记，接入归 3.6.8）
-- connector_upgrade_no_seq = UC+6 位升级单号全局序号（1 行原子取号）
-- connector_registration   = 增列 cancelled_at（注销终态时间）；三主态与活跃态生成列沿用 V1，零改结构

CREATE TABLE connector_version_change (
    id                   BIGINT       NOT NULL AUTO_INCREMENT COMMENT '技术主键（自增）',
    upgrade_no           VARCHAR(16)  NOT NULL COMMENT 'UC+6位升级单号（登记即签发）',
    connector_no         VARCHAR(16)  NOT NULL COMMENT 'CN+6位连接器编号',
    subject_no           VARCHAR(32)  NOT NULL COMMENT '所属主体快照（提交时属主）',
    from_version         VARCHAR(16)  NOT NULL COMMENT '变更前版本（提交时的当前版本）',
    to_version           VARCHAR(16)  NOT NULL COMMENT '目标版本：主.次.修订（各 1~3 位数字）',
    compatibility        VARCHAR(16)  NOT NULL COMMENT 'COMPATIBLE/INCOMPATIBLE（兼容性声明）',
    status               VARCHAR(24)  NOT NULL COMMENT 'PENDING_CONFIRM待确认/APPLIED已生效/VOIDED注销作废',
    pending_connector_no VARCHAR(16)  GENERATED ALWAYS AS (IF(status = 'PENDING_CONFIRM', connector_no, NULL)) STORED COMMENT '待确认唯一键（同连接器至多 1 条待确认；APPLIED/VOIDED 后置 NULL 释放槽位）',
    requested_by         VARCHAR(32)  NOT NULL COMMENT '提交人（属主主体编号）',
    confirmed_by         VARCHAR(32)  NULL COMMENT '显式确认人（运营方；兼容升级与未确认为 NULL）',
    applied_at           DATETIME     NULL COMMENT '生效时间（APPLIED 时写入）',
    created_at           DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '提交时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_upgrade_no (upgrade_no),
    UNIQUE KEY uk_pending_connector (pending_connector_no),
    KEY idx_version_connector (connector_no, created_at),
    KEY idx_version_status (status, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '连接器版本变更（升级登记 + 版本历史链）';

CREATE TABLE connector_job_occupancy (
    id           BIGINT      NOT NULL AUTO_INCREMENT COMMENT '技术主键（自增）',
    connector_no VARCHAR(16) NOT NULL COMMENT 'CN+6位连接器编号',
    job_ref      VARCHAR(64) NOT NULL COMMENT '作业引用（使用方提供，业务可读，不含秘密材料）',
    status       VARCHAR(16) NOT NULL COMMENT 'OCCUPY在途/RELEASED已释放',
    occupied_at  DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '最近登记时间（重复登记刷新）',
    released_at  DATETIME    NULL COMMENT '释放时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_connector_job (connector_no, job_ref),
    KEY idx_occupancy_status (connector_no, status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '在途交付作业占用台账（注销前置检查口径）';

CREATE TABLE connector_upgrade_no_seq (
    id       BIGINT NOT NULL COMMENT '固定为 1（单行取号表）',
    next_no  BIGINT NOT NULL COMMENT '下一个可签发序号（LAST_INSERT_ID 原子自增）',
    PRIMARY KEY (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '连接器升级单号全局序号（1 行原子取号）';

INSERT INTO connector_upgrade_no_seq (id, next_no) VALUES (1, 1);

ALTER TABLE connector_registration
    ADD COLUMN cancelled_at DATETIME NULL COMMENT '注销时间（终态时间；WBS-3.6.4）' AFTER last_heartbeat_at;
