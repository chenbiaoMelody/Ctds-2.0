-- WBS-3.5.4 沙箱子域首版迁移（hifi §2）：
-- sandbox_task = 沙箱任务（合约凭据 + 产品快照 + 配额快照 + 生命周期状态）；
-- sandbox_job = 受控作业流水（3.7.5 计量事件源，一次作业 = 一次数据使用）；
-- sandbox_action_log = 沙箱动作留痕（与 common-logging 审计并行）；
-- sandbox_no_seq / sandbox_job_no_seq = SB/SJ + 6 位全局序号（原子取号，沿 delivery_no_seq 先例）。
-- 只存业务要素与校验值：无宿主机路径、无容器 ID/名、无镜像、无命令、无内容、无密钥材料。

CREATE TABLE sandbox_task (
    id                BIGINT        NOT NULL AUTO_INCREMENT,
    sandbox_no        VARCHAR(16)   NOT NULL COMMENT 'SB+6位全局序号',
    contract_no       VARCHAR(32)   NOT NULL COMMENT '交付凭据（已生效合约编号）',
    product_id        BIGINT        NOT NULL COMMENT '合约产品 id 快照',
    product_name      VARCHAR(128)  NOT NULL COMMENT '产品名快照',
    requester_no      VARCHAR(32)   NOT NULL COMMENT '合约需求方快照（发起人）',
    provider_no       VARCHAR(32)   NOT NULL COMMENT '合约提供方快照',
    status            VARCHAR(16)   NOT NULL COMMENT 'RUNNING/STOPPED/EXPIRED',
    cpus              DECIMAL(3,2)  NOT NULL COMMENT '配额快照（申请不可自选）',
    memory_mb         INT           NOT NULL COMMENT '内存配额快照（MB）',
    ttl_seconds       INT           NOT NULL COMMENT '时长配额快照（秒）',
    expires_at        DATETIME      NOT NULL COMMENT '到期时刻（服务端判定）',
    data_file_count   INT           NOT NULL COMMENT '注入的数据文件数（不含名称/路径）',
    close_reason      VARCHAR(16)   NULL COMMENT 'CLOSED/EXPIRED/RECONCILED',
    idempotency_key   VARCHAR(128)  NOT NULL,
    created_at        DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at        DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_sandbox_no (sandbox_no),
    UNIQUE KEY uk_sandbox_idem (requester_no, idempotency_key),
    KEY idx_sandbox_contract (contract_no, created_at),
    KEY idx_sandbox_requester (requester_no, created_at),
    KEY idx_sandbox_provider (provider_no, created_at),
    KEY idx_sandbox_status (status, expires_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '沙箱任务（容器隔离环境 + 配额 + 生命周期）';

CREATE TABLE sandbox_job (
    id                BIGINT        NOT NULL AUTO_INCREMENT,
    job_no            VARCHAR(16)   NOT NULL COMMENT 'SJ+6位全局序号',
    sandbox_no        VARCHAR(16)   NOT NULL,
    job_id            VARCHAR(64)   NOT NULL COMMENT '配置化作业编号',
    status            VARCHAR(16)   NOT NULL COMMENT 'RUNNING/SUCCEEDED/FAILED/TIMEOUT',
    exit_code         INT           NULL COMMENT '容器内退出码',
    duration_ms       BIGINT        NULL COMMENT '执行耗时（毫秒）',
    purpose           VARCHAR(256)  NULL COMMENT '策略要素（业务要素，不含原文数据）',
    territory         VARCHAR(256)  NULL COMMENT '策略要素（业务要素，不含原文数据）',
    idempotency_key   VARCHAR(128)  NOT NULL,
    created_at        DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    finished_at       DATETIME      NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_sandbox_job_no (job_no),
    UNIQUE KEY uk_sandbox_job_idem (sandbox_no, idempotency_key),
    KEY idx_sandbox_job_task (sandbox_no, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '沙箱受控作业流水（不含 stdout/stderr 与产物内容）';

CREATE TABLE sandbox_action_log (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    sandbox_no   VARCHAR(16)  NULL COMMENT '拒绝类动作可为空',
    job_no       VARCHAR(16)  NULL,
    contract_no  VARCHAR(32)  NULL,
    operator_no  VARCHAR(32)  NOT NULL,
    action       VARCHAR(64)  NOT NULL,
    result       VARCHAR(16)  NOT NULL COMMENT 'SUCCESS/DENIED/FAILURE',
    reason_code  VARCHAR(16)  NULL,
    created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_sandbox_log_task (sandbox_no, created_at),
    KEY idx_sandbox_log_contract (contract_no, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '沙箱动作留痕（不含内容/路径/容器/镜像/命令）';

CREATE TABLE sandbox_no_seq (
    id       BIGINT NOT NULL,
    next_no  BIGINT NOT NULL,
    PRIMARY KEY (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '沙箱编号全局序号（1 行原子取号）';

INSERT INTO sandbox_no_seq (id, next_no) VALUES (1, 1);

CREATE TABLE sandbox_job_no_seq (
    id       BIGINT NOT NULL,
    next_no  BIGINT NOT NULL,
    PRIMARY KEY (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '沙箱作业编号全局序号（1 行原子取号）';

INSERT INTO sandbox_job_no_seq (id, next_no) VALUES (1, 1);
