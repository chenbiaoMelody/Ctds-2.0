# WBS-3.8.3 任务卡：全链路存证埋点（2026-11-06）

| 项 | 内容 |
| --- | --- |
| 来源 | 台账 `docs/status/进度看板与债务登记簿.md` **行 17 / 行 19**"下一关口 = 下一包立卡指示（候选 = WBS-3.8.3 全链路存证埋点，WBS 行 329〔实测应为 328〕；立卡裁定权归编排师）" + 开发日志 `docs/logs/Ctds-项目开发日志-26-11-06-2158.md` 续点第 1 项；**编排师 2026-11-06 会话指示"读取最新项目日志，立卡WBS-3.8.3 全链路存证埋点"** |
| 级别与性质 | L1-3 平台功能**实施包 + 非界面类**（WBS 行 328，预算 **1 会话**）；章程 2.6.3 非界面类两级设计 = 业务可读行为清单 + 接口契约表 + 边界值与异常行为，`docs/designs/WBS-3.8.3-lofi.md` + `-hifi.md` 同批 **V0.9** 提交、2026-11-06 一次确认转 **V1.0**，**免 HTML 原型**。**零前端改动**；**零新第三方 Maven 依赖**（新增 1 个平台自有 `common` 模块，根 pom 20→21）；**零 Flyway 迁移** |
| 需求锚点核对 | WBS `docs/C-TDS项目总体实施计划与WBS.md` **行 328** 原文"全链路存证埋点 \| 授权/合约/交付/使用四环节存证事件接入 \| 3.8.2 \| 1"（依赖 3.8.2 ✅ 2026-11-06 合并 `b472500`）；下游 3.8.4~3.8.6（行 329~331）本卡零触碰；PRD V1.0 行 164（C-8.1"授权、合约、交付、使用全链路上链存证"，P0）；规格 `docs/specs/C-8.1-8.2-存证与审计.md` **V1.1**：**B1-1**（一动作一事件 / 六要素）/ **B1-2**（环节四类值域封闭）/ **B1-3**（只消费上游事件源、单据号逐字一致、拒绝不产生存证 = Q10）/ **B1-4**（上链白名单）/ **B2-1**（解耦 + fail-visible）/ **B2-2**（幂等）+ **§4-2**（四环节埋点归 3.8.3）+ **§5**（C-2.x / C-4.x / C-5.x / C-7.x 上游均闭环）+ **§6-8**（1012 段已由 3.8.2 占用）；剧本 `docs/manuals/C-8.1-区块链存证-验收剧本.md` **V1.2**（S1 八步 / S2 九步；S1-1~S1-5 现以"内部写面构造"，真实埋点归本卡） |
| 上游能力边界（立卡实测） | ① **存证侧已就绪**：内部写面 `POST /api/v1/evidence/internal/records` + 权限点 `evidence.internal.write`（`EvidenceInternalController.java:29-34`）；六类操作类型与环节强绑定（`EvidenceOperationType.java:10-15,28-39`）；`businessNo` `^[A-Za-z0-9_.:-]{6,64}$`（`EvidenceRules.java:18`）；幂等唯一键 `uk_evidence_idem(stage_type,business_no,operation_type)`（`V1__create_evidence_tables.sql:33`）；**全仓调用方引用 = 0**（唯一调用方是 evidence 自身与其测试）。② **授权（空间）**：成员生效唯一写入口 `SpaceAdmissionService.activate`（`:271-279`）→ `SpaceJdbcRepository.activateMembership`（`:329-370`）；`space_admission` / `space_member` **无业务单号列**（仅自增 id），留痕表 `space_action_log` 有 `id`；策略覆盖 `SpacePolicyService.submitOverride`（`:119-159`），**"继承"是读时取严解析（`EffectivePolicyResolver` 纯函数），无批量继承写动作**；`space-service` **零 DID 引用、零 `AuditRecorder` 调用**。③ **合约**：`contract_attestation`（V2:96-110，唯一键 `contract_id`）+ `findAttestation`（`JdbcContractRepository.java:139-147`）；生效链 `ContractOrderSyncService.signAndSync`（`:41-50`）→ `JdbcContractRepository.sign`（`:254-296`，`:291-295` 写存证行）；`contract_no` 为 `CO+6 位`；双方 DID 齐备；**公共数据授权类识别需 JOIN `contract_template.template_type`**（`contract` 表无该列）；`contract-service` **零 `AuditRecorder` 调用**。④ **交付**：`DeliveryCommandService.create`（`:88-196`，落库 `:175`、审计 `:193`）；`delivery_no` = `DL%06d`；**`delivery` 表无 DID 列**，`InternalContractSummaryViews.Summary` 仅 7 字段、不含 DID；`DeliveryAuditService` 有现成 AuditRecorder 出口但**吞异常**（与存证 fail-visible 语义不同，不可直接复用）。⑤ **使用**：放行判定唯一权威 `PolicyExecutionService.check`（`:68-127`，allow 分支 `:91` / `:125`）；`contract_usage_log` **无业务单号列、无唯一键**（V3:17-29）；计量流水 `metering_flow` 与放行**不同步、不同源、无关联键**（API 腿 = 网关事件 `eventId`；FILE/沙箱腿 = 交付号经批量采集；演示执行端点无流水）；拒绝分支 `:81-83` / `:100-101` / `:113-116` 全部 `insertDenied`（REQUIRES_NEW），与 Q10 一致。⑥ **DID**：唯一权威源 `did_identity`；内部端点 `GET /api/v1/did/internal/subjects/{subjectNo}/current` + 权限点 `did.internal.read`（`InternalCurrentDidController.java:22-41`），**当前仅 `connector-internal` 持有**（`did/application.yml:41`）；返回 `CurrentDidView(subjectNo,did,status)`。⑦ **跨服务调用范式**：JDK `HttpClient`（connect 2s / read 3s）+ `X-Ctds-Subject` / `X-Ctds-Roles` 头（`ContractInternalClient.java:33-49`）；**全仓零 `TransactionSynchronizationManager` / `@TransactionalEventListener` / outbox 先例**；`common/logging` 提供 `AutoConfiguration.imports` 自动装配范式（`LoggingAutoConfiguration.java:19-42`）。⑧ **门禁/构建**：根 pom `<modules>` 实测 **20**；`gates-config.json` `coreModules` = 5 项（不含 evidence）；前端零改动；三服务测试类基线 = space **16** / contract **25** / delivery **20** |
| 交付物 | ① **新增 `common/evidence-client` 模块**（pom〔parent `ctds-platform` 2.0.0-SNAPSHOT，`relativePath=../../pom.xml`〕+ `EvidenceEvent` / `EvidenceRecorder` / `EvidenceClientProperties` / `EvidenceClientAutoConfiguration` + `AutoConfiguration.imports`）+ 根 pom 注册与 `dependencyManagement`（**20→21**）；② **空间服务**两处埋点（成员准入 / 策略生效）+ `DidInternalClient` + `did` 服务角色映射新增 `space-internal: did.internal.read`；③ **合约服务**两处埋点（合约生效〔含公共数据授权双写〕/ 使用放行）+ `contract_usage_log` 插入回传自增 id + 模板类型只读查询 + 请求方 DID 只读查询；④ **交付服务**一处埋点（交付创建）+ 合约内部摘要新增 `requesterDid`（向后兼容）；⑤ 三服务 `application.yml` 客户端配置 + 三份 k8s deployment 环境变量（`CTDS_EVIDENCE_BASEURL`；space 另加 `CTDS_SPACE_DID_BASEURL`）；⑥ 剧本 C-8.1 **V1.3**（真实业务触发路径 + S1-2/S1-5 单据号口径对齐；业务判定与步骤编号一字不改，提示 PO 批准）；⑦ evidence-service **测试补强**（DB-113 P3-4：六类操作类型正向用例，仅测试、零主源改动）；⑧ 复用声明 + 规格外实现声明（应为空）+ 台账 / 开发日志 / 体量登记 + 业务可读交付说明 |
| 关闭条件 | ① **✅ 2026-11-06 达成**——两级设计经编排师**一次确认**（Q1~Q10 均采建议口径 A + D1 不拆分）→ lofi / hifi 同批转 V1.0 = 编码契约即时生效；② 编码段：`common/evidence-client` + 三服务 `test` 全绿（新增埋点用例 T1~T26 覆盖四环节正向 / 拒绝零事件 / 回滚零事件 / 幂等 / DID 缺失 / 存证侧不可达 / 事务后置）+ 全仓 `compile` **21 模块** SUCCESS + `checkstyle:check` 0 + evidence-service 既有 40 例零回归 + gateway 47 例零回归 + `gates-config.json` 零改动（ConfigSha256 与基线逐字一致）+ 前端四步 EXIT=0（零改动兜底）；③ 规格 B1-1 / B1-2 / B1-3 / B1-4 / B2-1 / B2-2 / Q10 → 四环节真实业务触发 → 剧本 S1-1~S1-8 映射齐备（§三）；④ 剧本 C-8.1 **V1.3** 经 PO 批准并随验收生效；⑤ 4 视角评审循环 ≤3 轮 + 独立复审 PASS（零未处置 P1/P2）；⑥ 零越界：**零 Flyway 迁移 / 零前端 / 零新第三方依赖 / 零门禁配置**；不改 evidence 内部写面契约（3.8.2 冻结）；不实现拒绝调用存证（Q10）；不做审计视图（3.8.4）/ 界面（3.8.5）/ 压测（3.8.6）/ 真链（DB-115）；⑦ 编排师验收通过（"验收通过" ≠ 合并授权） |
| 分支 | `feat/WBS-3.8.3-全链路存证埋点`（**自本地 main 梢 `4db19a1` 创建** = 3.8.2 合并段文档批；该批随本卡 PR 一并入库，沿链式先例；`origin/main = b472500` 未动） |
| 设计文件 | `docs/designs/WBS-3.8.3-lofi.md`（**V1.0 已确认 = 方向定稿，2026-11-06**）/ `docs/designs/WBS-3.8.3-hifi.md`（**V1.0 已确认 = 编码契约即时生效，2026-11-06**）——非界面类，免 HTML 原型 |
| 纪律声明 | **只做四环节埋点接入**——不做审计视图 / 报告（3.8.4）、不做界面（3.8.5）、不做四环节完整性联调与压测（3.8.6）、不做真链（DB-115）；**存证与业务解耦 + fail-visible**（业务事务提交后才调用；调用失败不回滚业务、不静默——WARN 结构化日志 + 跟踪项）；**拒绝调用一律不产生存证事件**（Q10，含空间红线拒绝 / 合约判定拒绝 / 交付失败 / 首签未生效）；**不自建密码学、不新增业务单据号列、不改上游表结构**；**不修改 evidence 内部写面契约**；**不引入未审批依赖**；**发现规格缺口走变更流程**（本卡 Q3 / Q5 / Q9 属口径确认项，确认后才实施）；发现冲突或疑似 S0 → 立即停止上报（红线 4） |

---

## 一、范围与设计

- **做什么 W1~W8 / 不做什么 N1~N9**：见 `docs/designs/WBS-3.8.3-lofi.md` §1；
- **结构组成**：lofi §2；**主要流程（成员准入 / 策略生效 / 合约生效 / 交付 / 放行 / 失败跳过）**：lofi §3；**决策点（待确认）**：lofi §4（Q1~Q10 + D1）；**体量**：lofi §5（预估 **1400~1800 行**，1 个编码会话）；
- **编码契约**（客户端契约 / 事件源与字段规则 / 各服务改动清单 / 事务与幂等 / DID 与权限 / 配置部署 / 前置检查 / 测试锚点 / 剧本映射与诚实边界）：见 `docs/designs/WBS-3.8.3-hifi.md` §1~§10。

### 立卡前素材盘点（只读；2 个独立子智能体 + 主会话定点补测 12 项）

| 项 | 结论 |
| --- | --- |
| ① WBS 锚点 | 行 **328** 原文核对（四环节存证事件接入；依赖 3.8.2 ✅；预算 1 会话）；行 329~331 下游边界 = 视图 / 界面 / 测试集成，本卡零触碰。**补正**：日志 `-2158` 续点写"WBS 行 329"，实测行 328（3.8.4 = 行 329） |
| ② 规格锚点 | C-8.1-8.2 V1.1 B1-1（一动作一事件 + 六要素）/ B1-2（四类值域）/ B1-3（只消费上游 + 单据号逐字 + Q10 拒绝不产生）/ B1-4（白名单）/ B2-1（解耦 + fail-visible）/ B2-2（幂等）；§2 术语"四环节 / 内容摘要"；§4-2 归口；§5 上游闭环表 |
| ③ 剧本锚点 | C-8.1 V1.2：S1-1~S1-5 现以内部写面构造（本卡改真实业务触发）；S1-5 括号写"业务单据号 = 计量流水源单号"（**与本卡 Q3 存在口径冲突，需裁决**）；S1-6 幂等 / S1-7 白名单 / S1-8 编号；S2 九步由 3.8.2 全承载 |
| ④ 存证侧核查 | 内部写面 / 六类操作类型 / 三元组唯一键 / 三态状态机 / 1012 五码均已就绪；**调用方 0 引用**；响应恒 200（链失败为 `FAILED`）；`duplicate=true` 幂等 |
| ⑤ 授权侧核查 | 成员准入：`activate`（单事务三步）→ `activateMembership`；**无业务单号列**；策略：`submitOverride` 仅覆盖提交，**继承 = 读时解析**；**无 DID、无 AuditRecorder**；`space_action_log.id` 可作留痕锚点（需让插入回传 id） |
| ⑥ 合约侧核查 | `contract_attestation`（合约哈希 + 双方 DID + 双方签署时点，一合约一行）；生效链完整；`contract_no` 可逐字透传；公共数据授权类需 `contract_template.template_type` JOIN；**零 AuditRecorder**；首签未生效不产生存证 |
| ⑦ 交付侧核查 | `delivery_no`（`DL%06d`）可逐字透传；创建落库与留痕同事务；幂等重放提前返回；**无 DID**；合约内部摘要不含 DID；`AuditRecorder` 有出口但吞异常，不可复用 |
| ⑧ 使用侧核查 | 放行判定 `PolicyExecutionService.check` 唯一权威；放行留痕与计数同事务；**`contract_usage_log` 无单号 / 无唯一键**；计量流水与放行不同步、不同源、无关联键（API 腿网关事件 / FILE 腿批量采集 / 演示端点无流水）；拒绝留痕 REQUIRES_NEW、零计量 |
| ⑨ DID 与权限 | `did.internal.read` 仅 `connector-internal` 持有；`CurrentDidView(subjectNo,did,status)`；空间/交付需新增授权或改用本域数据 |
| ⑩ 调用范式 | JDK `HttpClient` + 服务身份头；**零 afterCommit / outbox 先例**；`common/logging` 有自动装配范式；测试用 JDK `HttpServer` 桩、无 MockWebServer 依赖 |
| ⑪ 门禁与依赖面 | 零新第三方依赖；根 pom 20→21（新增平台自有 common 模块）；`gates-config.json` 零改动；`coreModules` 5 项不含 evidence → 覆盖率口径仍为整体 70% |
| ⑫ 测试与体量面 | 基线：space 16 / contract 25 / delivery 20 个测试类；evidence 40 例；gateway 47 例；前端四步零改动兜底；本卡新增 T1~T26（约 28~36 例）；预估 1400~1800 行 |

### 设计要点摘要（供快速表决）

1. **一个客户端 + 三服务复用**：新增 `common/evidence-client`（`EvidenceRecorder`），业务侧只在事务提交后调用一次；换存证实现只改客户端；
2. **事件源只选上游权威点**：成员准入 = `activate` 成功；策略 = `POLICY_OVERRIDE` 成功；合约 = `contract_attestation` 落库；交付 = `delivery` 落库；使用 = `contract_usage_log` 放行行；
3. **单据号可反查**：合约 / 交付逐字透传上游单号；授权 / 使用以上游留痕行主键派生可读键（Q2 / Q3），不新增第二套编号体系；
4. **DID 分域取源**：合约 / 使用 = 本域 `contract_attestation`；交付 = 合约内部摘要新增 `requesterDid`；空间 = `did.internal.read` 内部端点（Q6）；
5. **事务后置 + fail-open**：`afterCommit` 触发，证据服务不可达不回滚业务、不静默（WARN + 跟踪项）；无 MQ / outbox / 定时任务（Q7）；
6. **拒绝零事件**：红线拒绝 / 首签未生效 / 判定拒绝 / 交付失败 / 同值幂等均不调用（Q10 口径）；
7. **幂等双保险**：客户端重放由存证侧三元组唯一键兜底（`duplicate=true`），重复交付 / 重复签署不产生第二条；
8. **诚实边界**：调用侧网络不可达窗口无补偿队列（跟踪项）；真链未接入（DB-115）；策略"继承"以覆盖生效动作承载（Q4）。

## 二、决策点与确认留痕（待编排师一次确认；章程 2.6.3）

> 决策点全文与业务影响见 `docs/designs/WBS-3.8.3-lofi.md` §4；下表为摘要。

| 编号 | 问题（业务语言） | 建议口径 |
| --- | --- | --- |
| **Q1** | 埋点客户端放在哪里？三个业务服务都要用同一套"提交存证"能力 | **A**：新增平台自有 `common/evidence-client` 模块（一处实现、三服务复用，符合横切能力收敛；根 pom 20→21，零新第三方依赖）；B：塞进 `common-logging`（不新增模块，但存证语义与日志混淆，且 space/contract 需新增对 logging 的依赖）；C：三个服务各写一份（违反收敛规则，三份维护） |
| **Q2** | 授权环节上游没有业务单号（`space_admission` / `space_member` 只有自增 id），存证业务单据号怎么定？ | **A**：以"上游留痕行主键"派生可读键——成员准入 `SPACE{空间id}:ADMISSION{准入单id}`（可反查 `space_action_log.target_id`）、策略生效 `SPACE{空间id}:POLICY-AL{留痕行id}`（需让 `space_action_log` 插入回传自增 id）、授权类合约 `contract_no`（逐字一致）；零迁移、零新编号体系；B：给上游表新增业务单号列 + 取号（3 张迁移 + 第二套编号，违反 B1-3"不新增第二套业务口径"）；C：UUID（不可反查，审计无法定位） |
| **Q3** | 使用环节的存证业务单据号：剧本 S1-5 现在写"计量流水源单号"，但放行时点拿不到（API 腿由网关生成、FILE 腿后置采集、演示端点无流水） | **A**：以"使用放行留痕单号"`USG{12 位 usage_log id}`承载，**剧本 C-8.1 转 V1.3 时把 S1-5 括号说明改为"使用放行留痕单号"**（"逐字一致"判定不变）；B：改以计量流水源单号为事件源 → 埋点须落到网关 + 计量采集 + 合同三处，异步延迟、演示端点覆盖缺口（扩卡）；C：两处都埋（同一动作两条存证，违反 B1-1 / B1-3） |
| **Q4** | 规格说"空间策略继承"，但代码里的"继承"是读时取严解析，没有批量继承写动作 | **A**：以"空间策略覆盖提交成功（`POLICY_OVERRIDE`）"承载 `SPACE_POLICY_INHERITED`（一次一条；同值幂等不产生事件；操作类型名沿用 3.8.2 已定枚举）；B：新增"空间启用时批量继承平台条目"动作（新业务行为 + 批量留痕，超出本卡，需规格变更）；C：不埋该操作类型（授权环节覆盖缺口，剧本 S1-2 只能验成员准入） |
| **Q5** | 一份"公共数据授权类合约"生效，是只算合约环节，还是授权 + 合约两个环节都要有存证？ | **A**：**双写两条**——`AUTHORIZATION/DATA_AUTH_CONTRACT_EFFECTIVE` + `CONTRACT/CONTRACT_EFFECTIVE`，同一 `contract_no`、不同环节/操作类型（幂等键含操作类型，结构不冲突；B1-1"一条业务动作一条事件"按"每环节每动作一条"解释）；B：只落合约环节（授权环节对授权类合约缺覆盖，违反 B1-3 事件源定义）；C：只落授权环节（合约环节缺覆盖，剧本 S1-3 失败） |
| **Q6** | 空间与交付服务没有 DID，主体标识从哪来？ | **A**：合约 / 使用 = 本域 `contract_attestation` 双方 DID（零权限变更）；交付 = 合约内部摘要新增 `requesterDid`（同域扩展、向后兼容）；空间 = 调 `did` 内部端点（给 `did/application.yml` 新增 `space-internal: did.internal.read` 映射）；DID 非 ACTIVE / 取不到 → **跳过该条存证 + WARN + 登记缺口**（不伪造）；B：空间 / 交付都调 did（交付多一处权限变更与新客户端）；C：用 `subjectNo` 冒充 DID（不符合 `did:ctds:` 值域，语义错误，不可采） |
| **Q7** | 存证调用放在业务事务里还是提交后？失败怎么办？ | **A**：`afterCommit` 触发（无事务时立即），同步 HTTP（connect 2s / read 3s）；**失败不抛业务**，WARN 结构化日志（只含环节 / 单号 / 操作类型，不含 DID 与原文），并登记"调用侧不可达窗口"跟踪项；无 MQ / outbox / 定时任务；B：每服务加 outbox 表 + 定时补偿（强一致，但 4 个服务库改造 + 调度，显著超最小实现，建议另立卡）；C：事务内同步调用或失败回滚业务（可能产生"业务回滚但存证已发"的假证据 / 违反 B2-1 解耦） |
| **Q8** | 埋点开关与默认地址怎么定？ | **A**：`ctds.evidence.client.enabled` 默认 `true`；`base-url` 默认**空 = 跳过 + WARN**，由部署清单显式注入 `CTDS_EVIDENCE_BASEURL`（测试默认不打网络，避免既有用例被 2s 超时拖慢）；B：默认 `http://127.0.0.1:8090`（漏配也能用，但所有既有集成测试会真实打网络）；C：不设开关（无法按环境关闭） |
| **Q9** | 验收剧本要不要改？ | **A**：C-8.1 转 **V1.3**——S1-1~S1-5 改为"四环节业务自动触发"的操作路径；S1-2 空间准入键补派生规则；S1-5 单据号口径按 Q3 对齐；**业务判定与步骤编号一字不改**，提示 PO 批准；B：不改（S1-5 的"计量流水源单号"无法执行）；C：重写（超范围） |
| **Q10** | 3.8.2 留下的跟踪项怎么处置？ | **A**：① **DB-113 P3-4**（授权三类 / 使用一类操作类型无正向用例）由本卡在 evidence-service **补 4 条测试**顺带闭合（仅测试、零主源改动）；② DB-115 真链不动；③ DB-114（循环 2 的 R2-3~R2-8）不承接；④ 新登记"调用侧不可达窗口（无 outbox）"跟踪项；B：在本卡消化 DB-113 / DB-114 全部（超埋点范围，扩卡）；C：不处置（P3-4 继续挂账） |
| **D1** | 体量 / 是否拆分 | **不拆分**：预估 **1400~1800 行**、1 个编码会话（WBS 行 328 = 1 会话）。四环节是同一验收链（剧本 S1 一次跑通），拆则产生"授权有存证、交付无存证"的中间态不可验收；超出 400 行按服务包先例登记制豁免 |

> **✅ 确认留痕（2026-11-06 编排师会话回复"全部按建议确认"）**：**Q1~Q10 均采建议口径 A + D1 不拆分**——Q1 新增 `common/evidence-client` / Q2 授权环节以留痕主键派生 businessNo / Q3 使用环节以放行留痕单号 `USG{12 位}` 承载（剧本 S1-5 同步改口径）/ Q4 空间策略"继承"以覆盖生效承载 / Q5 公共数据授权类合约双写（授权 + 合约各一条）/ Q6 DID 分域取源（空间调 did 内部端点 + `space-internal: did.internal.read`；交付经合约摘要扩字段）/ Q7 afterCommit 触发 + 失败仅告警（无 outbox）/ Q8 默认空地址 = 关闭 + 部署显式注入 / Q9 剧本 C-8.1 转 V1.3 / Q10 顺带闭合 DB-113 P3-4 + 新登记"调用侧不可达窗口" / **D1 不拆分**（1400~1800 行，1 个编码会话）。→ ① lofi / hifi 同批转 **V1.0（编码契约即时生效）**；② 关闭条件① / 设计文件行 / §四 确认段 / §五 体量回填；③ 台账行 17 / 19 转态；④ 确认批提交并**推送 origin（分支首推）**。**下一关口 = 编码段**（新会话冷启动，测试先行，第一动作 = hifi §7 前置检查 12 项逐项实测留痕）。

## 三、规格行为 → 本卡承载 → 剧本步骤 映射表

| 规格承载（V1.1） | 本卡落点（事件源 / 单据号 / DID） | 剧本步骤 | 测试锚点 |
| --- | --- | --- | --- |
| B1-1（一动作一事件 + 六要素） | 五个业务提交点各调用一次；编号 / 状态 / 摘要由 3.8.2 生成 | S1-2~S1-5 / S1-8 | T1 / T2 / T5 / T6 |
| B1-2（环节四类值域封闭） | 沿用 3.8.2 六类操作类型，不新增值 | S1-2~S1-5 | T2 |
| B1-3（只消费上游事件源、单据号逐字一致） | 事件源 = 上游事务内的权威写点；合约 / 交付逐字透传；授权 / 使用按 Q2 / Q3 派生 | S1-2~S1-5 | T1 / T3 / T4 |
| B1-4（白名单 / 禁原文） | 客户端只发六字段（环节 / 单号 / 操作类型 / DID / 时点 / 操作人），无自由文本 | S1-7 | T7（负向字段扫描） |
| B2-1（解耦 + fail-visible） | `afterCommit` 触发；调用失败不抛、WARN；链失败由 3.8.2 `FAILED` 承载 | S2-4 | T8 / T9 |
| B2-2（幂等） | 存证侧三元组唯一键；重复交付 / 重复签署 / 重放不产生第二事件 | S1-6 | T10 / T11 |
| Q10（拒绝不产生存证） | 红线拒绝 / 首签未生效 / 判定拒绝 / 交付失败 / 同值幂等 → 零调用 | S1-5 | T12 / T13 |
| S1-1 / S1-8（读面与编号） | 3.8.2 治理读面（本卡零改动） | S1-1 / S1-8 | 复用 3.8.2 |
| S1-2 授权类合约 | `AUTHORIZATION/DATA_AUTH_CONTRACT_EFFECTIVE` + `CONTRACT/CONTRACT_EFFECTIVE` 双写（Q5） | S1-2 / S1-3 | T3 / T4 |

## 四、执行记录

| 项 | 内容 |
| --- | --- |
| 立卡段（2026-11-06） | **立卡完成**：素材盘点（2 个独立只读子智能体 + 主会话定点补测 12 项）→ 任务卡（本卡）+ `lofi V0.9` + `hifi V0.9`（均待一次确认）；分支 `feat/WBS-3.8.3-全链路存证埋点` 自本地 main 梢 `4db19a1` 开立（3.8.2 合并段文档批随本卡 PR 入库）；**零代码、零迁移、零新第三方依赖、零门禁配置改动**，未动 `main`。下一关口 = 两级设计一次确认（Q1~Q10 + D1）。 |
| 确认段（2026-11-06） | **✅ 编排师会话回复"全部按建议确认"** = **Q1~Q10 均采建议口径 A + D1 不拆分** → ① `lofi` / `hifi` 同批转 **V1.0（编码契约即时生效）**；② 关闭条件① / 设计文件行 / §二 确认留痕 / §五 体量回填；③ 台账行 17 / 19 转态；④ 确认批提交并**推送 origin（分支首推）**。**本段零代码 / 零测试 / 零迁移 / 零新第三方依赖 / 零门禁配置改动，未动 `main`。** 下一关口 = **编码段**（新会话冷启动；测试先行；第一动作 = hifi §7 前置检查 12 项逐项实测留痕；红相归档 `build-output/w383-red-*`）。 |

## 五、体量登记

| 项 | 数值 / 说明 |
| --- | --- |
| 实测（立卡段） | 任务卡（本卡）**79 行**；lofi **107 行**；hifi **243 行** → 合计 **429 行**（非空行口径；物理行 97 + 131 + 301 = 529）；无代码、无测试 |
| 实测（确认段） | **零代码 / 零测试 / 零依赖 / 零迁移 / 零门禁配置**；lofi / hifi 版本头转 V1.0 + 任务卡四处回填 + 台账两行与变更记录 + 开发日志 1 份；仓库口径文档变更约 **14 行**。 |
