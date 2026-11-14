-- WBS-3.9.4 Q3-A / 边界 8（规格 :213）：监控读数读面（platform.monitor.read）+ 运维处置写面
-- （platform.alert.handle）两个新权限点授 platform.sysadmin（三员分立纯口径：secadmin/audadmin/
-- 治理档 admin 不持新点 → B8-3 双向天然成立）。V3/V4 已应用不可改（checksum 红线），授点走
-- 本 V5 增量；role_permission_change_log 同事务留痕（B1-R4；before/after 取值沿
-- JdbcRbacStore 口径：点缺失 = '0'，启用 = '1'；变更单编号 = 本任务卡登记）。

INSERT INTO role_permission (role_code, permission_point, enabled, version_no) VALUES
 ('platform.sysadmin','platform.monitor.read',1,1);

INSERT INTO role_permission_change_log
 (change_no, change_order_no, changed_by, changed_at, role_code, permission_point,
  before_value, after_value) VALUES
 ('RCW394MONITOR01', 'CHG-WBS-3.9.4-MONITOR-READ', 'wbs-3.9.4', NOW(3),
  'platform.sysadmin', 'platform.monitor.read', '0', '1');

INSERT INTO role_permission (role_code, permission_point, enabled, version_no) VALUES
 ('platform.sysadmin','platform.alert.handle',1,1);

INSERT INTO role_permission_change_log
 (change_no, change_order_no, changed_by, changed_at, role_code, permission_point,
  before_value, after_value) VALUES
 ('RCW394ALERT01', 'CHG-WBS-3.9.4-ALERT-HANDLE', 'wbs-3.9.4', NOW(3),
  'platform.sysadmin', 'platform.alert.handle', '0', '1');
