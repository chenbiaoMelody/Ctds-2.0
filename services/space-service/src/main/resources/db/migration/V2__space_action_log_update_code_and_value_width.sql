-- 留痕表动作码登记与值列放宽（WBS-3.2.3，hifi §1 端点 6 / T11 留痕"从何值→到何值"）。
-- 零破坏原则：不改既有动作码、不收窄任何列；V1 逐字冻结（checksum 不变，既有库可直接前滚）。

-- ① 动作码值域登记 UPDATE（空间配置变更，3.2.3 端点 6）：
--    V1 列注释口径"值域随下游包扩展，扩展须登记本注释"——本语句即登记动作本身（仅改注释，不改值域约束）。
ALTER TABLE space_action_log
    MODIFY COLUMN action VARCHAR(32) NOT NULL
    COMMENT '动作码：CREATE/ENABLE/FREEZE/UNFREEZE/DISSOLVE/UPDATE/ADMIT_REQUEST/ADMIT_INVITE/ADMIT_CONFIRM/ADMIT_APPROVE/ADMIT_REJECT/LEAVE/REMOVE/ROLE_GRANT/ROLE_REVOKE/POLICY_OVERRIDE/POLICY_OVERRIDE_REJECTED/ACCESS_DENIED 等（值域随下游包扩展，扩展须登记本注释；不删不改既有码）——UPDATE=空间配置变更（WBS-3.2.3 登记 2026-09-26）';

-- ② from_value / to_value VARCHAR(64) → VARCHAR(1024)：
--    配置变更留痕须容纳简介全文（业务上限 512 字符），64 载宽不够"从何值→到何值"完整落痕；
--    放宽对齐同表内值载体最宽列 entry_value VARCHAR(1024)（3.2.5 策略覆盖留痕同受益）；
--    只放宽不收窄，不触碰既有行。状态机留痕值（CREATED/ACTIVE/FROZEN/DISSOLVED）不受影响。
ALTER TABLE space_action_log
    MODIFY COLUMN from_value VARCHAR(1024) NULL
    COMMENT '变更前状态/值（状态机动作=前状态；配置变更=变更前值，WBS-3.2.3 放宽至 1024 容纳简介全文；纯拒绝/创建动作可 NULL）';
ALTER TABLE space_action_log
    MODIFY COLUMN to_value VARCHAR(1024) NULL
    COMMENT '变更后状态/值（状态机动作=后状态；配置变更=变更后值）';
