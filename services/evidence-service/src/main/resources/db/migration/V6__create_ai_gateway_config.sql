-- WBS-3.10.4 Q2-A/Q3-A：AI 网关配置面（模型/开关/预算配置管理页的数据承载）。
-- 红线：模型密钥值禁止入库（ADR-003）——api_key_ref 仅引用占位（seed 空值），页面与读 API 均不返回；
-- 诚实边界：V1.0 无运行时消费方，本配置"落库 ≠ 已生效"，生效对象 = 未来 V1.5/C-11.x 绑定（C-11.1 规格 :101）。
-- V5 及更早已应用脚本禁改（checksum 红线），本 V6 为增量：三表 + seed + 权限两点。

CREATE TABLE ai_gateway_config (
    id               INT          NOT NULL COMMENT '恒 1（单例行）',
    enabled          TINYINT      NOT NULL DEFAULT 0 COMMENT '网关总开关：1=启用 0=关闭（缺省关闭=V1.0 零消耗安全默认，C-11.1 B3）',
    default_provider VARCHAR(32)  NOT NULL DEFAULT 'glm' COMMENT '默认供应商编号（封闭清单内）',
    token_budget     BIGINT       NOT NULL DEFAULT 0 COMMENT 'token 预算上限；0=未设限（预留位，C-11.1 B3）',
    updated_by       VARCHAR(64)  NULL COMMENT '最近修改人（X-Ctds-Subject）',
    updated_at       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最近修改时间',
    PRIMARY KEY (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = 'AI 网关总控配置（单例行；V1.0 配置即预留，无运行时消费方）';

CREATE TABLE ai_gateway_provider_config (
    provider_id  VARCHAR(32)  NOT NULL COMMENT '供应商编号（封闭清单：glm/deepseek/qwen/kimi，ADR-003 §2.1）',
    base_url     VARCHAR(512) NULL COMMENT 'OpenAI 兼容服务地址（http(s)；NULL=未配置；仓库零真实部署值）',
    api_key_ref  VARCHAR(256) NULL COMMENT '密钥引用占位列：真实密钥值禁止入库（红线），部署期经配置中心/KMS 注入；页面与读 API 均不返回本列',
    model        VARCHAR(128) NULL COMMENT '供应商默认模型名（NULL=未配置）',
    updated_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最近修改时间',
    PRIMARY KEY (provider_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = 'AI 供应商连接参数（封闭清单四行；密钥值永不入库）';

CREATE TABLE ai_gateway_config_change_log (
    id           BIGINT        NOT NULL AUTO_INCREMENT COMMENT '技术主键',
    changed_at   DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '变更时间',
    changed_by   VARCHAR(64)   NOT NULL COMMENT '操作者（X-Ctds-Subject）',
    field_path   VARCHAR(64)   NOT NULL COMMENT '字段路径：enabled/defaultProvider/tokenBudget/providers.<id>.baseUrl/providers.<id>.model',
    before_value VARCHAR(1024) NULL COMMENT '变更前值（NULL=原值为空）',
    after_value  VARCHAR(1024) NULL COMMENT '变更后值（NULL=新值为空）',
    PRIMARY KEY (id),
    KEY idx_changed_at (changed_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = 'AI 配置变更留痕（只读；逐字段从何值到何值；不提供界面回滚）';

-- seed：总控单例行（C-11.1 缺省态）+ 封闭清单四行（未配置态）
INSERT INTO ai_gateway_config (id, enabled, default_provider, token_budget) VALUES (1, 0, 'glm', 0);
INSERT INTO ai_gateway_provider_config (provider_id, base_url, api_key_ref, model) VALUES
 ('glm', NULL, NULL, NULL), ('deepseek', NULL, NULL, NULL),
 ('qwen', NULL, NULL, NULL), ('kimi', NULL, NULL, NULL);

-- 权限点增量（沿 V5 先例：授 platform.sysadmin；secadmin/audadmin 不持新点，负向 403 天然可演；
-- role_permission_change_log 同事务留痕；变更单编号 = 本任务卡登记）
INSERT INTO role_permission (role_code, permission_point, enabled, version_no) VALUES
 ('platform.sysadmin','platform.ai.config.read',1,1);

INSERT INTO role_permission_change_log
 (change_no, change_order_no, changed_by, changed_at, role_code, permission_point,
  before_value, after_value) VALUES
 ('RCW3104AICONF01', 'CHG-WBS-3.10.4-AI-CONFIG-READ', 'wbs-3.10.4', NOW(3),
  'platform.sysadmin', 'platform.ai.config.read', '0', '1');

INSERT INTO role_permission (role_code, permission_point, enabled, version_no) VALUES
 ('platform.sysadmin','platform.ai.config.write',1,1);

INSERT INTO role_permission_change_log
 (change_no, change_order_no, changed_by, changed_at, role_code, permission_point,
  before_value, after_value) VALUES
 ('RCW3104AICONF02', 'CHG-WBS-3.10.4-AI-CONFIG-WRITE', 'wbs-3.10.4', NOW(3),
  'platform.sysadmin', 'platform.ai.config.write', '0', '1');
