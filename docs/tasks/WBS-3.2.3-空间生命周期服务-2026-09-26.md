# WBS-3.2.3 任务卡：空间生命周期服务（2026-09-26）

| 项 | 内容 |
| --- | --- |
| 来源 | 台账「下一包」= **WBS 行 253**；编排师 2026-09-26 会话回复"**继续**"（承接日志 `docs/logs/Ctds-项目开发日志-26-09-26-2138.md` 续点"下一包 = 3.2.3 空间生命周期服务，待编排师立卡指示"；**多任务单会话显式授权沿清债四卡先例**，逐卡提交/落日志） |
| 级别与性质 | L1-3 平台功能**实施包**（WBS 行 253 预算 **1 会话**）——**非界面类**：交付 = 空间创建/配置/冻结/解散的完整服务（接口层+应用服务+领域+基础设施）；**走两级设计门禁**（lofi/hifi 一并落盘、一次确认——章程 2.6.3），设计文件 `docs/designs/WBS-3.2.3-lofi.md` / `-hifi.md` |
| 需求锚点核对 | 规格 `docs/specs/C-2.1-2.3-逻辑空间管理.md` V1.0：**行为 1**（空间创建与要素设定：资格门槛/要素/命名唯一与归一化/留痕/幂等/初始态）+ **行为 2**（生命周期：状态机/启用前提/冻结/解散含二次确认与名称锁定/超权限拒绝/留痕）+ 行为 6 的"可见性=可否被检索"读面承载；WBS 行 253（本卡 = "创建/配置/冻结/解散服务"）；PRD 行 109（C-2.1 P0） |
| 上游能力边界 | **3.2.2 交付**（`ctds_space` 六表+约束+动作码值域+domain 5 实体 12 枚举+模块骨架 8083；解散三写事务契约 = hifi-3.2.2 §2）；**3.2.2 移交三项**（①归一化新建实现·空白集显式定义含 Unicode 空白；②判重前提 `utf8mb4_0900_ai_ci`；③ActionResult 与 common AuditOutcome 分工）；**subject 内部端点**（`/api/v1/subject/internal/subjects/{no}/admission`，ADR-016 §6 衔接契约先例，did 同款复用）；`common` 幂等（@Idempotent，ADR-007）/auth（AuthContext+角色头）/pagination/errorcode；subject 状态机乐观门槛先例（3.1.3）；ADR-005 资源命名 `/api/v1/data-spaces`；**错误码 1006 段启用**（规格 Q7 预留，码值本卡定稿） |
| 交付物 | ① space-service 四层补全：接口层 7 端点（创建/启用/冻结/恢复/解散/配置变更/最小读面）+ 应用服务（SpaceCommandService 状态机+资格门槛+幂等+留痕）+ domain（SpaceRepository 接口+状态机规则）+ infrastructure（JdbcRepository+SubjectAdmissionClient）；② `SpaceErrorCodes` **1006 段码值定稿**；③ subject-service **唯一改动 = application.yml 角色映射加 1 行**（`space-internal: subject.internal.read`）+ ADR-016 §6 衔接契约补记；④ 部署清单 space-service 入列（deploy/k8s 追加 deployment/service/kustomization）；⑤ 集成测试（Testcontainers 实跑，含状态机全边/资格防枚举/归一化含 U+3000/幂等/解散三写/同名先后解散边界）；⑥ 本任务卡+lofi/hifi+台账+日志 |
| 关闭条件 | ① 两级设计经编排师**一次确认**（实现与设计逐条一致）；② 本地门禁全绿（compile/test/checkstyle）+ 集成测试全过；③ 规格行为 → 端点/测试映射表齐备（本卡 §三）；④ 4 视角评审通过+修复批复审；⑤ 编排师验收通过 |
| 分支 | `feat/WBS-3.2.3-空间生命周期服务`（自 `main` = `90d98c4`，沿"每包独立分支"惯例） |
| 纪律声明 | **只做空间本体的创建/配置/生命周期**——成员准入/角色/退出移除与隔离判定归 3.2.4、策略继承引擎归 3.2.5、界面归 3.2.6、测试达标（覆盖率/变异）归 3.2.7；既有服务仅 subject-service yml 加 1 行授权（本卡声明的最小必要改动，设计留痕）；不动门禁配置、不引新依赖（client 用 JDK HttpClient 零依赖）；错误码码值以 `SpaceErrorCodes` 常量类定稿；发现规格缺口一律走变更流程（红线 2/章程 2.6） |

---

## 一、范围与设计

- **做什么 / 不做什么**：见 lofi「做什么/不做什么」与 hifi 端点表/错误码表；规格行为 1+2 全部规则逐条落地，行为 6 仅承载"可见性=可否被检索"的读面最小集。
- **两级设计一并提交**（章程 2.6.3 ≤1 天卡）：lofi = 方向（端点清单/资格通道/权限双轨/Q 清单），hifi = 编码契约（端点契约表/错误码表/状态机与事务/测试计划/部署清单）。

**设计要点摘要（供快速表决）**

1. **7 端点最小完整面**：写面 6（创建/启用/冻结/恢复/解散/配置变更）+ 读面 2 最小集（列表=公开可检索+运营方全量、详情=公开摘要或成员全量）——可见性消费语义（Q6 裁决）归空间本体，为 3.2.6 界面供数；
2. **资格判定走 subject 内部端点**（沿 did→subject 同款 client 先例，零新增依赖）；subject 侧唯一改动 = 角色映射加 1 行 `space-internal: subject.internal.read`；不可达/失败 = UNAVAILABLE 统一文案，不冒充"未入驻"（防枚举同形，行为 1 规则 1）；
3. **双轨权限判定**：平台角色（`platform.operator` 治理档，角色头）+ 空间内角色（owner/admin，查 `space_member` 表）——3.2.4 收敛统一权限面（规格行为 4 规则 4"权限点命名随 3.2.4 定稿"）；
4. **状态机乐观门槛沿 subject 先例**（同事务 `UPDATE ... WHERE status=from_status`，0 行 → 状态门槛码）；解散 = **同事务三写**（status=DISSOLVED + `space_name_lock` 写入 + 该空间策略条目全部 ARCHIVED，3.2.2 hifi §2 契约兑现）+ 请求体必填 `confirmDissolve=true`（二次确认的 API 层强表达）；
5. **同名空间先后解散边界**（盘点发现的规格未定义路径）：跨 owner 同名活跃空间允许 → 先解散者锁定名称，后解散者 `name_lock` PK 冲突——采"已锁定即跳过"语义（锁定目标已达成，治理动作不被历史锁定阻断），探针固化；
6. **归一化新建实现**（3.2.2 移交①）：空白集显式定义含 Unicode 空白（U+3000 全角空格等），控制字符去除、首尾 trim——判重前提 `utf8mb4_0900_ai_ci`（移交②），探针含全角空格绕过尝试必被拒。

## 二、待确认决策点（请一次确认；章程 2.6.3）

> 全文与备选影响见 **lofi「待确认问题」节**；下表为摘要，**确认后 lofi/hifi 确认记录签署、进入编码**。

| 编号 | 问题 | 建议口径 |
| --- | --- | --- |
| **Q1** | 资格判定通道 | **A**：SubjectAdmissionClient 调 subject 内部端点（沿 did 先例）+ subject yml 加 1 行授权（唯一既有服务改动） |
| **Q2** | 读面边界 | **A**：最小读面 2 端点随本包（列表/详情，可见性消费语义归空间本体） |
| **Q3** | 解散二次确认形态 | **A**：请求体必填 `confirmDissolve=true`（API 层强表达；界面两段式归 3.2.6） |
| **Q4** | 平台运营方表达 | **A**：演示期角色头新增 `platform.operator` 档 + 空间内角色查成员表（双轨，3.2.4 收敛） |
| **Q5** | 配置变更字段范围 | **A**：仅简介与生效期可变更；名称/场景/参与方范围/可见性不可变更（规格未定义变更规则，如需开放走变更流程） |
| **Q6** | 同名先后解散边界 | **A**：`name_lock` 已锁定即跳过（锁定目标已达成），探针固化 |
| **D1** | 体量 / 是否拆分 | **A**：不拆分——预估 ~1100~1400 行，超 400 行指引（单服务单目标原子交付，沿 3.2.2 D1 先例） |

> **确认留痕**：（待编排师确认后回填）

## 三、规格行为 → 端点/规则/测试 映射表

| 规格行为（V1.0） | 承载端点/规则 | 关键测试 |
| --- | --- | --- |
| 行为 1 规则 1 资格门槛+防枚举 | 创建端点 + SubjectAdmissionClient（UNAVAILABLE 同形） | ADMITTED 通过；未入驻/不存在统一文案同形；服务不可达=UNAVAILABLE 不冒充未入驻 |
| 行为 1 规则 2 要素 | 创建请求体必填校验（名称/场景/参与方范围/可见性） | 缺要素 400 逐字段 |
| 行为 1 规则 3 命名唯一+归一化 | 归一化实现（空白集含 Unicode）+ `uk_owner_norm_name` | 同 owner 同名拒（含全角空格绕过尝试）；跨 owner 同名允许 |
| 行为 1 规则 4 留痕 | `space_action_log` CREATE 行四要素 | 留痕断言 |
| 行为 1 规则 5 幂等 | `@Idempotent(key=owner+归一化名)` | 重复提交同结果、空间数不变 |
| 行为 1 规则 6 初始态 | CREATED 不可接纳成员不生效策略（门槛归 3.2.4，本卡落状态初值） | 初始状态断言 |
| 行为 2 规则 1 状态机 | transition 乐观门槛（CREATED→ACTIVE⇄FROZEN→DISSOLVED 任一非终态可解散） | 全边正向+非法边拒绝+终态再动作拒绝 |
| 行为 2 规则 2 启用前提 | 要素完整+所有者仍 ADMITTED | 缺要素/资格失效拒绝 |
| 行为 2 规则 3 冻结 | freezing/unfreezing | 冻结恢复双向+留痕 |
| 行为 2 规则 4 解散 | dissolution：confirmDissolve 必填+三写事务+名称锁定 | 二次确认缺失拒绝；三写落库断言；同名先后解散边界 |
| 行为 2 规则 5 超权限拒绝 | owner/admin（成员表）+platform.operator（角色头）判定 | member/非成员/无角色逐动作拒绝+DENIED 留痕 |
| 行为 2 规则 6 留痕 | from_value→to_value+操作者 | 状态变更留痕断言 |
| 行为 6（可见性读面） | 列表/详情可见性过滤 | 公开可检索；不公开不出现；可见性≠可访问性注记 |

## 四、执行记录

| 项 | 内容 |
| --- | --- |
| 状态 | ⏳ **立卡中（2026-09-26）**：分支已建（自 `90d98c4`），三件套落盘，待编排师一次确认 Q1~Q6 + D1 |
| 立卡前素材盘点（只读） | ① WBS 行 253 + 规格 V1.0 行为 1/2/6 全文；② **资格通道实测**：subject `InternalAdmissionController`（`@RequirePermission("subject.internal.read")`）+ 授权映射在 subject `application.yml:49`（`did-internal: subject.internal.read`）→ space 复用 = 加 1 行；did `SubjectStatusHttpClient`（93 行，JDK HttpClient，UNAVAILABLE 语义）为 client 先例；③ **幂等先例**：`@Idempotent(key = "#orderNo")` SpEL（example/subject）；④ **状态机先例**：`SubjectStatusService.appendTransition` 同事务乐观门槛（3.1.3 教训固化）；⑤ **身份**：`AuthContext.subject()` + 角色头经 `AuthProperties`；⑥ **盘点发现**：跨 owner 同名空间先后解散撞 `name_lock` PK = 规格未定义真实路径（Q6 处置）；⑦ 部署清单现仅 example+frontend（`deploy/k8s/`），space 入列随本包 |
| 规格外实现声明 | 无（端点/规则全部由规格行为 1/2/6 派生；读面边界 Q2、二次确认形态 Q3、运营方表达 Q4 均为规格授权范围内的实现形态决策） |
| 复用声明 | 复用 subject 内部端点（ADR-016 §6 衔接契约）、did client 先例形态、subject 状态机乐观门槛模式、common 幂等/鉴权/分页/错误码、3.2.2 六表与 domain 模型；**未新增第三方依赖**（client = JDK HttpClient） |
| 体量登记 | 预估 ~1100~1400 行（见 D1；确认后以实际 `wc -l` 重测回填） |

---

> **边界复述（红线自查）**：本卡不动门禁配置、不引新依赖；既有服务唯一改动 = subject yml 1 行授权（ADR-016 §6 补记留痕）；成员域与策略域零触碰；错误码仅 1006 段（码值随本卡 `SpaceErrorCodes` 定稿，不动其他段）。
