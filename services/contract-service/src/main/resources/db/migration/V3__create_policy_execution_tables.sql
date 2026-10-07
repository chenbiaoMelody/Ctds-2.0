-- 策略执行引擎两表（WBS-3.4.5 hifi §5 定稿；规格 docs/specs/C-4.1-4.3-数字合约与使用控制.md
-- V1.0 行为 5；Q4-A 判检一体 + Q7-A 执行记录与摘要读面）。
-- contract_usage_counter：配额余额口径（judgment 步 9 单语句条件 UPDATE 原子递增——防"先查后增"
-- 超卖；无乐观锁版本列，无 SELECT FOR UPDATE）；contract_usage_log：执行流水口径（放行/拒绝
-- 统一执行记录：谁/何时/哪份合约/动作类型/触发要素/结果——留痕四要素口径，不含条款原文与请求
-- 文本原文，violations 只落枚举名，规避 L3 分级纠缠）。
-- 对账数据源（移交-3 预登记）：counter = 余额口径、log = 流水口径——3.7.x 对账以两表交叉验证。
-- 数据分级落级（hifi §5，分级规范 §6.1 同步行）：两表均 L1（业务元数据）。

CREATE TABLE contract_usage_counter (
  contract_no   VARCHAR(20)  NOT NULL,
  used_count    INT          NOT NULL DEFAULT 0,
  last_used_at  DATETIME     NULL,
  PRIMARY KEY (contract_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE contract_usage_log (
  id            BIGINT       NOT NULL AUTO_INCREMENT,
  contract_no   VARCHAR(20)  NOT NULL,
  requester_no  VARCHAR(20)  NOT NULL,
  action_type   VARCHAR(16)  NOT NULL,            -- USE / REDISTRIBUTE
  outcome       VARCHAR(8)   NOT NULL,            -- ALLOWED / DENIED
  reason_code   VARCHAR(16)  NULL,                -- DENIED 时：C0020 / C0013
  violations    VARCHAR(128) NULL,                -- DENIED 时：触发要素码逗号分隔（枚举名，非用户文本）
  used_count    INT          NULL,                -- ALLOWED 时：递增后计数；DENIED 时：当前不变值
  occurred_at   DATETIME     NOT NULL,
  PRIMARY KEY (id),
  KEY idx_usage_log_contract (contract_no, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
