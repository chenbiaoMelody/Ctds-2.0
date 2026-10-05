-- 合约库（WBS-3.4.2，hifi §4/§5 定稿；规格 docs/specs/C-4.1-4.3-数字合约与使用控制.md V1.0 行为 1）。
-- 四表承载规格行为 1：contract_template（模板主表：维护权唯一/类型/当前版本指针/状态机）、
-- contract_template_version（模板版本表：行级版本化、版本行不可变 = 版本快照载体——行为 1 规则 3）、
-- contract_template_action_log（模板留痕表：四要素含版本号与 from→to + DENIED 拒绝留痕——规则 7）、
-- contract_template_no_seq（模板编号序号表：全局 6 位序号原子取号，低频运营对象不按日重置）。
-- 预置三类模板（公共数据授权 / API 调用 / 隐私计算）随本迁移种子落库（行为 1 规则 2"随 3.4.2 交付落库"；
-- C-4.1 剧本演示前提④），条款框架 = hifi §5 / lofi §3 确认的槽位集合实例化（公共骨架 7 必填 + 类型差异化）。
-- 跨库引用（actor_subject_no → ctds_subject.subject）一律逻辑引用不建外键（沿 catalog-service 先例，
-- ADR-016 §6 口径）；published_by/created_by 为同库逻辑引用（运营方操作者，演示期身份头口径）。
-- 排序规则：各表未显式声明 COLLATE，落 MySQL 8 默认 utf8mb4_0900_ai_ci——大小写/重音不敏感
-- （模板同类型同名判重为"过阻断"方向非绕过）；0900 系 NO PAD，尾随空格参与比较，
-- 归一化口径 = DB 生成列 LOWER(TRIM(name)) 单点（应用层不重复归一化，防口径分裂——DB-31 同族教训）。
-- 数据分级落级（WBS-3.4.2 hifi §8，分级规范 §6.1 同步行）：模板三表（条款框架 = 业务配置文案，
-- 不含个人信息与业务数据本体）= L1；contract_template_action_log 留痕（含操作者主体编号）= CAT-06 L3。

-- 模板主表：一行 = 一个合约模板（预置三类 + 运营方新增，行为 1 规则 1/2；维护权唯一 = 平台运营方）。
-- 同类型归一化名唯一（uk_type_norm_name 生成列承载）——防运营方误建重复模板；版本指针指向当前版本号。
CREATE TABLE contract_template (
    id                BIGINT       NOT NULL AUTO_INCREMENT COMMENT '技术主键（对外即 REST /contract-templates/{templateNo} 逻辑键，ADR-005 先例）',
    template_no       VARCHAR(32)  NOT NULL COMMENT '模板编号：CT+6位全局序号（平台生成、全平台唯一、不可变；序号表原子取号）',
    template_name     VARCHAR(128) NOT NULL COMMENT '模板名称（原始输入，展示用）',
    template_name_norm VARCHAR(128) GENERATED ALWAYS AS (LOWER(TRIM(template_name))) STORED COMMENT '归一化名称（DB 生成列单点：LOWER+TRIM，同类型唯一性判定口径）',
    template_type     VARCHAR(32)  NOT NULL COMMENT '模板类型：PUBLIC_DATA_AUTHORIZATION公共数据授权/API_CALL API调用/PRIVACY_COMPUTING隐私计算（三类受控枚举，PRD C-4.1）',
    current_version   INT          NOT NULL DEFAULT 1 COMMENT '当前版本号（指向 contract_template_version.version_no，修订出上新版本时前移）',
    status            VARCHAR(16)  NOT NULL DEFAULT 'ENABLED' COMMENT '状态机：ENABLED启用中/DISABLED停用（停用=退出浏览+不可新发起，不影响既有——行为 1 规则 4；Q6-A 最严口径）',
    created_by        VARCHAR(24)  NOT NULL COMMENT '创建操作者主体编号（平台运营方，逻辑引用 ctds_subject.subject.subject_no）',
    created_at        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_template_no (template_no),
    UNIQUE KEY uk_type_norm_name (template_type, template_name_norm),
    KEY idx_type_status (template_type, status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '合约模板主表（一行=一个合约模板；维护权唯一=平台运营方；预置三类+运营方新增）';

-- 模板版本表：一行 = 模板的一个已发布版本（行级版本化——修订永远新增行，版本行不可变；
-- "旧版本保留可查"（行为 1 规则 3）由行保留天然承载；版本行即版本快照载体，
-- 合约发起时锁定快照的写入动作归 3.4.3（本卡交付 QV1/QV2 应用层校验与读取）。
-- 无 updated_at：不可变载体不设更新时间列，仓储接口亦无更新路径（编译期保证）。
CREATE TABLE contract_template_version (
    id                BIGINT       NOT NULL AUTO_INCREMENT COMMENT '技术主键',
    template_id       BIGINT       NOT NULL COMMENT '模板 id（contract_template.id，同库逻辑引用不建外键）',
    version_no        INT          NOT NULL COMMENT '版本号（模板内递增，从 1 起；预置模板 V1 起步）',
    clause_framework  JSON         NOT NULL COMMENT '条款框架：槽位集合实例化 {"slots":[{key,name,required,guide}]}——槽位集合 = 公共骨架 7 必填 + 类型差异化（lofi §3 确认清单；发起合约时按槽位填写具体约定值）',
    published_by      VARCHAR(24)  NOT NULL COMMENT '发布操作者主体编号（平台运营方；预置种子 = platform-seed）',
    published_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '发布时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_template_version (template_id, version_no)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '合约模板版本表（行级版本化、版本行不可变=版本快照载体；修订=新增行）';

-- 模板统一留痕表：四要素（谁/何时/哪个模板+版本/动作）+ 拒绝理由码 + from→to 值。
-- 新增/修订/启停/拒绝共用一张（动作码区分）；只插不改（无 updated_at）；拒绝动作同样留痕
-- （行为 1 规则 1"一律拒绝并留痕"——维护权判定在应用服务承载，DENIED_MANAGE 留痕含未入驻维护）；
-- 不含敏感原文与条款框架全文（框架全文在版本表按版本号可查）。
CREATE TABLE contract_template_action_log (
    id               BIGINT        NOT NULL AUTO_INCREMENT COMMENT '技术主键',
    template_no      VARCHAR(32)   NULL COMMENT '模板编号（动作对象；模板定位前被拒〔如新增场景维护权拒绝〕时为 NULL——hifi V1.1 §4）',
    version_no       INT           NULL COMMENT '动作涉及版本号（CREATE/REVISE 必有；启停记动作时点当前版本；DENIED 同态拒绝记当时版本，模板定位前拒绝为 NULL——hifi V1.2 §4）',
    action           VARCHAR(32)   NOT NULL COMMENT '动作码：CREATE新增/REVISE修订/ENABLE启用/DISABLE停用/DENIED_MANAGE维护被拒（值域封闭枚举）',
    actor_subject_no VARCHAR(24)   NOT NULL COMMENT '操作者主体编号（DENIED 时 = 被拒者——四要素"谁"）',
    reason_code      VARCHAR(16)   NULL COMMENT '拒绝理由码（1008 码位尾号，如 C0002——直接存码位尾号不设第二套枚举，DB-31 口径）',
    from_value       VARCHAR(64)   NULL COMMENT '变更前值（REVISE: 旧版本号；ENABLE/DISABLE: 旧状态）',
    to_value         VARCHAR(64)   NULL COMMENT '变更后值（REVISE: 新版本号；ENABLE/DISABLE: 新状态）',
    created_at       DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '留痕时间',
    PRIMARY KEY (id),
    KEY idx_log_template (template_no, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '合约模板统一留痕表（四要素+理由码+from→to；只插不改；拒绝同样留痕）';

-- 模板编号序号表：全局 1 行原子自增（模板为低频运营对象，不按日重置；预置种子占 CT000001~CT000003，
-- 初始 next_no = 4）。取号 = UPDATE ... SET next_no = LAST_INSERT_ID(next_no + 1) 后读 LAST_INSERT_ID()
-- （沿 subject nextDailySeq / catalog dataset_no_seq 先例）。
CREATE TABLE contract_template_no_seq (
    id       TINYINT NOT NULL COMMENT '固定 1 行（id=1）',
    next_no  INT     NOT NULL COMMENT '下一个全局序号',
    PRIMARY KEY (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '模板编号序号表（全局 1 行原子自增；CT+6位序号）';

-- ==== 预置三类模板种子（行为 1 规则 2；条款框架 = lofi §3 确认槽位集合）====
-- 模板命名沿规格 C-4.1 §6-6 术语唯一裁决："合约模板"（不引入"合同"第二套术语——评审③修复项）。

INSERT INTO contract_template (template_no, template_name, template_type, current_version, status, created_by)
VALUES ('CT000001', '公共数据授权合约模板', 'PUBLIC_DATA_AUTHORIZATION', 1, 'ENABLED', 'platform-seed'),
       ('CT000002', 'API调用服务合约模板', 'API_CALL', 1, 'ENABLED', 'platform-seed'),
       ('CT000003', '隐私计算服务合约模板', 'PRIVACY_COMPUTING', 1, 'ENABLED', 'platform-seed');

INSERT INTO contract_template_version (template_id, version_no, clause_framework, published_by) VALUES
(1, 1, '{"slots":[{"key":"subject_matter","name":"授权标的","required":true,"guide":"授权的数据/服务是什么（对应目录产品，含标识与名称）"},{"key":"scope","name":"授权范围","required":true,"guide":"允许的使用方式（如：内部数据分析/对外服务集成/二次加工边界）"},{"key":"term","name":"授权期限","required":true,"guide":"起止时间或条件"},{"key":"purpose_and_restrictions","name":"使用目的与限制","required":true,"guide":"允许的使用目的清单+明确禁止项（含禁止再分发默认表述）"},{"key":"security_confidentiality","name":"安全与保密义务","required":true,"guide":"数据保护要求、泄露责任"},{"key":"liability","name":"违约责任","required":true,"guide":"违约情形与责任承担方式"},{"key":"dispute_resolution","name":"争议解决","required":true,"guide":"争议处理途径与适用规则"},{"key":"data_format_delivery","name":"数据格式与交付方式","required":true,"guide":"交付格式（如库表/文件/接口拉取）与交付渠道"},{"key":"data_update_obligation","name":"数据更新与维护义务","required":true,"guide":"更新频率、通知义务、中断处理"},{"key":"data_quality_commitment","name":"数据质量承诺","required":false,"guide":"准确性/完整性承诺口径"}]}', 'platform-seed'),
(2, 1, '{"slots":[{"key":"subject_matter","name":"授权标的","required":true,"guide":"授权的数据/服务是什么（对应目录产品，含标识与名称）"},{"key":"scope","name":"授权范围","required":true,"guide":"允许的使用方式（如：内部数据分析/对外服务集成/二次加工边界）"},{"key":"term","name":"授权期限","required":true,"guide":"起止时间或条件"},{"key":"purpose_and_restrictions","name":"使用目的与限制","required":true,"guide":"允许的使用目的清单+明确禁止项（含禁止再分发默认表述）"},{"key":"security_confidentiality","name":"安全与保密义务","required":true,"guide":"数据保护要求、泄露责任"},{"key":"liability","name":"违约责任","required":true,"guide":"违约情形与责任承担方式"},{"key":"dispute_resolution","name":"争议解决","required":true,"guide":"争议处理途径与适用规则"},{"key":"api_scope_and_invocation","name":"接口范围与调用方式","required":true,"guide":"可调用接口清单、鉴权方式、协议版本"},{"key":"rate_limit","name":"调用频次上限","required":true,"guide":"总量/峰值限制（为策略引擎配额计数与计费提供约定载体）"},{"key":"availability_commitment","name":"服务可用性承诺","required":false,"guide":"可用率口径与补偿方式"}]}', 'platform-seed'),
(3, 1, '{"slots":[{"key":"subject_matter","name":"授权标的","required":true,"guide":"授权的数据/服务是什么（对应目录产品，含标识与名称）"},{"key":"scope","name":"授权范围","required":true,"guide":"允许的使用方式（如：内部数据分析/对外服务集成/二次加工边界）"},{"key":"term","name":"授权期限","required":true,"guide":"起止时间或条件"},{"key":"purpose_and_restrictions","name":"使用目的与限制","required":true,"guide":"允许的使用目的清单+明确禁止项（含禁止再分发默认表述）"},{"key":"security_confidentiality","name":"安全与保密义务","required":true,"guide":"数据保护要求、泄露责任"},{"key":"liability","name":"违约责任","required":true,"guide":"违约情形与责任承担方式"},{"key":"dispute_resolution","name":"争议解决","required":true,"guide":"争议处理途径与适用规则"},{"key":"compute_env_security","name":"计算环境与安全要求","required":true,"guide":"可用计算环境、安全管控要求"},{"key":"result_delivery","name":"计算结果交付方式","required":true,"guide":"结果形态与交付渠道"},{"key":"raw_data_not_leaving_domain","name":"原始数据不出域承诺","required":true,"guide":"原始数据不离开提供方域的强约束表述（隐私计算核心特征）"},{"key":"audit_verification","name":"审计与核查","required":false,"guide":"审计权、核查方式与频次"}]}', 'platform-seed');

INSERT INTO contract_template_no_seq (id, next_no) VALUES (1, 4);
