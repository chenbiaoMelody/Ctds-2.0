-- DID 验证留痕（WBS-3.1.9 hifi §3，行为 3 规则 3）：时间/DID/结果（失败或不可用另含原因）；
-- 不保存业务数据原文（验证通道不是数据存储通道）。
CREATE TABLE did_verification_log (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    did         VARCHAR(128) NOT NULL,
    result      VARCHAR(16)  NOT NULL,
    reason      VARCHAR(32)  NULL,
    occurred_at DATETIME     NOT NULL,
    PRIMARY KEY (id),
    KEY idx_verification_did_time (did, occurred_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;