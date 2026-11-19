-- =============================================================================
-- AI 调用日志表（V1.0 预留占位 —— WBS 2.4.4；结构定稿 WBS 3.10.3）
-- 状态：仅表结构预留。禁止在 V1.0 建表或写入；V1.5 由 C-8.4 / C-11.5 填充归属服务与 Flyway 实施。
-- 依据：PRD G4（V1.0 仅预留）+ docs/designs/WBS-2.4.4-hifi.md + WBS-3.10.3-hifi.md §4 + ADR-005 §3 第 6 项。
-- 隐私口径：仅元数据，禁止 prompt / 响应原文 / 密钥 / 个人信息（数据分级 CAT-01 / L2）。
-- =============================================================================

CREATE TABLE IF NOT EXISTS ai_call_log (
    id                BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    trace_id          VARCHAR(64)  NOT NULL DEFAULT '-'    COMMENT '链路追踪 ID（与平台 traceId 同源）',
    feature           VARCHAR(64)  NOT NULL DEFAULT '-'    COMMENT '业务用途（如 corpus_summary）',
    actor             VARCHAR(64)  NOT NULL DEFAULT '-'    COMMENT '调用主体',
    provider          VARCHAR(64)  NOT NULL DEFAULT '-'    COMMENT '模型服务商',
    model             VARCHAR(128) NOT NULL DEFAULT '-'    COMMENT '模型标识',
    prompt_tokens     INT          NOT NULL DEFAULT 0      COMMENT '输入 token 数',
    completion_tokens INT          NOT NULL DEFAULT 0      COMMENT '输出 token 数',
    latency_ms        INT          NULL                    COMMENT '耗时毫秒',
    outcome           VARCHAR(16)  NOT NULL                COMMENT '结果：SUCCESS / DENIED / FAILURE',
    error_code        VARCHAR(16)  NULL                    COMMENT '失败时的平台错误码（V1.5 映射）',
    guardrail_verdict VARCHAR(16)  NULL                    COMMENT '护栏判定：ALLOW / DENY',
    guardrail_reason  VARCHAR(64)  NULL                    COMMENT '护栏可读原因码',
    created_at        DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间（UTC）',
    PRIMARY KEY (id),
    KEY idx_ai_call_log_trace (trace_id),
    KEY idx_ai_call_log_created (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI 调用审计日志（V1.5 启用）';