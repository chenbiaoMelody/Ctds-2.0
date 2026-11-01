-- 统一计量流水单表（WBS-3.7.5 hifi §2 定稿；规格 docs/specs/C-7.1-7.2-门户与计费.md V1.0 B7/B8）：
-- 四源事件 1:1 映射（GATEWAY_API / FILE_DELIVERY / SANDBOX_JOB / SANDBOX_EXPORT，MeteringSourceType）；
-- uk(source_type, source_no) 幂等锚（Q8：网关事件重放 / 采集重复执行零重计）；
-- 聚合 = 查询承载（Q2-A：无物化聚合表，GROUP BY 合约 × 自然月，idx_flow_contract 前缀覆盖）；
-- 列面 = ADR-022 §2.6-2 最小必要投影，零请求体/响应体/原文/令牌列（B7-1）；
-- 不双计（B7-3）：API 调用计量源唯一 = 网关事件（GATEWAY_API 行），文件/沙箱腿采集排除 API 行；
-- 数据分级落级（分级规范 §6.1 口径）：L1（业务元数据，无内容本体与敏感原文）。

CREATE TABLE metering_flow (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    contract_no  VARCHAR(32)  NOT NULL COMMENT '合约编号（勾稽与归属锚；CO+6位，沿 contract uk_contract_no 同域）',
    requester_no VARCHAR(32)  NOT NULL COMMENT '计量主体（网关事件 subject / 交付需求方快照）',
    source_type  VARCHAR(24)  NOT NULL COMMENT 'GATEWAY_API / FILE_DELIVERY / SANDBOX_JOB / SANDBOX_EXPORT（四源枚举，MeteringSourceType）',
    source_no    VARCHAR(64)  NOT NULL COMMENT '源单号（网关 eventId / delivery_no / job_no）——幂等锚',
    action       VARCHAR(48)  NOT NULL COMMENT '动作名（gateway.delivery.metering / delivery.file / delivery.sandbox_export / sandbox.job——采集常量映射）',
    resource     VARCHAR(128) NULL     COMMENT '被交付资源（网关腿 = routeId+方法+路径模板；文件/沙箱腿 NULL）',
    occurred_at  DATETIME     NOT NULL COMMENT '事件时点（事件源时点，非采集时点）',
    created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '采集落库时点',
    PRIMARY KEY (id),
    UNIQUE KEY uk_metering_source (source_type, source_no),
    KEY idx_flow_contract (contract_no, occurred_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '统一计量流水（四源事件 1:1 映射；uk 幂等；ADR-022 §2.6-2 最小必要字段，零敏感原文列）';
