-- 跨空间互认留痕（WBS-3.1.10 hifi §3，行为 5 规则 2）：方向/对端空间标识/DID/结果（失败另含原因）/时间；
-- 不保存业务数据原文（互认通道不是数据存储通道）；时间戳经应用时钟写入，不走 DB NOW()（ADR-017 §2.2）。
CREATE TABLE did_interop_log (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    direction   VARCHAR(16)  NOT NULL COMMENT 'INBOUND 来访 / OUTBOUND 出向',
    peer_space  VARCHAR(64)  NOT NULL COMMENT '对端空间标识（演示期 = 模拟对端）',
    did         VARCHAR(128) NOT NULL COMMENT '被验证的 DID（对端 DID 或本空间 DID）',
    result      VARCHAR(16)  NOT NULL COMMENT 'PASS / FAIL / UNAVAILABLE',
    reason      VARCHAR(32)  NULL COMMENT 'SIGNATURE_INVALID / REVOKED / SUBJECT_BINDING_FAILED / NOT_REGISTERED / BINDING_UNAVAILABLE',
    occurred_at DATETIME     NOT NULL COMMENT '应用时钟（秒级）',
    PRIMARY KEY (id),
    KEY idx_interop_did_time (did, occurred_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '跨空间互认留痕';
