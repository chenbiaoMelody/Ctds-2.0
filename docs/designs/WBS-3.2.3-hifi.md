# WBS-3.2.3 空间生命周期服务 · 高保真设计（编码契约）

> 任务卡：`docs/tasks/WBS-3.2.3-空间生命周期服务-2026-09-26.md`｜低保真：`docs/designs/WBS-3.2.3-lofi.md`｜规格：`docs/specs/C-2.1-2.3-逻辑空间管理.md` V1.0
> **本文即编码契约**：实现与本文件不一致 = 打回项（章程 2.6）。

## 确认记录（人签署；未签署 = 未确认 = 禁止进入编码）

| 项 | 内容 |
| --- | --- |
| 编排师确认 | **已确认（2026-09-26 22:4x 会话三问表决）：Q1+Q2 / Q3+Q4+Q6 / Q5+D1 均采建议 A**——本文转**编码契约**，实现与本文件不一致 = 打回项 |
| 确认时间 | 2026-09-26 22:4x 签署；**实现已回填（2026-09-26 编码会话，实现提交 `600f0e7`，T1~T14 全绿 + checkstyle 0 违规）** |

## 1. 端点契约表（REST，前缀 `/api/v1/data-spaces`，ADR-005 先例；响应统一 ApiResult 封套）

| # | 端点 | 权限判定 | 请求体 → 出参 | 规格锚点 |
| --- | --- | --- | --- | --- |
| 1 | `POST /api/v1/data-spaces` | 登录主体（`AuthContext.subject()`）；创建者=owner | `{name, sceneType, accessMode, visibility, intro?, effectiveFrom?, effectiveTo?}` → `SpaceDetail` | 行为 1 全部规则 |
| 2 | `POST /{id}/enablement` | owner/admin（成员表）或 platform.operator | 空 → `SpaceDetail` | 行为 2 规则 1/2/5 |
| 3 | `POST /{id}/freezing` | 同上 | 空 → `SpaceDetail` | 行为 2 规则 1/3/5 |
| 4 | `POST /{id}/unfreezing` | 同上 | 空 → `SpaceDetail` | 行为 2 规则 1/3/5 |
| 5 | `POST /{id}/dissolution` | owner 或 platform.operator（**admin 不可解散**，行为 4 规则 3"仅所有者动作"） | `{confirmDissolve: true（必填）， reason?}` → `SpaceDetail` | 行为 2 规则 4/5 |
| 6 | `PUT /{id}` | owner/admin 或 platform.operator | `{intro?, effectiveFrom?, effectiveTo?}` → `SpaceDetail` | 行为 2 规则 5 + 剧本 S2 配置留痕 |
| 7 | `GET /api/v1/data-spaces` | 公开：全部登录主体（仅 PUBLIC）；platform.operator：全量 | `?pageNum&pageSize&keyword?` → `PageResult<SpaceSummary>` | 行为 6 规则 3（可见性=可检索） |
| 8 | `GET /{id}` | 成员/owner/admin 或 platform.operator：全量；非成员：仅 PUBLIC 返回摘要（intro/状态，**不含成员构成**） | → `SpaceDetail` 或 `SpaceSummary` | 行为 6 规则 3/规则 1 |

通用约定：路径参数 `{id}` 为技术 id（3.2.2 Q7-A）；状态门槛失败 = `1006C0002`；空间不存在 = `1006C0004`，读面对"不公开空间"按不存在口径**同形**返回（防存在性探测）；治理类动作对"自己的空间"可区分不存在（E2 勘误）；请求方身份与角色经 `common/auth`（`AuthContext.subject()` / 角色头），`X-Ctds-Subject` 头为演示期身份（沿 subject 演示口径）。

## 2. 错误码表（`SpaceErrorCodes`，1006 段定稿；9 位 = 段 4 位 + 类型 1 位 + 序号 4 位，沿 ADR-005 体系）

| 码 | 常量 | 语义 | HTTP |
| --- | --- | --- | --- |
| 1006C0001 | ADMISSION_REQUIRED | 主体未入驻（统一文案，不区分"不存在/未入驻"——防枚举） | 403 |
| 1006C0002 | SPACE_STATUS_GATE | 空间状态不允许该动作（含终态再动作、未启用不可变更） | 409 |
| 1006C0003 | SPACE_NAME_TAKEN | 同一所有者已有同名空间（归一化后判定） | 409 |
| 1006C0004 | SPACE_NOT_FOUND | 空间不存在 | 404 |
| 1006C0005 | SPACE_ELEMENT_MISSING | 创建要素缺失/超长（逐字段提示） | 400 |
| 1006C0006 | DISSOLVE_CONFIRM_REQUIRED | 解散二次确认缺失（confirmDissolve ≠ true） | 400 |
| 1006C0007 | SPACE_ACCESS_DENIED | 非授权主体执行该动作（逐动作判定失败，含 member 越权） | 403 |
| 1006S0001 | SUBJECT_SERVICE_UNAVAILABLE | 主体服务不可达/失败（UNAVAILABLE 统一文案，**不冒充 1006C0001**） | 503 |

类型位口径沿既有段：C=业务拒绝、S=系统不可用；序号段内顺延。对外文案不暴露内部实现（红线：吞异常禁止/对外不暴露）。

## 3. 状态机与事务契约

- **合法边**（`SpaceStatus` 枚举既有值域，不得增删）：`CREATED→ACTIVE`（启用）；`ACTIVE→FROZEN`（冻结）；`FROZEN→ACTIVE`（恢复）；`CREATED→DISSOLVED`、`ACTIVE→DISSOLVED`、`FROZEN→DISSOLVED`（解散，任一非终态）；`DISSOLVED` 无出边。
- **实现**（沿 subject `appendTransition` 乐观门槛先例，3.1.3 教训）：`UPDATE space SET status=? , updated_at=NOW() WHERE id=? AND status=?` 同事务追加留痕；**0 行更新 = 状态门槛拒绝**（`1006C0002`）——不先查后改（TOCTOU）。
- **解散事务三写**（3.2.2 hifi §2 契约兑现，`@Transactional` 单事务）：① `UPDATE space SET status='DISSOLVED'`（乐观门槛）；② `space_name_lock` 写入（`INSERT ... SELECT ... WHERE NOT EXISTS` 语义 = **已锁定即跳过**，Q6-A；跳过时留痕仍记 DISSOLVED）；③ `UPDATE space_policy SET status='ARCHIVED' WHERE space_id=? AND status='ACTIVE'`。任一失败整体回滚。
- **启用前提**（行为 2 规则 2）：要素完整（本卡创建路径天然完整）+ 所有者主体仍 ADMITTED（调 Q1 通道复查）。

## 4. 资格判定与归一化（3.2.2 移交三项兑现）

- **SubjectAdmissionClient**（infrastructure，沿 `SubjectStatusHttpClient` 93 行先例，JDK HttpClient 零依赖）：服务身份头 `space-service` / `space-internal`（只读角色）；`GET /api/v1/subject/internal/subjects/{no}/admission`；**ADMITTED=true 才放行**；不可达/超时(1s/3s)/非 200/解析失败 → `1006S0001`（UNAVAILABLE，不冒充资格拒绝）；主体不存在或非 ADMITTED → `1006C0001` 统一文案。subject 侧改动 = `application.yml` 角色映射追加 `space-internal: subject.internal.read`（1 行）+ `ADR-016 §6` 衔接契约补记（登记 space 为第 3 个消费方，did-internal 先例同款）。
- **归一化新建实现**（`SpaceNameNormalizer`，domain；移交①）：① 去除 Unicode 控制字符（`Cf` 格式字符与 `Cc` 控制字符，含零宽 U+200B~U+200D、BOM U+FEFF）；② 空白集显式定义 = `Character.isWhitespace` ∪ U+3000（全角空格）∪ U+00A0（nbsp），首尾 trim、**内部连续空白折叠为单个空格**；③ 长度校验在归一化后执行（≤128）。判重前提 = 列排序规则 `utf8mb4_0900_ai_ci`（大小写/重音不敏感、NO PAD——**尾随空格不做 MySQL PAD 等价**，故归一化必须先 trim，移交②）。
- **ActionResult 与 AuditOutcome 分工**（移交③）：`space_action_log.result`（SUCCESS/DENIED）= 业务留痕值域（动作是否生效）；`common/logging` 的 AuditOutcome = 横切审计出口（接口访问审计），两套不混用、不互相翻译。

## 5. 幂等与权限

- **创建幂等**：`@Idempotent(key = "#cmd.ownerSubjectNo + ':' + #cmd.normalizedName")`（ADR-007 模式 B，沿 subject 注册先例；演示/单测 memory 模式）；重复提交返回首次结果。**（DB-28 修复补注，2026-09-28；见 §10 E13）**：幂等命中返回首次结果**以名称未锁定为前提**——命中结果是创建时快照（`status` 恒为 CREATED，不反映其后解散），故 `SpaceCommandService` 在幂等调用返回后补一次名称锁复核：命中空间其后已解散（归一化名已进 `space_name_lock`）→ 按名称锁口径拒绝（`1006C0003` + 锁定文案），不以 `code=0` 返回已解散空间。
- **双轨权限**（Q4-A）：① 平台角色 = 角色头（`platform.operator` 档加入演示期角色映射，`AuthProperties` 配置；subject/kms/did 同款机制）；② 空间内角色 = 查 `space_member`（`status='ACTIVE'` 且 `role IN ('OWNER','ADMIN')`，按动作差异化：解散仅 OWNER）。判定失败 → `1006C0007` + DENIED 留痕。**空间创建者写 owner 成员行**（行为 1 规则 1"创建者自动成为所有者"——`space_member` 插入 `role=OWNER, status=ACTIVE`，uk_active_owner 天然兜底）。

## 6. 部署清单（3.2.2 纪律声明顺延项兑现）

`deploy/k8s/` 追加 `space-deployment.yaml` + `space-service.yaml`（沿 backend 模板：镜像占位符 IMAGE_PLACEHOLDER、回环/探针口径对齐、非 root uid 1001）+ `kustomization.yaml` 追加两行；`deploy/runbook.md` 服务清单节补 1 行。**不动既有清单字段**。

## 7. 测试计划（集成测试 Testcontainers mysql:8.0 实跑 + 枚举/归一化纯单测；"承诺-实测对账"教训：映射表每格可指到测试行）

| # | 测试 | 断言要点 | 映射 |
| --- | --- | --- | --- |
| T1 | 创建正向 | 200 + ApiResult success（E4 勘误：全仓先例口径）/CREATED 初始态/owner 成员行落库/留痕四要素（含 target_id） | 行为 1 规则 1/4/6 |
| T2 | 资格门槛防枚举 | 未入驻与不存在同文案 `1006C0001`；服务停 → `1006S0001` 不冒充 | 行为 1 规则 1 |
| T3 | 归一化判重 | 同 owner"全角空格名"被拒（U+3000 绕过失败）；跨 owner 同名允许 | 行为 1 规则 3 |
| T4 | 幂等 | 同键重复提交返回首次结果、空间数不变 | 行为 1 规则 5 |
| T5 | 要素校验 | 缺名称/场景/范围/可见性 → `1006C0005` 逐字段 | 行为 1 规则 2 |
| T6 | 状态机全边 | CREATED→ACTIVE→FROZEN→ACTIVE→DISSOLVED 正向链；CREATED→FROZEN 等非法边拒 `1006C0002`；DISSOLVED 再动作拒 | 行为 2 规则 1 |
| T7 | 启用前提 | 所有者资格失效（client mock 拒绝）→ 启用被拒 | 行为 2 规则 2 |
| T8 | 解散契约 | confirmDissolve 缺失拒 `1006C0006`；三写落库（status+name_lock+policy ARCHIVED）同事务断言；留痕 from→to | 行为 2 规则 4/6 |
| T9 | 同名先后解散边界 | 跨 owner 同名两空间先后解散均成功、名称保持锁定、第二空间不重复写锁 | Q6-A |
| T10 | 权限双轨 | member 冻结被拒 `1006C0007`+DENIED 留痕；非成员启用被拒；platform.operator 全动作放行；admin 解散被拒 | 行为 2 规则 5 |
| T11 | 配置变更 | intro 生效期可改+留痕从何值→到何值；改名称/可见性被拒（不可变更字段拒绝语义=仅接受白名单字段，多余字段 400） | Q5-A |
| T12 | 读面可见性 | 公开空间出现在列表；PRIVATE 不出现（运营方全量）；非成员取详情得摘要不含成员构成 | 行为 6 规则 3 |
| T13 | 归一化单测 | `SpaceNameNormalizer` 纯单测：控制字符/全角空格/连续空白折叠；长度断言落应用服务校验（E5 勘误，探针随 T17） | 移交① |
| T14 | 枚举封闭性单测 | 12 枚举 + `SpaceErrorCodes` 码值格式（9 位/段位 1006） | 值域必填格（-2138 教训） |

**本地门禁**：`mvn -B -ntp -pl services/space-service -am test` + `checkstyle:check` + subject 模块回归（yml 改动）+ 前端/其他模块不触碰不重跑。

## 8. 边界值与异常行为

- 名称 ≤128（归一化后）；intro ≤512（3.2.2 列宽）；理由 ≤256；错误文案不暴露内部实现；
- `confirmDissolve` 语义 = 请求体显式 `true`（缺省/null/false 一律 `1006C0006`）；
- 读面 keyword 检索 = 归一化名称 LIKE 前缀（防全表扫；语义登记：检索按名称前缀）；
- 解散后读面：DISSOLVED 空间对成员仍可见（留痕保留可查），检索列表不再出现（终态不出现在可检索面——规格行为 7 规则 5"归档保留可查"指策略，空间本体终态可见性 hifi 口径 = 详情可达、列表不出）。

## 9. 交付物核对清单

1. 四层代码（interfaces 1 控制器 / application / domain / infrastructure + client）；2. `SpaceErrorCodes`；3. subject yml 1 行授权（1 行映射 + 1 行注释）+ ADR-016 §6 补记；4. deploy/k8s 三文件 + runbook 1 行；5. **V2 迁移**（`space_action_log` 动作码登记 UPDATE + from_value/to_value 64→1024，只放宽不收窄——迁移历史断言随迁移集演进）；6. 测试 T1~T19 全绿；7. 本卡与 lofi/hifi 签署回填；8. 台账与日志。

## 10. 评审循环 1 勘误与补测登记（2026-09-27，4 视角评审对账结论；均为澄清与补严，非需求变更）

| # | 条目 | 勘误内容 |
| --- | --- | --- |
| E1 | §1 端点 7 参数 | `?pageNum&pageNumSize&keyword?` → **`?pageNum&pageSize&keyword?`**（沿 ADR-005 §3.2 / common-pagination 与 subject/did 先例；hifi 笔误） |
| E2 | §1 通用约定 | 草稿式自问自答改陈述句：状态门槛失败 = 1006C0002；空间不存在 = 1006C0004；**读面对"不公开空间"按不存在口径同形返回**（防存在性探测）；治理类动作对"自己的空间"可区分不存在 |
| E3 | §2 码表 | 补登记同码不同文案：`1006C0003` 另有锁定文案 **SPACE_NAME_LOCKED_MESSAGE"空间名称已被锁定，不可复用"**（名称锁定拒绝路径，实现已落） |
| E4 | §7 T1 | "201 语义" → **200 + ApiResult success**（全仓先例口径，实现/测试/先例三方一致） |
| E5 | §7 T13 | "长度"断言落点 = 应用服务校验（`SpaceCommandService`，归一化器本身无长度逻辑）；名称**原始输入同限 128**（name 列 VARCHAR(128) 存原始输入，防御性前置校验）一并登记 |
| E6 | §3/§7 T6 | 终态自环修复登记：解散入口增加显式终态前置门槛（DISSOLVED 无出边；同值 WHERE 乐观门槛拦不住 DISSOLVED→DISSOLVED 自环）+ T6 补边（CREATED→DISSOLVED、FROZEN→DISSOLVED）+ 二次解散 409 且无新增留痕断言 |
| E7 | §7 测试计划 | 补测登记：**T15** 逐动作权限矩阵（非成员冻结/解散、成员解散 + DENIED 留痕逐动作齐备）与终态自环；**T16** 解散后名称全平台锁定拒绝（跨主体）；**T17** 边界值（名称 129 / 简介 513 / 理由 257 / 纯空白名）与 T8 confirmDissolve null 形态；**T18** keyword 通配符转义（% 字面前缀）；**T19** client 读超时（挂起桩 >3s → UNAVAILABLE）与畸形主体编号（URI 非法 → UNAVAILABLE 不出站 500）+ 码表↔处理器映射一致性锚 |
| E8 | §5 权限 | isOwner = `space.owner_subject_no` 列口径（与成员表活跃 OWNER 行的一致性由 `uk_active_owner` 兜底；所有权转移须同步两处——登记给 3.2.4）；yml `platform.operator: space.manage` 映射当前**零消费**（双轨①走 `AuthContext.roles()`，权限点命名随 3.2.4 收敛，登记防误读） |
| E9 | 行为 6 规则 5 | 读面拒绝留痕（非成员访问不公开空间的拒绝留痕）归 **3.2.4/3.2.7**（本卡读面按 hifi §1 端点 8 摘要/404 同形口径，无留痕） |
| E10 | §6 部署 | 激活前提补登记：**prometheus 三注解 + actuator/micrometer 依赖随激活批次补**（ADR-014）；ModuleMap/镜像构建入列；subject 服务集群可达；扩容前幂等/锁切 redis（ADR-007，当前 memory + 单副本一致）；网关/真实令牌就位前角色头信任边界沿 ADR-016 §2.7（仅本机/演示） |
| E11 | §5 幂等 | 键卫生登记：键长上限 256（common）与业务极值 257（128+1+128）存在边缘冲突 → 3.2.4/沉淀候选（长度前缀或摘要编码）；`:` 分隔符理论碰撞（演示期身份受控，实务不可达） |
| E12 | 沉淀建议 | ① `SubjectAdmissionClient` 与 did `SubjectStatusHttpClient` 逐字同构（第 2 份副本）→ 跨服务客户端沉淀候选；② common `GlobalExceptionHandler` 补 `MethodArgumentTypeMismatchException`→400（畸形数值入参当前 500）；③ 迁移测试独立库名/建库 GRANT 支撑逻辑第 4 份变体 → ADR-010 §10 已达触发条件（4 模块），立小卡待编排师 |
| E13 | §5 幂等 | **DB-28 修复口径补注**（2026-09-28，WBS-3.2.6 走查 C-2.1 S3-6 FAIL）：幂等命中返回首次结果**以名称未锁定为前提**——命中结果 = 创建时快照（`status` 恒为 CREATED），首次空间其后是否已解散只能以名称锁表判定（解散与锁定同事务写入，行为 2 规则 4）；命中且名称已锁定 → `SpaceCommandService` 复核后按名称锁口径拒绝（`1006C0003` + 锁定文案）。补测 T19（解散后同名重放拒）/ T20（未解散重放保持语义）/ T21（同键并发不劣化） |
