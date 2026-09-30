-- 目录与资源库（WBS-3.3.2，hifi §3 定稿；规格 docs/specs/C-3.1-2.3-数据目录与资源.md V1.0）。
-- 四表承载规格行为 1/2（+行为 7 资源侧）：dataset（行为 1 主表+数据标识+申报载体）、
-- dataset_name_lock（行为 2 规则 3 注销名同空间锁定）、dataset_action_log（行为 1 规则 7/行为 2 规则 5
-- 统一留痕含 DENIED 拒绝留痕）、dataset_no_seq（行为 1 规则 5 数据标识当日序号原子取号）。
-- 跨库引用（space_id → ctds_space.space、owner_subject_no → ctds_subject.subject）一律逻辑引用
-- 不建外键，一致性由应用层经内部端点校验（沿 space-service 先例，ADR-016 §6 口径）。
-- 排序规则：各表未显式声明 COLLATE，落 MySQL 8 默认 utf8mb4_0900_ai_ci——大小写/重音不敏感
-- （判重为"过阻断"方向非绕过）；0900 系 NO PAD，尾随空格参与比较，防重依赖应用层写入已归一化值。
-- 数据分级落级（WBS-3.3.2 hifi §7，分级规范 §6.1 同步行）：dataset 主表+申报字段 = CAT-03 L2；
-- dataset_action_log 留痕 = CAT-06 L3。

-- 资源主表：一行 = 一个数据资源（登记与元数据载体，不承载本体数据）。
-- 归一化名称为同空间唯一性判定口径（DB 不做归一化，只存应用层写入的归一化结果）；
-- 活跃与注销行共同参与 uk_space_norm_name（注销后同空间同名由 name_lock 继续锁定，双保险沿 3.2.2 口径）。
CREATE TABLE dataset (
    id                BIGINT       NOT NULL AUTO_INCREMENT COMMENT '技术主键（对外即 REST /datasets/{id} 的 {id}，ADR-005 先例）',
    data_no           VARCHAR(32)  NOT NULL COMMENT '数据标识：DS+yyyyMMdd+6位当日序号（平台生成、全平台唯一、不可变——行为 1 规则 5；Q3-A）',
    space_id          BIGINT       NOT NULL COMMENT '归属空间 id（逻辑引用 ctds_space.space.id，不建外键——空间状态与成员口径唯一，经 space 内部端点判定）',
    owner_subject_no  VARCHAR(24)  NOT NULL COMMENT '登记主体编号（逻辑引用 ctds_subject.subject.subject_no；资源管理动作仅限本人——行为 2 规则 5）',
    name              VARCHAR(128) NOT NULL COMMENT '资源名称（原始输入，展示用）',
    normalized_name   VARCHAR(128) NOT NULL COMMENT '归一化名称（去控制字符+空白折叠+首尾 trim，同空间唯一性判定口径）',
    type              VARCHAR(16)  NOT NULL COMMENT '资源类型：DATASET数据集/API接口/REPORT报告/MODEL模型（四类受控枚举，规格 §7 Q4）',
    intro             VARCHAR(512) NOT NULL COMMENT '简介',
    semantic_tags     VARCHAR(512) NOT NULL COMMENT '语义标签（JSON 数组字符串载体级——词表成员校验归 3.3.3，受控词表未落定前只做载体校验）',
    declare_category  VARCHAR(64)  NOT NULL COMMENT '分类申报（字符串载体——类目树校验归 3.3.4，本列是提供方申报的原始载体）',
    declare_level     VARCHAR(8)   NOT NULL COMMENT '分级申报：L1/L2/L3/L4（提供方申报、平台复核，CAT-07；变更只能收紧就高——行为 2 规则 1）',
    declare_important TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '重要数据申报（true 一律拒收登记——分级规范 §4.5-3 硬约束，代码强制；成功行恒 0）',
    status            VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE' COMMENT '状态机：ACTIVE生效/DELETED已注销(终态不可逆，行为 2 规则 2/4)',
    created_at        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_data_no (data_no),
    UNIQUE KEY uk_space_norm_name (space_id, normalized_name),
    KEY idx_owner (owner_subject_no, status),
    KEY idx_space_status (space_id, status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '资源主表（一行=一个数据资源；数据标识全平台唯一；同空间归一化名唯一（活跃与注销行共同参与））';

-- 注销名称锁定表：注销资源名称同空间不可复用（行为 2 规则 3；沿 space_name_lock 先例收缩为同空间口径）。
-- 注销事务内写入，PK 冲突即名称已被本空间历史资源锁定——存储引擎原子层兜底，无并发穿透窗口。
CREATE TABLE dataset_name_lock (
    space_id        BIGINT       NOT NULL COMMENT '空间 id（锁定键之一——同空间口径）',
    normalized_name VARCHAR(128) NOT NULL COMMENT '归一化名称（锁定键之一）',
    dataset_id      BIGINT       NOT NULL COMMENT '来源资源 id（dataset.id，溯源）',
    locked_at       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '锁定时间（注销时点）',
    PRIMARY KEY (space_id, normalized_name)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '注销资源名称同空间锁定表（行为2规则3；复合 PK 硬约束兜底）';

-- 统一操作留痕表：四要素（谁/何时/对象/动作）+ 结果 + 变更前后值 + 拒绝理由码。
-- 登记/变更/注销共用一张（动作码区分）；只插不改（无 updated_at）；拒绝动作同样留痕
-- （行为 1 规则 4 拒收留痕/行为 2 规则 5 越权留痕/行为 7 规则 4 越权探测留痕）；不含敏感原文与数据本体。
CREATE TABLE dataset_action_log (
    id               BIGINT        NOT NULL AUTO_INCREMENT COMMENT '技术主键',
    actor_subject_no VARCHAR(24)   NOT NULL COMMENT '操作者主体编号（DENIED 时 = 越权/被拒者——四要素"谁"）',
    space_id         BIGINT        NOT NULL COMMENT '归属空间 id（登记未成行时为请求目标空间）',
    dataset_id       BIGINT        NULL COMMENT '资源 id（dataset.id；登记拒绝/未成行时为 NULL，说明并入 reason）',
    action           VARCHAR(32)   NOT NULL COMMENT '动作码：REGISTER/UPDATE/CANCEL + DENIED_ 前缀变体（值域随下游包扩展须登记本注释；不删不改既有码）',
    from_value       VARCHAR(1024) NULL COMMENT '变更前值（UPDATE 时 from→to 摘要；纯拒绝/创建动作可 NULL——沿 space V2 放宽先例）',
    to_value         VARCHAR(1024) NULL COMMENT '变更后值（UPDATE 时新值摘要）',
    result           VARCHAR(16)   NOT NULL COMMENT '结果：SUCCESS/DENIED（拒绝同样留痕）',
    reason_code      VARCHAR(16)   NULL COMMENT '拒绝理由码（DENIED 时落错误码尾号，如 C0003——服务端常量，不含用户输入）',
    created_at       DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '发生时间——四要素"何时"',
    PRIMARY KEY (id),
    KEY idx_dataset (dataset_id, created_at),
    KEY idx_space (space_id, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '资源域统一操作留痕（四要素：谁/何时/对象/动作+结果与理由码；拒绝动作同样留痕；不含敏感原文；只插不改）';

-- 数据标识序号表：DS+yyyyMMdd+6 位当日序号的原子取号载体（Q3-A，沿 subject subject_daily_seq 先例）。
-- 取号与登记同事务：INSERT ... ON DUPLICATE KEY UPDATE 原子自增，当日从 1 起、跨日重置。
CREATE TABLE dataset_no_seq (
    seq_date  DATE        NOT NULL COMMENT '取号日期（当日重置口径）',
    seq_key   VARCHAR(8)  NOT NULL COMMENT '序号键（恒 DATASET，预留 3.3.3~3.3.5 扩展位）',
    seq_value INT         NOT NULL COMMENT '当日已取号数（原子自增；上限 999999 后按超限拒绝）',
    PRIMARY KEY (seq_date, seq_key)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '数据标识当日序号取号表（原子自增、当日重置；Q3-A）';
