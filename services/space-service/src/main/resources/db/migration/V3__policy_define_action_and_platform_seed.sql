-- 策略继承引擎迁移（WBS-3.2.5：动作码登记 + 平台级基线种子；hifi §4/§7 Q6-A）。
-- 零破坏原则：不改既有动作码、不收窄任何列、无新表无列变更；V1/V2 逐字冻结（checksum 不变）。

-- ① 动作码值域登记 UPDATE（平台级策略条目创建/变更，3.2.5 端点 1/2）：
--    V1 列注释口径"值域随下游包扩展，扩展须登记本注释"——本语句即登记动作本身（仅改注释，不改值域约束）。
--    POLICY_OVERRIDE / POLICY_OVERRIDE_REJECTED 为 V1 既有预留码（空间覆盖成功/拒绝），本包直接复用零登记。
ALTER TABLE space_action_log
    MODIFY COLUMN action VARCHAR(32) NOT NULL
    COMMENT '动作码：CREATE/ENABLE/FREEZE/UNFREEZE/DISSOLVE/UPDATE/ADMIT_REQUEST/ADMIT_INVITE/ADMIT_CONFIRM/ADMIT_APPROVE/ADMIT_REJECT/LEAVE/REMOVE/ROLE_GRANT/ROLE_REVOKE/POLICY_OVERRIDE/POLICY_OVERRIDE_REJECTED/ACCESS_DENIED 等（值域随下游包扩展，扩展须登记本注释；不删不改既有码）——UPDATE=空间配置变更（WBS-3.2.3 登记 2026-09-26）；POLICY_DEFINE=平台级策略条目创建/变更（WBS-3.2.5 登记 2026-09-27）';

-- ② 平台级基线种子 3 条（目录三键，PolicyCatalog 声明序；两红线一非红线——C-2.3 剧本 S2 三类演示载体：
--    S2-2 拒宽 / S2-3 收紧 / S2-4 非红线覆盖）。种子 = 平台基线配置（默认来源），非演示残留，
--    清理口径登记"保留"（任务卡 §四）；平台条目日常变更走端点 1/2 留痕（规则 4）。
INSERT INTO space_policy (scope, space_id, platform_entry_id, entry_key, entry_value, is_redline, status) VALUES
    ('PLATFORM', NULL, NULL, 'data.visibility', 'SPACE_MEMBER', 1, 'ACTIVE'),
    ('PLATFORM', NULL, NULL, 'data.retention', 'D90', 1, 'ACTIVE'),
    ('PLATFORM', NULL, NULL, 'member.data_export', 'ALLOWED', 0, 'ACTIVE');
