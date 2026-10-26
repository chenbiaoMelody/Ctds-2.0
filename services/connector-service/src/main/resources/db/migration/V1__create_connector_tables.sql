-- WBS-3.6.3 连接器注册与核验服务首版迁移（hifi §2）：
-- connector_registration   = 注册申请与档案（待核验 → 已注册；CANCELLED 仅预留，注销归 3.6.4）
-- connector_did_binding    = 连接器 ↔ 所属主体 DID 绑定（不冗余 DID 状态：单一事实源在 DID 域）
-- connector_action_log     = 域内动作留痕（申请/补正/核验/绑定查验/心跳/拒绝；不含任何秘密材料）
-- connector_application_no_seq / connector_no_seq = CA/CN 双轨全局序号（各 1 行原子取号）

CREATE TABLE connector_registration (
    id                   BIGINT       NOT NULL AUTO_INCREMENT,
    application_no       VARCHAR(16)  NOT NULL COMMENT 'CA+6位申请单号（登记即签发）',
    connector_no         VARCHAR(16)  NULL COMMENT 'CN+6位连接器编号（核验通过事务内签发；待核验态为 NULL）',
    subject_no           VARCHAR(32)  NOT NULL COMMENT '所属主体编号（申请者身份，服务端从鉴权上下文取）',
    connector_name       VARCHAR(64)  NOT NULL COMMENT '连接器名称（去首尾空白后 2~64 字符）',
    connector_type       VARCHAR(16)  NOT NULL COMMENT 'STANDARD（V1.0 唯一取值；轻量化属 P1 非本卡）',
    connector_version    VARCHAR(16)  NOT NULL COMMENT '版本号：主.次.修订（各 1~3 位数字）',
    status               VARCHAR(16)  NOT NULL DEFAULT 'PENDING_REVIEW' COMMENT 'PENDING_REVIEW/REGISTERED/CANCELLED（CANCELLED 仅预留，注销归 3.6.4）',
    active_name_norm     VARCHAR(64)  GENERATED ALWAYS AS (IF(status = 'CANCELLED', NULL, LOWER(TRIM(connector_name)))) STORED COMMENT '活跃态归一化名称（注销后可同名重注册，行为 10 验收 4 前瞻）',
    last_rejection_code  VARCHAR(16)  NULL COMMENT '最近一次核验拒绝码（1010C0005/1010C0006/1010C0007）',
    last_rejection_reason VARCHAR(256) NULL COMMENT '最近一次核验拒绝理由（服务端常量文案）',
    last_heartbeat_at    DATETIME     NULL COMMENT '最近心跳时间（健康度读时派生，无调度）',
    created_at           DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at           DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_application_no (application_no),
    UNIQUE KEY uk_connector_no (connector_no),
    UNIQUE KEY uk_subject_active_name (subject_no, active_name_norm),
    KEY idx_status_created (status, created_at),
    KEY idx_subject_status (subject_no, status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '连接器注册申请与档案（待核验 → 已注册）';

CREATE TABLE connector_did_binding (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    connector_no  VARCHAR(16)  NOT NULL COMMENT 'CN+6位连接器编号',
    subject_no    VARCHAR(32)  NOT NULL COMMENT '所属主体编号（绑定对象）',
    did           VARCHAR(128) NOT NULL COMMENT '所属主体 DID 快照（非秘密材料）',
    status        VARCHAR(16)  NOT NULL COMMENT 'ACTIVE/RELEASED（RELEASED 归 3.6.4 注销）',
    bound_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '绑定建立时间',
    released_at   DATETIME     NULL COMMENT '绑定解除时间（3.6.4）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_connector_binding (connector_no),
    KEY idx_binding_did (did)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '连接器与所属主体 DID 绑定（DID 状态不冗余，查验实时读取）';

CREATE TABLE connector_action_log (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    application_no  VARCHAR(16)  NULL COMMENT '申请单号（申请/补正/核验类动作）',
    connector_no    VARCHAR(16)  NULL COMMENT '连接器编号（核验通过后动作）',
    operator_no     VARCHAR(32)  NOT NULL COMMENT '操作者（申请人 / 核验人 / 属主）',
    action          VARCHAR(64)  NOT NULL COMMENT 'connector.apply/amend/verify.approved/verify.rejected/binding.read/heartbeat/denied',
    result          VARCHAR(16)  NOT NULL COMMENT 'SUCCESS/DENIED/FAILURE',
    reason_code     VARCHAR(16)  NULL COMMENT '拒绝码 / 失败码',
    detail          VARCHAR(256) NULL COMMENT '业务要素摘要（名称/类型/版本/绑定 DID/结论/旧值），不含凭据',
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_log_application (application_no, created_at),
    KEY idx_log_connector (connector_no, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '连接器域动作留痕（不含秘密材料）';

CREATE TABLE connector_application_no_seq (
    id       BIGINT NOT NULL,
    next_no  BIGINT NOT NULL,
    PRIMARY KEY (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '连接器申请单号全局序号（1 行原子取号）';

INSERT INTO connector_application_no_seq (id, next_no) VALUES (1, 1);

CREATE TABLE connector_no_seq (
    id       BIGINT NOT NULL,
    next_no  BIGINT NOT NULL,
    PRIMARY KEY (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '连接器编号全局序号（1 行原子取号）';

INSERT INTO connector_no_seq (id, next_no) VALUES (1, 1);
