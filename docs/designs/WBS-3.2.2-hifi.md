# WBS-3.2.2 空间数据模型 · 高保真设计（编码契约）

> 任务卡：`docs/tasks/WBS-3.2.2-空间数据模型-2026-09-26.md`｜低保真：`docs/designs/WBS-3.2.2-lofi.md`｜规格：`docs/specs/C-2.1-2.3-逻辑空间管理.md` V1.0
> **本文即编码契约**：实现与本文件不一致 = 打回项（章程 2.6）。表结构 = `V1__create_space_tables.sql` 的逐表定稿；字段口径以本文为准。

## 确认记录（人签署；未签署 = 未确认 = 禁止进入编码）

| 项 | 内容 |
| --- | --- |
| 编排师确认 | **已确认（2026-09-26 会话四问表决）：Q1+Q2 采建议 A / Q3~Q8 均采建议 A / Q9 采建议 A / D1 不拆分豁免**——本文转**编码契约**，实现与本文件不一致 = 打回项 |
| 确认时间 | 2026-09-26（立卡会话续段；随实现提交回填入库） |

## 1. 库表设计（6 表，库 `ctds_space`，MySQL 8）

通用惯例（沿 subject-service 先例）：`ENGINE=InnoDB`、`CHARSET=utf8mb4`、`id BIGINT AUTO_INCREMENT` 技术主键、`created_at/updated_at` 公共列、每表每列中文注释、状态列 `VARCHAR` 存枚举码、跨库引用一律**逻辑引用**（不建外键，引用关系在注释中标明，一致性由应用层校验——沿 subject 先例）。
排序规则（勘误注记 2026-09-26 评审轮）：各表未显式声明 COLLATE，落 MySQL 8 默认 `utf8mb4_0900_ai_ci`——大小写/重音不敏感（判重为"过阻断"方向）；0900 系 NO PAD，尾随空格参与比较，防重依赖应用层写入已归一化值；**下游不得以"大小写不同即可再建名"作业务前提**。

### 1.1 `space` 空间表（一行 = 一个逻辑空间）

| 列 | 类型 | 约束 | 说明 |
| --- | --- | --- | --- |
| id | BIGINT | PK AUTO_INCREMENT | 技术 id，即 REST `/data-spaces/{id}` 的 `{id}`（lofi Q7-A） |
| name | VARCHAR(128) | NOT NULL | 空间名称（原始输入，展示用） |
| normalized_name | VARCHAR(128) | NOT NULL | 归一化名称（去首尾空白与控制字符；**复用**主体服务归一化**口径**，实现归 3.2.3——主体既有实现为文件名归一化 `normalizeFileName`，空白集须显式定义含 Unicode 空白（如全角空格 U+3000），不可直接照搬；勘误注记 2026-09-26 评审轮） |
| scene_type | VARCHAR(16) | NOT NULL | 场景类型：`FINTECH` 普惠金融 / `MEDICAL` 医疗验证 / `OTHER` 其他（lofi Q8-A，枚举类 SceneType） |
| access_mode | VARCHAR(16) | NOT NULL | 参与方范围：`OPEN` 公开 / `INVITE` 邀请制 / `APPROVAL` 审批制（规格 Q2 裁决三档） |
| visibility | VARCHAR(16) | NOT NULL | 可见性：`PUBLIC` / `PRIVATE`（可见性 ≠ 资源可访问性，规格行为 6 规则 3） |
| intro | VARCHAR(512) | NULL | 空间简介（可选项；NULL=未填） |
| effective_from | DATETIME | NULL | 生效期起（可选项；NULL=不限） |
| effective_to | DATETIME | NULL | 生效期止（可选项；NULL=不限） |
| owner_subject_no | VARCHAR(24) | NOT NULL | 所有者主体编号（**逻辑引用** `ctds_subject.subject.subject_no`；ADMITTED 资格由应用层调 C-1.1 判定，本库不存副本） |
| status | VARCHAR(20) | NOT NULL | 状态机：`CREATED` 已创建未启用 / `ACTIVE` 已启用 / `FROZEN` 已冻结 / `DISSOLVED` 已解散（**终态不可逆**，规格 Q4） |
| created_at / updated_at | DATETIME | NOT NULL | 公共列（默认 CURRENT_TIMESTAMP / ON UPDATE） |

索引：`PRIMARY KEY(id)`；**`UNIQUE KEY uk_owner_norm_name (owner_subject_no, normalized_name)`**；`KEY idx_status(status)`；`KEY idx_norm_name(normalized_name)`（解散锁定动作与检索辅助）。
表注释：'空间表（一行=一个逻辑空间；活跃空间名称同一所有者唯一，解散名称全平台锁定见 space_name_lock）'。

### 1.2 `space_name_lock` 解散名称锁定表（一行 = 一个全平台锁定名）

| 列 | 类型 | 约束 | 说明 |
| --- | --- | --- | --- |
| normalized_name | VARCHAR(128) | **PK** | 归一化名称——全平台锁定键（任何主体不得再以该名创建，规格行为 2 规则 4） |
| space_id | BIGINT | NOT NULL | 来源空间 id（space.id，逻辑引用） |
| locked_at | DATETIME | NOT NULL DEFAULT CURRENT_TIMESTAMP | 锁定时点（解散动作发生时） |

索引：仅 PK。表注释：'解散空间名称全平台锁定表（规格行为 2 规则 4；解散事务内写入，PK 冲突即名称已被锁定）'。

### 1.3 `space_member` 成员表（一行 = 一条成员关系）

| 列 | 类型 | 约束 | 说明 |
| --- | --- | --- | --- |
| id | BIGINT | PK AUTO_INCREMENT | |
| space_id | BIGINT | NOT NULL | 空间 id（space.id，逻辑引用） |
| subject_no | VARCHAR(24) | NOT NULL | 成员主体编号（逻辑引用 subject） |
| role | VARCHAR(16) | NOT NULL | 角色：`OWNER` / `ADMIN` / `MEMBER`（规格 Q3 三档；只读审计角色归 C-9，不建） |
| status | VARCHAR(16) | NOT NULL | 关系状态：`ACTIVE` 生效中 / `LEFT` 已退出 / `REMOVED` 已移除（终态行**保留**供追溯；再次准入生成新行） |
| joined_at | DATETIME | NOT NULL DEFAULT CURRENT_TIMESTAMP | 成为成员时点 |
| exited_at | DATETIME | NULL | 退出/移除时点（NULL=仍在空间） |
| active_flag | BIGINT | **STORED 生成列** | `IF(status='ACTIVE', 1, NULL)`——支撑 uk_active_member（lofi Q6-A） |
| owner_uniq | BIGINT | **STORED 生成列** | `IF(role='OWNER' AND status='ACTIVE', 1, NULL)`——支撑 uk_active_owner |
| created_at / updated_at | DATETIME | NOT NULL | 公共列 |

索引：`PRIMARY KEY(id)`；**`UNIQUE KEY uk_active_member (space_id, subject_no, active_flag)`**；**`UNIQUE KEY uk_active_owner (space_id, owner_uniq)`**；`KEY idx_subject(subject_no)`；`KEY idx_space_role(space_id, role)`。
表注释：'空间成员表（一行=一条成员关系；同一空间同一主体至多一条生效关系、至多一个活跃所有者均由唯一索引硬兜底；退出/移除行保留改终态）'。
注：生成列为 MySQL 8 标准能力；唯一索引中 NULL 不参与去重，故终态多行可共存、活跃行唯一——探针测试实证该行为（§4 探针 4）。

### 1.4 `space_admission` 准入单表（一行 = 一次准入流程）

| 列 | 类型 | 约束 | 说明 |
| --- | --- | --- | --- |
| id | BIGINT | PK AUTO_INCREMENT | |
| space_id | BIGINT | NOT NULL | 空间 id（逻辑引用） |
| subject_no | VARCHAR(24) | NOT NULL | 被准入主体编号（逻辑引用 subject） |
| type | VARCHAR(16) | NOT NULL | 准入形态：`APPLICATION` 申请 / `INVITATION` 邀请（由空间 access_mode 决定，规格行为 3 规则 2） |
| status | VARCHAR(24) | NOT NULL | `PENDING_APPROVAL` 待审批 / `PENDING_CONFIRMATION` 待被邀方确认 / `APPROVED` 已通过（成员关系已建立）/ `REJECTED` 已拒绝 / `DECLINED` 被邀方谢绝 / `CANCELLED` 已撤回（状态机流转细则归 3.2.4，本表定值域与载体） |
| operator | VARCHAR(64) | NOT NULL | 发起方（申请人主体编号 / 邀请操作人） |
| reason | VARCHAR(256) | NULL | 拒绝/谢绝理由（业务文案；拒绝须记录理由，规格行为 3 规则 5） |
| member_id | BIGINT | NULL | 生效后的成员关系 id（space_member.id；status=APPROVED 时回填，未生效为 NULL） |
| created_at / updated_at | DATETIME | NOT NULL | 公共列 |

索引：`PRIMARY KEY(id)`；`KEY idx_space_status(space_id, status)`；`KEY idx_subject(subject_no)`。
表注释：'空间准入单（申请/邀请载体：未确认邀请与未审批申请不产生成员关系；通过后回填 member_id 贯通追溯）'。

### 1.5 `space_policy` 策略条目表（载体级；一行 = 一个策略条目的当前值）

| 列 | 类型 | 约束 | 说明 |
| --- | --- | --- | --- |
| id | BIGINT | PK AUTO_INCREMENT | |
| scope | VARCHAR(16) | NOT NULL | 作用域：`PLATFORM` 平台级（默认来源）/ `SPACE` 空间级（覆盖） |
| space_id | BIGINT | NULL | 空间 id（scope=SPACE 必填；PLATFORM 为 NULL） |
| platform_entry_id | BIGINT | NULL | 被覆盖的平台级条目 id（scope=SPACE 必填；平台级行为 NULL）——继承链的显式指向 |
| entry_key | VARCHAR(64) | NOT NULL | 条目键（**载体级**：键命名与可配置项集合归 3.2.5 定稿，规格行为 7 规则 6） |
| entry_value | VARCHAR(1024) | NOT NULL | 条目值（当前生效值；历史变更走留痕"从何值→到何值"，本表不存版本链） |
| is_redline | TINYINT | NOT NULL DEFAULT 0 | 红线标记：1=限制性条款不得放宽（仅平台级条目可标记；放宽拒绝判定归 3.2.5，本列是判定依据） |
| status | VARCHAR(16) | NOT NULL | `ACTIVE` 生效 / `ARCHIVED` 归档不可变（空间解散时该空间条目全部置 ARCHIVED，规格行为 7 规则 5） |
| scope_uniq | BIGINT | **STORED 生成列** | `IF(status='ACTIVE', IF(scope='PLATFORM', 0, space_id), NULL)`——平台级记 0、空间级记 space_id、归档为 NULL |
| created_at / updated_at | DATETIME | NOT NULL | 公共列 |

索引：`PRIMARY KEY(id)`；**`UNIQUE KEY uk_scope_key (entry_key, scope_uniq)`**（同作用域同键至多一条 ACTIVE：平台级同键唯一、空间级同键唯一覆盖行；ARCHIVED 多行共存）；`KEY idx_space(space_id)`；`KEY idx_platform_entry(platform_entry_id)`。
表注释：'空间策略条目载体表（平台级默认+空间级覆盖；条目键与值语义归 3.2.5 策略继承引擎定稿；红线=不得放宽标记；历史版本走留痕）'。

### 1.6 `space_action_log` 统一操作留痕表（一行 = 一次动作/留痕事件）

| 列 | 类型 | 约束 | 说明 |
| --- | --- | --- | --- |
| id | BIGINT | PK AUTO_INCREMENT | |
| space_id | BIGINT | NULL | 归属空间（绝大多数动作可归属；NULL 仅限平台级策略动作等少数场景） |
| target_type | VARCHAR(16) | NOT NULL | 对象类型：`SPACE` / `MEMBER` / `ADMISSION` / `POLICY`（枚举 TargetType） |
| target_id | BIGINT | NULL | 对象 id（对应各表主键；探测被拒且无实体可指时为 NULL，此时 target 说明并入 reason） |
| action | VARCHAR(32) | NOT NULL | 动作码：CREATE / ENABLE / FREEZE / UNFREEZE / DISSOLVE / ADMIT_REQUEST / ADMIT_INVITE / ADMIT_CONFIRM / ADMIT_APPROVE / ADMIT_REJECT / LEAVE / REMOVE / ROLE_GRANT / ROLE_REVOKE / POLICY_OVERRIDE / POLICY_OVERRIDE_REJECTED / ACCESS_DENIED 等（值域随下游包实现扩展，扩展须在本表注释登记；不删不改既有码） |
| operator | VARCHAR(64) | NOT NULL | 操作者：主体编号或 `PLATFORM`（平台运营方）——四要素"谁" |
| from_value | VARCHAR(64) | NULL | 变更前状态/值（状态机动作=前状态；策略覆盖=原值；纯拒绝/创建动作可 NULL） |
| to_value | VARCHAR(64) | NULL | 变更后状态/值 |
| result | VARCHAR(16) | NOT NULL | `SUCCESS` / `DENIED`（**拒绝同样留痕**，规格行为 2 规则 6 / 行为 6 规则 5） |
| reason | VARCHAR(256) | NULL | 理由（移除/拒绝的业务文案；**不含敏感原文**，规格边界声明 3） |
| created_at | DATETIME | NOT NULL DEFAULT CURRENT_TIMESTAMP | 发生时间——四要素"何时"（留痕只插不改，无 updated_at） |

索引：`PRIMARY KEY(id)`；`KEY idx_space(space_id, created_at)`；`KEY idx_target(target_type, target_id)`。
表注释：'空间域统一操作留痕（四要素：谁/何时/对象/动作+结果与理由；拒绝动作同样留痕；不含敏感原文；只插不改）'。

## 2. 三条唯一性硬约束的 DB 兜底方案（本包核心质量点）

| # | 规格规则 | DB 机制 | 应用层职责（下游包） |
| --- | --- | --- | --- |
| 1 | 活跃空间名称同一所有者唯一（行为 1 规则 3） | `uk_owner_norm_name(owner_subject_no, normalized_name)`——同 owner 下活跃与解散行共同覆盖，解散后同 owner 重建亦被拒（与全平台锁定口径一致，非加严） | 归一化计算、业务文案（错误码 1006 段） |
| 2 | 解散空间名称全平台不可复用（行为 2 规则 4） | `space_name_lock` PK——解散事务内 INSERT，同名已锁定即主键冲突 | 解散动作的事务编排（3.2.3）：status=DISSOLVED + 写锁定表 + 策略归档，同事务 |
| 3 | 重复准入不产生重复成员（行为 3 规则 4）+ 唯一所有者保护（行为 5 规则 3） | `uk_active_member`（生成列 active_flag）+ `uk_active_owner`（生成列 owner_uniq） | 准入流程、owner 唯一性的业务校验与文案 |

并发窗口说明：三条机制均把"判定+写入"收敛到存储引擎原子层，并发重复请求在 DB 层必有一方失败——下游包捕获唯一键冲突转业务文案即可，无需引入分布式锁（幂等创建另走 `common/idempotency`，归 3.2.3）。

## 3. 领域模型类（domain 包，与表逐列对应）

- 实体 **record**（风格沿 `Subject.java` 本然——`Subject.java` 即 record；勘误注记 2026-09-26 评审轮）：`Space`、`SpaceMember`、`SpaceAdmission`、`SpacePolicy`、`SpaceActionLog`。
- 枚举（`displayName` 构造器 + `getDisplayName()`，风格沿 `SubjectStatus.java` 本然；勘误注记同轮）：`SpaceStatus`、`AccessMode`、`Visibility`、`SceneType`、`MemberRole`、`MemberStatus`、`AdmissionType`、`AdmissionStatus`、`PolicyScope`、`PolicyStatus`、`TargetType`、`ActionResult`（12 个）。
- **不建**：Repository 接口、错误码常量类（无错误码）、配置类（lofi Q2-A）。

## 4. 模块骨架与测试计划

**模块骨架**：`services/space-service`——`pom.xml`（parent=`com.ctds:ctds-platform:2.0.0-SNAPSHOT`，`relativePath=../../pom.xml`；依赖 spring-boot-starter-jdbc、flyway-core、flyway-mysql、mysql-connector-j、test 侧 spring-boot-starter-test + testcontainers mysql/junit-jupiter——**全部为既有依赖族，版本沿父 pom/dependencyManagement，零新增依赖族**）+ `SpaceServiceApplication.java` + `application.yml`（port `8083`、库 `ctds_space`、flyway enabled；配置结构沿 subject-service 同款，profile 拆分照抄）+ domain 包（勘误注记 2026-09-26 评审轮：application 包随 3.2.3 建立，git 不跟踪空目录不落空壳）。根 pom `<modules>` 追加一行。

**测试计划**（`src/test/java/.../space/SpaceMigrationIntegrationTest.java`，Testcontainers MySQL 8 实跑——**不用 H2 兜底**：生成列/IF 方言行为必须真库实证，沿 2.4.11 教训；探针用例间状态隔离用方法级容器或 @BeforeEach 清空，沿"共享库撞状态"教训。**勘误注记（实现落盘时，非设计变更）**：测试类名由初稿 `SpaceMigrationIT` 改为 `SpaceMigrationIntegrationTest`——沿 subject-service 先例 `*IntegrationTest` 命名（surefire 直接执行，工程未配 failsafe，`*IT` 命名会静默漏跑）；容器支持内联于测试类（建库/授权用容器 root、断言用应用用户——沿 SharedMySqlContainer 同款分工），独立库名隔离语义等价方法级容器）：

| # | 探针 | GIVEN/WHEN/THEN | 映射规格 |
| --- | --- | --- | --- |
| 0 | 迁移冒烟 | 空库启动 → Flyway V1 全部成功 → 6 表存在且列齐 | 建表正确性 |
| 1 | 同 owner 同名拒绝 | 同 owner 两行同 normalized_name ACTIVE → 第二行 INSERT 抛唯一冲突 | 行为 1 规则 3 |
| 2 | 跨 owner 同名允许 | 不同 owner 同 normalized_name → 两行均成功（**不加严**实证） | 行为 1 规则 3"同一所有者"口径 |
| 3 | 解散名全平台锁定 | 锁定表写"某名"后再次写入任一归属 → PK 冲突 | 行为 2 规则 4 |
| 4 | 成员唯一活跃+唯一所有者 | 同空间同主体两 ACTIVE → 冲突；ACTIVE+LEFT 共存 → 成功；两主体同任 OWNER → 冲突 | 行为 3 规则 4 / 行为 5 规则 3 |
| 5 | 策略载体唯一 | 平台级同 key 两 ACTIVE → 冲突；空间级同 key 两 ACTIVE → 冲突；ARCHIVED 多行 → 共存 | 行为 7（载体约束） |
| 6 | 枚举与注释在位 | 列 COMMENT/表 COMMENT 断言 + 生成列定义断言（information_schema 实查） | 文档化承诺可核对 |

**本地门禁**：`mvn -B -ntp compile / test / checkstyle:check`（space 模块）+ 全仓门禁脚本既有段（`secretsScan` 等，按门禁配置既有口径；不动门禁配置）。

## 5. 数据分级落级表（规格合规锚点指派义务；Q9-A 回写规范 §6.1）

| 表/字段组 | 类目 | 建议级别 | 定级理由 | 管控锚点 |
| --- | --- | --- | --- | --- |
| space：名称/场景/范围/可见性/简介/生效期/状态 | CAT-01 | L2 | 平台应用配置；聚合可推平台空间名录 | 表注释；展示层无脱敏需求 |
| space.owner_subject_no、space_member.subject_no/role、space_admission.subject_no | CAT-02 | L2 | 主体标识+权限映射（对外业务标识，非鉴别信息；C-1.1 出站口径；role 为权限映射载体，2026-09-26 评审轮与规范 §6.1 对齐） | 跨库逻辑引用；不入日志原文 |
| space_policy：entry_key/entry_value/is_redline | CAT-01 | L2 | 应用配置；**若未来策略值引用数据资源则就高随来源**（诚实条款登记） | 3.2.5 定稿时复核 |
| space_action_log：全部字段 | CAT-06 | L3 | 安全与审计数据，篡改可掩盖痕迹（对齐审计明细 L3 先例） | 只插不改；不含敏感原文（reason 业务文案） |
| space_name_lock | CAT-01 | L2 | 运营索引数据 | PK 硬约束 |

## 6. 边界值与异常行为（非界面类设计要素）

- 长度门槛：name/intro 等列长见 §1（name 128 沿 subject_name 先例；超长由列宽自然拒绝，应用层给业务文案）；normalized_name 与 name 同宽（128）。
- NULL 语义：intro/effective_from/effective_to/reason/exited_at/member_id/platform_entry_id/space_id(policy)/target_id/from_value/to_value 的 NULL 均为业务合法态（含义见各列注释），除此之外列不允许 NULL。
- 枚举越界：状态列应用层一律以枚举写码，非法码由下游包校验拦截（DB 不做 CHECK 约束——沿 subject 先例不建 CHECK，靠枚举类+测试保证）。
- 异常行为承诺（下游包消费本契约时的可预期失败）：三条唯一性约束冲突 = 唯一键异常（错误码文案归下游包）；解散时锁定表冲突 = 名称已被历史空间锁定；准入通过回填 member_id 失败 = 整体回滚（事务边界归 3.2.4，本表结构支持）。

## 7. 交付物核对清单（实现完成即对照打勾）

1. `services/space-service/pom.xml` + 根 pom modules 追加；2. 主类 + application.yml（8083/ctds_space/flyway）；3. `V1__create_space_tables.sql`（6 表 = §1 逐列一致）；4. domain 包 5 实体 + 12 枚举；5. `SpaceMigrationIntegrationTest` + `SpaceDomainEnumsTest`（探针 0~6 全过 + 枚举值域封闭性，评审修复批补齐）；6. 分级规范 §6.1 回写 5 行（§5）；7. 本卡与 lofi/hifi 确认记录签署回填；8. 台账 L1-3 行与日志。

**勘误注记（2026-09-26 评审修复轮，非设计变更）**：① §3 实体/枚举风格描述更正为先例本然（record / displayName，先勘误为"POJO/getter"系初稿笔误，实现一直沿先例）；② §4 parent 坐标更正 `ctds-parent` → `ctds-platform`（实现文件一向正确）；③ §4 依赖清单补 flyway-mysql（subject 同款既有依赖，非新增）；④ §7 测试类名同步 §4 勘误；⑤ §1.1 归一化表述更正为"复用口径、实现归 3.2.3"（主体既有实现系文件名归一化不可照搬，空白集须显式定义含 Unicode 空白）；⑥ §1 补 COLLATE 默认行为登记；⑦ application 空包改为"随 3.2.3 建立"；⑧ V1 迁移内注释与 §1 逐列核对语义一致、文字级差异不逐一同步（**表/列注释以 SQL 文件为准**，探针 6 按 SQL 注释断言）。
