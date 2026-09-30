-- 运营档治理查看留痕迁移（DB-29：动作码登记；Q1~Q5 全 A——1 个通用码 GOVERNANCE_VIEW + target_type 区分端点面）。
-- 零破坏原则：不改既有动作码、不收窄任何列、无新表无列变更；V1/V2/V3 逐字冻结（checksum 不变）。

-- 动作码值域登记 UPDATE（仅改注释，不改值域约束；沿 V2/V3 登记口径）：
--    GOVERNANCE_VIEW = 运营档治理查看（仅 platform.operator 角色成功访问治理读端点时写入；
--    operator = 实际登录主体编号（Q3-A）；空间面 target_type=SPACE（target_id=空间 id，列表面为空），
--    平台策略条目面 target_type=POLICY（沿 POLICY_DEFINE 平台留痕形态 space_id=NULL））。
ALTER TABLE space_action_log
    MODIFY COLUMN action VARCHAR(32) NOT NULL
    COMMENT '动作码：CREATE/ENABLE/FREEZE/UNFREEZE/DISSOLVE/UPDATE/ADMIT_REQUEST/ADMIT_INVITE/ADMIT_CONFIRM/ADMIT_APPROVE/ADMIT_REJECT/LEAVE/REMOVE/ROLE_GRANT/ROLE_REVOKE/POLICY_OVERRIDE/POLICY_OVERRIDE_REJECTED/ACCESS_DENIED 等（值域随下游包扩展，扩展须登记本注释；不删不改既有码）——UPDATE=空间配置变更（WBS-3.2.3 登记 2026-09-26）；POLICY_DEFINE=平台级策略条目创建/变更（WBS-3.2.5 登记 2026-09-27）；GOVERNANCE_VIEW=运营档治理查看留痕（DB-29 登记 2026-09-30）';
