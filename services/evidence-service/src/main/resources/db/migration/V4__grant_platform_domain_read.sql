-- WBS-3.9.3 Q4-A / 边界 8（规格 :213）：只读权限点 platform.domain.read 授 platform.sysadmin
-- （部署域信息读面 GET /api/v1/platform/domain 唯一持有角色）。V3 已应用不可改（checksum 红线），
-- 授点走本 V4 增量；role_permission_change_log 同事务留痕（B1-R4；before/after 取值沿
-- JdbcRbacStore 口径：点缺失 = '0'，启用 = '1'；变更单编号 = 本任务卡登记）。

INSERT INTO role_permission (role_code, permission_point, enabled, version_no) VALUES
 ('platform.sysadmin','platform.domain.read',1,1);

INSERT INTO role_permission_change_log
 (change_no, change_order_no, changed_by, changed_at, role_code, permission_point,
  before_value, after_value) VALUES
 ('RCW393DOMAINGRANT01', 'CHG-WBS-3.9.3-DOMAIN-READ', 'wbs-3.9.3', NOW(3),
  'platform.sysadmin', 'platform.domain.read', '0', '1');
