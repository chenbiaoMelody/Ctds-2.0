-- WBS-3.5.5 结果审核与导出迁移（hifi §2）：
-- sandbox_result = 结果物登记（一任务一文件名一行；提交审核时登记 + 快照 SM3/字节数）；
-- sandbox_result_review = 审核留痕（一轮一行，不可变；结论 + 理由 + 轮次）；
-- delivery 加列 sandbox_no / result_name 承载 SANDBOX_EXPORT 导出记录（3.7.5/3.8.3 单一交付事件源）；
-- sandbox_action_log 加列 result_name（结果物维度动作与拒绝留痕）。
-- 只存业务要素与校验值：无内容、无宿主机路径、无容器/镜像/命令、无密钥材料。
-- 偏差说明：hifi §2.3 称 delivery_type 列类型不变，但 V1 现状 VARCHAR(8) 容不下
-- SANDBOX_EXPORT（14 字符）——V3 放宽为 VARCHAR(16)，既有 API/FILE 值零变更（纯列宽放大）。

CREATE TABLE sandbox_result (
    id               BIGINT        NOT NULL AUTO_INCREMENT,
    sandbox_no       VARCHAR(16)   NOT NULL,
    file_name        VARCHAR(128)  NOT NULL COMMENT '结果物标识（工作区 output 顶层文件名）',
    status           VARCHAR(16)   NOT NULL COMMENT 'PENDING/APPROVED/REJECTED',
    review_round     INT           NOT NULL COMMENT '审核轮次（首次提交 = 1，重新提交 +1）',
    size_bytes       BIGINT        NOT NULL COMMENT '提交时快照（字节数）',
    checksum         VARCHAR(64)   NOT NULL COMMENT '提交时快照（SM3 小写 hex）',
    submitted_by     VARCHAR(32)   NOT NULL COMMENT '需求方主体号',
    submitted_at     DATETIME      NOT NULL,
    reviewed_by      VARCHAR(32)   NULL COMMENT '最近一轮审核人（运营方）',
    reviewed_at      DATETIME      NULL,
    reject_reason    VARCHAR(256)  NULL COMMENT '最近一轮驳回理由（业务文本）',
    idempotency_key  VARCHAR(128)  NOT NULL COMMENT '最近一次提交的幂等键',
    created_at       DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at       DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_sandbox_result (sandbox_no, file_name),
    KEY idx_sandbox_result_status (sandbox_no, status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '沙箱结果物登记（提交审核时快照；不含内容与路径）';

CREATE TABLE sandbox_result_review (
    id           BIGINT        NOT NULL AUTO_INCREMENT,
    sandbox_no   VARCHAR(16)   NOT NULL,
    file_name    VARCHAR(128)  NOT NULL,
    review_round INT           NOT NULL COMMENT '对应提交轮次',
    conclusion   VARCHAR(16)   NOT NULL COMMENT 'APPROVED/REJECTED',
    reason       VARCHAR(256)  NULL COMMENT '驳回必填 / 通过可空（业务文本）',
    reviewed_by  VARCHAR(32)   NOT NULL COMMENT '运营方主体号',
    created_at   DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '审核时刻',
    PRIMARY KEY (id),
    KEY idx_sandbox_review (sandbox_no, file_name, review_round)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '沙箱结果审核留痕（一轮一行不可变；不含内容与路径）';

ALTER TABLE delivery
    MODIFY delivery_type VARCHAR(16) NOT NULL COMMENT 'API/FILE/SANDBOX_EXPORT',
    ADD COLUMN sandbox_no VARCHAR(16) NULL COMMENT '沙箱任务号（仅 SANDBOX_EXPORT 行非空）',
    ADD COLUMN result_name VARCHAR(128) NULL COMMENT '结果物标识（仅 SANDBOX_EXPORT 行非空）',
    ADD KEY idx_delivery_sandbox (sandbox_no);

ALTER TABLE sandbox_action_log
    ADD COLUMN result_name VARCHAR(128) NULL COMMENT '结果物维度动作与拒绝留痕填写';
