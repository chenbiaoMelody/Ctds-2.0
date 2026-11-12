-- WBS-3.9.2 Q1-A / hifi §3：角色→权限点映射表与映射变更留痕表（最小集）。
-- 本表为运行期角色→权限点映射的唯一权威来源（ctds.auth.rbac.source=jdbc 时生效）；
-- 初始 seed 由 11 服务 application.yml 既有登记（15 角色 / 59 权限点）汇总，
-- 并按 §9 E3 裁定 A 一次性撤除 admin 的审计读面与授权/核验写面，补齐三员与 connector.health.read。
-- 部署期义务（§9 E1）：各服务账号需被授予本 schema 的 SELECT，仓库内不提供 GRANT 脚本。

CREATE TABLE IF NOT EXISTS role_permission (
    id BIGINT NOT NULL AUTO_INCREMENT,
    role_code VARCHAR(64) NOT NULL,
    permission_point VARCHAR(64) NOT NULL,
    enabled TINYINT NOT NULL DEFAULT 1,
    version_no INT NOT NULL DEFAULT 1,
    PRIMARY KEY (id),
    UNIQUE KEY uk_role_permission (role_code, permission_point)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS role_permission_change_log (
    id BIGINT NOT NULL AUTO_INCREMENT,
    change_no VARCHAR(32) NOT NULL,
    change_order_no VARCHAR(64) NOT NULL,
    changed_by VARCHAR(64) NOT NULL,
    changed_at DATETIME(3) NOT NULL,
    role_code VARCHAR(64) NOT NULL,
    permission_point VARCHAR(64) NOT NULL,
    before_value VARCHAR(16) NOT NULL,
    after_value VARCHAR(16) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_change_no (change_no),
    UNIQUE KEY uk_change_order (role_code, permission_point, change_order_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 既有业务角色 / 演示档 / 服务身份（逐点沿用既有登记，零改名、零删除）
INSERT INTO role_permission (role_code, permission_point, enabled, version_no) VALUES
 ('applicant','subject.register',1,1),
 ('applicant','subject.read',1,1),
 ('applicant','subject.cancel',1,1),
 ('applicant','subject.certify',1,1),
 ('applicant','connector.register',1,1),
 ('applicant','connector.read',1,1),
 ('applicant','connector.heartbeat',1,1),
 ('applicant','connector.upgrade',1,1),
 ('applicant','connector.cancel',1,1),
 ('applicant','connector.source.manage',1,1),
 ('reviewer','subject.read',1,1),
 ('reviewer','subject.review',1,1),
 ('provider','dataset.register',1,1),
 ('provider','dataset.update',1,1),
 ('provider','dataset.cancel',1,1),
 ('provider','dataset.read',1,1),
 ('provider','vocabulary.read',1,1),
 ('provider','catalog.read',1,1),
 ('provider','catalog.interact',1,1),
 ('provider','catalog.product',1,1),
 ('provider','contract.template.read',1,1),
 ('provider','contract.read',1,1),
 ('provider','contract.deal',1,1),
 ('provider','delivery.read',1,1),
 ('provider','sandbox.read',1,1),
 ('provider','metering.read',1,1),
 ('provider','billing.read',1,1),
 ('requester','delivery.request',1,1),
 ('requester','delivery.read',1,1),
 ('requester','delivery.package.download',1,1),
 ('requester','sandbox.request',1,1),
 ('requester','sandbox.read',1,1),
 ('requester','sandbox.use',1,1),
 ('requester','metering.read',1,1),
 ('requester','billing.read',1,1),
 ('user','greeting.read',1,1),
 ('user','demo.read',1,1),
 ('platform.operator','space.admin',1,1),
 ('platform.operator','space.member',1,1),
 ('platform.operator','platform.policy',1,1),
 ('catalog-internal','space.internal.read',1,1),
 ('catalog-internal','subject.internal.read',1,1),
 ('contract-internal','catalog.internal.read',1,1),
 ('contract-internal','subject.internal.read',1,1),
 ('delivery-internal','catalog.internal.read',1,1),
 ('delivery-internal','contract.internal.read',1,1),
 ('delivery-internal','subject.internal.read',1,1),
 ('evidence-internal','contract.internal.read',1,1),
 ('evidence-internal','delivery.internal.read',1,1),
 ('evidence-internal','space.internal.read',1,1),
 ('evidence-internal','evidence.internal.write',1,1),
 ('gateway-internal','contract.internal.read',1,1),
 ('space-internal','subject.internal.read',1,1),
 ('space-internal','did.internal.read',1,1),
 ('did-internal','subject.internal.read',1,1),
 ('connector-internal','connector.internal.read',1,1),
 ('connector-internal','connector.internal.write',1,1),
 ('connector-internal','subject.internal.read',1,1),
 ('connector-internal','did.internal.read',1,1),
 ('contract-internal','did.demo.signature',1,1);

-- 三员（B2-R4 / 边界 9）：业务配置 ∥ 权限授予 ∥ 审计读面三类关键点两两不重叠；
-- kms.admin 归系统管理员（执行权），不与权限授予同持。
INSERT INTO role_permission (role_code, permission_point, enabled, version_no) VALUES
 ('platform.sysadmin','connector.health.read',1,1),
 ('platform.sysadmin','kms.admin',1,1),
 ('platform.sysadmin','did.admin',1,1),
 ('platform.sysadmin','contract.template.manage',1,1),
 ('platform.sysadmin','catalog.governance',1,1),
 ('platform.sysadmin','delivery.governance',1,1),
 ('platform.sysadmin','metering.governance',1,1),
 ('platform.sysadmin','billing.governance',1,1),
 ('platform.sysadmin','sandbox.review',1,1),
 ('platform.secadmin','connector.review',1,1),
 ('platform.secadmin','connector.upgrade.confirm',1,1),
 ('platform.secadmin','connector.cancel',1,1),
 ('platform.audadmin','evidence.governance',1,1);

-- 治理便捷档 admin：按 §9 E3 裁定 A 撤除审计读面 / 授权核验写面及 kms.admin，
-- 其余既有 governance 读面与治理审批全部保留（不逐点撤除）。
INSERT INTO role_permission (role_code, permission_point, enabled, version_no) VALUES
 ('admin','dataset.read',1,1),
 ('admin','vocabulary.read',1,1),
 ('admin','catalog.read',1,1),
 ('admin','catalog.governance',1,1),
 ('admin','connector.read',1,1),
 ('admin','connector.internal.read',1,1),
 ('admin','connector.health.read',1,1),
 ('admin','contract.template.read',1,1),
 ('admin','contract.template.manage',1,1),
 ('admin','contract.read',1,1),
 ('admin','contract.deal',1,1),
 ('admin','contract.governance',1,1),
 ('admin','delivery.read',1,1),
 ('admin','delivery.governance',1,1),
 ('admin','sandbox.governance',1,1),
 ('admin','sandbox.review',1,1),
 ('admin','did.admin',1,1),
 ('admin','did.demo.signature',1,1),
 ('admin','greeting.read',1,1),
 ('admin','greeting.delete',1,1),
 ('admin','demo.read',1,1),
 ('admin','metering.read',1,1),
 ('admin','metering.governance',1,1),
 ('admin','billing.read',1,1),
 ('admin','billing.governance',1,1);
