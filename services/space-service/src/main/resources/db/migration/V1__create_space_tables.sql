-- 逻辑空间库（WBS-3.2.2，hifi §1 定稿；规格 docs/specs/C-2.1-2.3-逻辑空间管理.md V1.0）。
-- 六表承载规格七条行为：space（行为1/2 主表+状态机）、space_name_lock（行为2规则4 解散名全平台锁定）、
-- space_member（行为3/4/5 成员关系+角色+唯一所有者保护）、space_admission（行为3 准入载体）、
-- space_policy（行为7 策略继承覆盖载体——条目语言与可配置项集合归 3.2.5）、space_action_log（行为1~7 统一留痕含拒绝留痕）。
-- 跨库引用（subject_no → ctds_subject.subject）一律逻辑引用不建外键，一致性由应用层校验（沿 subject-service 先例）。
-- 错误码 1006 段已预留（规格 Q7），码值随 3.2.3/3.2.4 实施包落定，本迁移不含任何业务码值。

-- 空间表：一行 = 一个逻辑空间。归一化名称（去首尾空白与控制字符）为唯一性判定口径，
-- 算法复用主体服务既有归一化实现，本库只存结果。
CREATE TABLE space (
    id               BIGINT       NOT NULL AUTO_INCREMENT COMMENT '技术主键（对外即 REST /data-spaces/{id} 的 {id}，ADR-005 先例）',
    name             VARCHAR(128) NOT NULL COMMENT '空间名称（原始输入，展示用）',
    normalized_name  VARCHAR(128) NOT NULL COMMENT '归一化名称（去首尾空白与控制字符，唯一性判定口径）',
    scene_type       VARCHAR(16)  NOT NULL COMMENT '场景类型：FINTECH普惠金融/MEDICAL医疗验证/OTHER其他（lofi Q8-A 裁决）',
    access_mode      VARCHAR(16)  NOT NULL COMMENT '参与方范围：OPEN公开/INVITE邀请制/APPROVAL审批制（规格 Q2 三档）',
    visibility       VARCHAR(16)  NOT NULL COMMENT '可见性：PUBLIC公开/PRIVATE不公开（可见性≠资源可访问性，行为6规则3）',
    intro            VARCHAR(512) NULL COMMENT '空间简介（可选项，NULL=未填）',
    effective_from   DATETIME     NULL COMMENT '生效期起（可选项，NULL=不限）',
    effective_to     DATETIME     NULL COMMENT '生效期止（可选项，NULL=不限）',
    owner_subject_no VARCHAR(24)  NOT NULL COMMENT '所有者主体编号（逻辑引用 ctds_subject.subject.subject_no；ADMITTED 资格由应用层调 C-1.1 判定，本库不存副本）',
    status           VARCHAR(20)  NOT NULL COMMENT '状态机：CREATED已创建未启用/ACTIVE已启用/FROZEN已冻结/DISSOLVED已解散(终态不可逆)（规格 Q4）',
    created_at       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_owner_norm_name (owner_subject_no, normalized_name),
    KEY idx_status (status),
    KEY idx_norm_name (normalized_name)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '空间表（一行=一个逻辑空间；活跃空间名称同一所有者唯一，解散名称全平台锁定见 space_name_lock）';

-- 解散名称锁定表：解散空间名称不可复用 = 全平台口径（行为2规则4，Q4 裁决）。
-- 解散事务内写入，PK 冲突即名称已被历史空间锁定——存储引擎原子层兜底，无并发穿透窗口。
CREATE TABLE space_name_lock (
    normalized_name VARCHAR(128) NOT NULL COMMENT '归一化名称（全平台锁定键）',
    space_id        BIGINT       NOT NULL COMMENT '来源空间 id（space.id）',
    locked_at       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '锁定时间（解散时点）',
    PRIMARY KEY (normalized_name)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '解散空间名称全平台锁定表（行为2规则4；PK 硬约束兜底）';

-- 成员表：一行 = 一条成员关系。退出/移除行保留改终态供追溯，再次准入生成新行。
-- 生成列为 MySQL 8 标准能力：唯一索引中 NULL 不参与去重，故终态多行可共存、活跃行唯一。
CREATE TABLE space_member (
    id          BIGINT      NOT NULL AUTO_INCREMENT COMMENT '技术主键',
    space_id    BIGINT      NOT NULL COMMENT '空间 id（space.id）',
    subject_no  VARCHAR(24) NOT NULL COMMENT '成员主体编号（逻辑引用 ctds_subject.subject.subject_no）',
    role        VARCHAR(16) NOT NULL COMMENT '角色：OWNER所有者/ADMIN管理员/MEMBER成员（规格 Q3 三档；只读审计角色归 C-9 审计域）',
    status      VARCHAR(16) NOT NULL COMMENT '成员关系状态：ACTIVE生效中/LEFT已退出/REMOVED已移除（终态行保留供追溯）',
    joined_at   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '成为成员时点',
    exited_at   DATETIME    NULL COMMENT '退出/移除时点（NULL=仍在空间）',
    active_flag BIGINT      GENERATED ALWAYS AS (IF(status = 'ACTIVE', 1, NULL)) STORED COMMENT '活跃标志生成列（ACTIVE=1 其余 NULL，支撑同空间同主体至多一条生效关系）',
    owner_uniq  BIGINT      GENERATED ALWAYS AS (IF(role = 'OWNER' AND status = 'ACTIVE', 1, NULL)) STORED COMMENT '唯一所有者生成列（活跃 OWNER=1 其余 NULL，支撑同空间至多一个活跃所有者——行为5规则3 硬兜底）',
    created_at  DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at  DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_active_member (space_id, subject_no, active_flag),
    UNIQUE KEY uk_active_owner (space_id, owner_uniq),
    KEY idx_subject (subject_no),
    KEY idx_space_role (space_id, role)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '空间成员表（一行=一条成员关系；同一空间同一主体至多一条生效关系、至多一个活跃所有者均由唯一索引硬兜底；退出/移除行保留改终态）';

-- 准入单表：一行 = 一次准入流程（申请/邀请/审批的载体）。
-- 未确认邀请与未审批申请不产生成员关系（行为3规则2）——成员表只存生效关系，语义由两表分工保证；
-- 通过后回填 member_id 贯通追溯。状态机流转细则归 3.2.4，本表定值域与载体。
CREATE TABLE space_admission (
    id         BIGINT       NOT NULL AUTO_INCREMENT COMMENT '技术主键',
    space_id   BIGINT       NOT NULL COMMENT '空间 id（space.id）',
    subject_no VARCHAR(24)  NOT NULL COMMENT '被准入主体编号（逻辑引用 ctds_subject.subject.subject_no）',
    type       VARCHAR(16)  NOT NULL COMMENT '准入形态：APPLICATION申请/INVITATION邀请（由空间 access_mode 决定，行为3规则2）',
    status     VARCHAR(24)  NOT NULL COMMENT '准入单状态：PENDING_APPROVAL待审批/PENDING_CONFIRMATION待被邀方确认/APPROVED已通过(成员关系已建立)/REJECTED已拒绝/DECLINED被邀方谢绝/CANCELLED已撤回（流转细则归 3.2.4）',
    operator   VARCHAR(64)  NOT NULL COMMENT '发起方（申请人主体编号/邀请操作人）',
    reason     VARCHAR(256) NULL COMMENT '拒绝/谢绝理由（业务文案，行为3规则5 拒绝须记录理由）',
    member_id  BIGINT       NULL COMMENT '生效后的成员关系 id（space_member.id，status=APPROVED 时回填）',
    created_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    KEY idx_space_status (space_id, status),
    KEY idx_subject (subject_no)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '空间准入单（申请/邀请载体：未确认邀请与未审批申请不产生成员关系；通过后回填 member_id 贯通追溯）';

-- 策略条目载体表：平台级默认 + 空间级覆盖（行为7规则1）。**载体级**——条目键命名与可配置项集合
-- 归 3.2.5 策略继承引擎定稿（规格行为7规则6：禁止两处各自定义策略模型），本表只定结构与唯一性。
-- 历史版本不落本表（变更走留痕"从何值→到何值"）；被拒绝的覆盖请求只落留痕不落数据行。
CREATE TABLE space_policy (
    id                BIGINT        NOT NULL AUTO_INCREMENT COMMENT '技术主键',
    scope             VARCHAR(16)   NOT NULL COMMENT '作用域：PLATFORM平台级(默认来源)/SPACE空间级(覆盖)',
    space_id          BIGINT        NULL COMMENT '空间 id（scope=SPACE 必填；PLATFORM 为 NULL）',
    platform_entry_id BIGINT        NULL COMMENT '被覆盖的平台级条目 id（scope=SPACE 必填；平台级行为 NULL）——继承链显式指向',
    entry_key         VARCHAR(64)   NOT NULL COMMENT '策略条目键（载体级：键命名与可配置项集合归 3.2.5 定稿）',
    entry_value       VARCHAR(1024) NOT NULL COMMENT '策略条目值（当前生效值；历史变更走留痕）',
    is_redline        TINYINT       NOT NULL DEFAULT 0 COMMENT '红线标记：1=限制性条款不得放宽（仅平台级条目可标记；放宽拒绝判定归 3.2.5，本列是判定依据——行为7规则2）',
    status            VARCHAR(16)   NOT NULL COMMENT '条目状态：ACTIVE生效/ARCHIVED归档不可变（空间解散时该空间条目全部归档——行为7规则5）',
    scope_uniq        BIGINT        GENERATED ALWAYS AS (IF(status = 'ACTIVE', IF(scope = 'PLATFORM', 0, space_id), NULL)) STORED COMMENT '作用域唯一生成列（平台级 ACTIVE 记 0、空间级 ACTIVE 记 space_id、归档 NULL——支撑同作用域同键至多一条 ACTIVE）',
    created_at        DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at        DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_scope_key (entry_key, scope_uniq),
    KEY idx_space (space_id),
    KEY idx_platform_entry (platform_entry_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '空间策略条目载体表（平台级默认+空间级覆盖；条目键与值语义归 3.2.5 策略继承引擎定稿；红线=不得放宽标记；历史版本走留痕）';

-- 统一操作留痕表：四要素（谁/何时/对象/动作）+ 结果（成功/拒绝）+ 变更前后值 + 理由。
-- 创建/状态变更/准入/角色/策略/越权拒绝共用一张（动作码区分）；只插不改（无 updated_at）；
-- 拒绝动作同样留痕（行为2规则6/行为6规则5）；不含敏感原文（规格边界声明3）。
CREATE TABLE space_action_log (
    id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '技术主键',
    space_id    BIGINT       NULL COMMENT '归属空间 id（绝大多数动作可归属；NULL 仅限平台级策略动作等少数场景）',
    target_type VARCHAR(16)  NOT NULL COMMENT '对象类型：SPACE空间/MEMBER成员/ADMISSION准入单/POLICY策略条目',
    target_id   BIGINT       NULL COMMENT '对象 id（对应各表主键；探测被拒且无实体可指时为 NULL，说明并入 reason）',
    action      VARCHAR(32)  NOT NULL COMMENT '动作码：CREATE/ENABLE/FREEZE/UNFREEZE/DISSOLVE/ADMIT_REQUEST/ADMIT_INVITE/ADMIT_CONFIRM/ADMIT_APPROVE/ADMIT_REJECT/LEAVE/REMOVE/ROLE_GRANT/ROLE_REVOKE/POLICY_OVERRIDE/POLICY_OVERRIDE_REJECTED/ACCESS_DENIED 等（值域随下游包扩展，扩展须登记本注释；不删不改既有码）',
    operator    VARCHAR(64)  NOT NULL COMMENT '操作者：主体编号或 PLATFORM(平台运营方)——四要素"谁"',
    from_value  VARCHAR(64)  NULL COMMENT '变更前状态/值（状态机动作=前状态；策略覆盖=原值；纯拒绝/创建动作可 NULL）',
    to_value    VARCHAR(64)  NULL COMMENT '变更后状态/值',
    result      VARCHAR(16)  NOT NULL COMMENT '结果：SUCCESS/DENIED（拒绝同样留痕）',
    reason      VARCHAR(256) NULL COMMENT '理由（移除/拒绝的业务文案；不含敏感原文——规格边界声明3）',
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '发生时间——四要素"何时"',
    PRIMARY KEY (id),
    KEY idx_space (space_id, created_at),
    KEY idx_target (target_type, target_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '空间域统一操作留痕（四要素：谁/何时/对象/动作+结果与理由；拒绝动作同样留痕；不含敏感原文；只插不改）';
