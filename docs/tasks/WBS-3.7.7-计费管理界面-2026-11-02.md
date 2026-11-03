# WBS-3.7.7 任务卡：计费管理界面（2026-11-02）

| 项 | 内容 |
| --- | --- |
| 来源 | 台账 / 最新日志续点「下一包立卡候选 = WBS-3.7.7 计费管理界面」（`docs/logs/Ctds-项目开发日志-26-11-02-2315.md` §下一步第 1 项）；编排师 2026-11-02 会话指示「读取最新项目日志，立卡 WBS-3.7.7」→ 按流程推进 **WBS-3.7.7 立卡段**；冷启动承接 3.7.6 全流程闭环（PR #26 MERGED `b538414`；收口批 `d9c0722` + 回填批 `771afe6` + 勘正批 `8603098` = **本地 main ahead 3**，随本卡 PR 入库——沿链式先例）。 |
| 级别与性质 | L1-3 平台功能**实施包 + 界面类**（WBS 行 319，预算 **1 会话**；章程 2.6.3：界面类两级设计 = **静态 HTML 原型 + 界面说明书**，≤1 天 lofi + hifi 同批 **V0.9** 提交、一次确认转 V1.0）。**前端增量为主**（2 菜单 + 2 深链 + 4 视图 + 5 子组件 + `api/billing.ts` + `constants/billing.ts` + 演示身份扩展 + vite 转发 2 键）+（Q3-A）后端 **1 个加性只读端点**（计量全量流水治理读面）；**零新 npm / Maven 依赖、零迁移、零新权限点、零新错误码、零新容器、零门禁配置改动**。 |
| 需求锚点核对 | WBS `docs/C-TDS项目总体实施计划与WBS.md` **行 319**（`计费管理界面 \| 账单管理/对账/费率配置页面 \| 3.7.6 \| 1`——依赖 3.7.6 ✅ 已合并闭环；预算 1 会话；三词映射口径见 **Q2**，须编排师确认）+ 行 320（3.7.8 测试与集成为下游）。规格 `docs/specs/C-7.1-7.2-门户与计费.md` **V1.0**：**B9**（四档计费口径的界面呈现——费率视图与账单快照）/ **B10**（账单生成双轨、编号、不可篡改与更正——界面承载出账 / 确认 / 更正，不含自动出账配置）/ **B11**（对账四方交叉验证、双向断言、fail-closed 冻结、全程留痕——界面承载执行与差异展示）/ **B12**（读面：需方 / 供方查本方账单与对账结论；运营全量 + 手动出账 / 对账；非本方不可读；**B12-2 的"运营全量计量流水"在本卡落地**——落地注记 202-205 行明文归 3.7.7）/ **B4-2**（运营方全量订单检索——C-7.1 剧本 S2-7 界面明文归 3.7.7）+ **§4 非目标 3**（计费管理界面归 3.7.7）+ **§5 依赖表** + **§6-2**（不改上游端点 / 表 / 错误码）。ADR-005（统一响应 / 九位错误码 / 分页）；ADR-016 §2.7（演示期仅本机可达，身份头信任边界）；ADR-018（core 模块 80% 覆盖；前端不计覆盖率）；ADR-001（技术栈冻结：Vue 3 + Element Plus + Vite，零新框架）；ADR-006 §3-5（1011 段已定稿，本卡零新码）。剧本 `docs/manuals/C-7.2-计量计费中心-验收剧本.md` **V1.2**（S2 出账幕 11 步 + S1-5 免费档补验）+ `docs/manuals/C-7.2-账单核对-验收剧本.md` **V1.1**（S1 正向 3 步 + S2 差异处置 5 步 + 附录 A 差异构造）+ `docs/manuals/C-7.1-数据产品门户-验收剧本.md` **V1.3**（S2-7 运营订单检索——界面归 3.7.7）。台账 **DB-103**（3.7.6 评审与裁定承接批五项，本卡立卡时逐项分解——见 §一 素材盘点⑫）。 |
| 上游能力边界 | **3.7.6 全链闭环（`b538414`）**：`services/metering-billing` 计费八端点（`B-W1` `POST /bills`、`B-R1` `GET /bills/mine`、`B-R2` `GET /bills/{billNo}`、`B-R3` `GET /bills/governance`、`B-W2` `POST /bills/{billNo}/confirm`、`B-W3` `POST /bills/{billNo}/corrections`、`B-R4` `GET /contracts/{contractNo}/reconciliations`、`B-W4` `POST /contracts/{contractNo}/reconciliations`），权限点 `billing.read` / `billing.governance`（50→52），错误码 1011C0009~C0015，**B-R2 支持 admin 读任意账单**（`BillingBillController.java:79-84` `queryService.detail(billNo, subject, isAdmin())`）、**B-R4 支持 admin 读任意合约**（`BillingReconcileController.java:50-62`），响应字段见 3.7.6 hifi §3.4（金额字符串）。**3.7.5 计量读面**：`R-M0` `POST /metering/collect`（governance）、`R-M1` `GET /metering/contracts/{no}/flows`（read，归属硬校验，非当事方含 admin 一律 404 1011C0008）、`R-M2` summary、`R-M3` `GET .../reconcile`（governance，四方勾稽读数）；`metering_flow` 列面 = `id / contract_no / requester_no / source_type / source_no / action / resource / occurred_at / created_at`，`uk(source_type, source_no)`；`MeteringSourceType` 四值 `GATEWAY_API / FILE_DELIVERY / SANDBOX_EXPORT / SANDBOX_JOB`；`common-pagination` 已引入（3.7.6）。**3.7.3 订单治理读面**：`GET /api/v1/orders/governance`（`contract.governance`，参数 status / productId / contractNo / requesterNo + 分页；`OrderQueryController.java:58-74`）——**零后端改动**；`R-OR2` 订单详情**仅当事方**（admin 亦 404，运营订单详情不可用 → hifi E0-2）。**前端现状实测**：`src` 六目录（`api / constants / layouts / router / stores / styles / views`），无 `components/` 顶层；路由单文件 38 条记录（36 named）、菜单 **23 项**（`MainLayout.spec` 断言普通 10 / admin 23）；`api/` 12 源文件**无 billing**；`client.ts` 原生 fetch + `makeDomainJson` + `X-Ctds-Subject` / `X-Ctds-Roles`（**无 JWT**）；错误文案按域常量内嵌（无全局映射表）；**无按钮级权限点**；`vite.config.ts` 16 个转发键**缺 `/api/v1/billing` 与 `/api/v1/metering`**（会误落兜底 `/api` → 8080）；测试基线 **58 文件 749 例**（vitest + @vue/test-utils + jsdom，fetch 以 `vi.stubGlobal` mock）；`PortalSourceGuard.spec.ts:111-125` 现有"计费 / 账单 / 支付全域禁用"静态断言（本卡须按开放口径更新，见 hifi §9 T15 / E0-5）。**门禁**：`gates-config.json` coreModules 含 metering-billing（80% 行覆盖）；本卡预计零配置改动（ConfigSha256 基线 `955bdbba…2ee2`）。 |
| 交付物 | ① **运营「计费管理」页**（`/billing`，menuOrder 24，`billing.governance`）= 五 Tab：**账单管理**（B-R3 全量列表 + 七维过滤 + 分页 +「手动出账」弹窗 B-W1）/ **对账工作台**（B-W4 执行 + 四方数值 + 差异明细 + B-R4 记录列表）/ **计量流水**（**R-M4 新增端点**：全平台逐条 + 合约 / 主体 / 源类型 / 时间过滤 + 分页）/ **订单检索**（R-OR3 既有端点，零后端改动；C-7.1 S2-7 界面入口）/ **费率视图**（四档口径只读 + 快照来源 + 「前往产品上架页」指路，Q2）；② **运营账单详情深链** `/billing/bills/:billNo`（B-R2 admin 读 + 快照 / 明细 / 明细合计对照 / 动作留痕 / 更正链 + 确认 B-W2 + 更正 B-W3）；③ **门户「我的账单」**（`/portal/bills`，menuOrder 25，登录可见）+ 详情深链 `/portal/bills/:billNo`（B-R1 / B-R2 本方；零写动作；`myRole` 中文）；④ `api/billing.ts`（9 函数 + spec）+ `api/order.ts` 追加 `listGovernanceOrders` + `constants/billing.ts`（状态 / 类型 / 定价档 / 结论 / 七类差异 / 留痕动作 / 源类型 / 角色 / 提示文案）+ `stores/demoIdentity.ts` 新增 `billingRolesHeader()`（运营 `admin` / 普通 `requester,provider`）+ `router/index.ts`（2 菜单 + 2 深链）+ `vite.config.ts`（`/api/v1/billing`、`/api/v1/metering` → 8089）；⑤ **后端 1 个加性只读端点** `GET /api/v1/metering/flows/governance`（`metering.governance`；`MeteringFlowRow` 九字段；分页 + 五维过滤；零迁移 / 零新权限点 / 零新错误码；3.7.5 既有端点零改动）+ 测试；⑥ 测试锚 **T1~T16**（前端 100~130 例 + 后端 5~7 例；含 XSS 渲染锚、只读结构锚、静态纪律锚、非运营 403 负向锚）；⑦ **静态 HTML 线框原型**（11 画面）+ **界面说明书**（hifi §3）；⑧ **三剧本核对修订**（C-7.2 计量计费中心 → **V1.3**；C-7.2 账单核对 → **V1.2**；C-7.1 门户 → **V1.4**；业务判定一字不改，PO 批准）；⑨ 复用 / 规格外声明 + 台账与开发日志 + 体量登记 + 业务可读交付说明。 |
| 关闭条件 | ① 两级设计获编排师一次确认（**Q1~Q8 + D1**）；② 本地门禁全绿：前端 `npm test`（基线 **749 例**零回归 + 新增全绿）/ `typecheck` / `lint` / `build` + metering-billing `test`（基线 **48 例**零回归 + 新增 5~7 例）/ `checkstyle:check` + gateway-service / contract-service 零回归抽跑（本卡零改动，兜意外）+ 全仓 `compile`（20 模块）+ `gates-config.json` 零改动（ConfigSha256 与基线逐字一致）；③ 规格 B4-2 / B9~B12 → 界面承载 → 剧本步骤映射齐备（hifi §8 回填实测锚点）；④ **三剧本可在界面逐步执行通过**（技术侧配合步骤按 hifi §8 承载口径；演示链 = 三容器 + subject 8080 + contract 8085 + metering-billing 8089 + 前端 5173）；⑤ 4 视角评审 + 修复循环 ≤3 轮 + 独立复审 PASS；⑥ 三剧本核对修订完成并提示 PO 批准留痕；⑦ 编排师验收通过（界面级人工走查，沿 3.4.7 / 3.5.6 / 3.6.7 先例）；⑧ 零越界（**3.7.5 / 3.7.6 既有端点、表结构、错误码、权限点零改动**〔仅 Q3-A 加性只读端点〕；**无费率写入**〔Q2〕；**无账单修改入口**〔B10-3〕；**无对账自动修数**〔B11-3〕；零资金动作；零新依赖 / 迁移 / 权限点 / 错误码；零 `admin` 混入门户档；零门禁配置改动；零口令入库；`/internal/**` 零触碰）。 |
| 分支 | `feat/WBS-3.7.7-计费管理界面`（**自本地 main 梢 `8603098` 创建** = 3.7.6 收口批 `d9c0722` + 回填批 `771afe6` + 勘正批 `8603098`；**ahead 3 随本卡 PR 一并入库**；`origin/main` = `b538414` 未动、未建 PR）。 |
| 设计文件 | `docs/designs/WBS-3.7.7-lofi.md`（**V1.0 已确认 = 方向定稿，2026-11-02**）/ `docs/designs/WBS-3.7.7-hifi.md`（**V1.0 已确认 = 编码契约即时生效，2026-11-02**）/ `docs/designs/WBS-3.7.7-原型-计费管理界面.html`（静态线框原型 11 画面）。 |
| 纪律声明 | **界面只做已交付契约的界面化**（3.7.6 八端点 + 3.7.5 计量读面 + 3.7.3 订单治理读面；Q3-A 加性只读端点为唯一后端增量），**不为界面新增业务规则 / 错误码 / 状态枚举**；**不绕过后端校验**（状态门槛、金额校验、归属、档位一律以后端为准，前端只做空值拦截 + 禁用态提示 + 文案原样展示）；**不做乐观更新 / 不做假幂等**（计费端点无幂等键）；**不做费率写入**（写面唯一在目录域）；**不做账单金额或明细修改入口**（结构性保证：视图零 `v-html`、零直拼 URL、零金额编辑控件）；**不做对账自动修数 / 一键修复**；**自由文本一律转义渲染**（DB-103 ②）；**普通档不携带 `admin`**（门户视图与 `constants/billing.ts` 零 `admin` 字面量）；**零支付 / 退款 / 结算 / 发票**；**不触碰 `/internal/**`**；**不改既有端点语义 / 错误码码值 / 门禁配置 / ADR-001 技术基线**；**不做真实登录 / JWT / 网关正式链路**（归 3.9.1）；**剧本只核界面入口与承载说明，业务判定一字不改**；**规格缺口走变更流程 / 编排师裁定**，不在本卡私自补需求；发现冲突或疑似 S0 安全问题 → **立即停止上报**（红线 4）。 |

---

## 一、范围与设计

- **做什么 W1~W9 / 不做什么 N1~N10**：见 `docs/designs/WBS-3.7.7-lofi.md` §1；
- **页面树 / 模块组成**：lofi §2；**主要流程（业务视角）**：lofi §3；**决策点（待确认）**：lofi §4（**Q1~Q8 + D1**）；**体量**：lofi §5（预估 **2400~3200 行**有效变更；D1 建议不拆分、1 个编码会话）；
- **编码契约**（接口契约表〔既有 8 + 新增 1〕/ 前端模块与类型 / 界面说明书 P1~P11 / 路由与菜单 / 演示身份 / 动作矩阵 / 标签与错误口径 / 剧本映射 / 测试锚点 / 实施前置检查 / 复用声明 / 勘误）：见 `docs/designs/WBS-3.7.7-hifi.md` §1~§12。

### 立卡前素材盘点（只读，本会话；两轮只读子智能体实测 + 主会话文档核对）

| 项 | 结论 |
| --- | --- |
| ① WBS 锚点 | 行 319 原文核对（账单管理 / 对账 / 费率配置三词；依赖 3.7.6 已闭环；预算 1 会话）；三词映射 → Q2（费率只读视图 + 目录定价页指路）；下游 3.7.8 |
| ② 规格锚点 | C-7.1-7.2 V1.0：B9（四档口径呈现）/ B10（出账 · 确认 · 更正；自动腿不入界面）/ B11（对账双向 + fail-closed）/ B12（三类读面；B12-2 全量流水归本卡）+ B4-2（运营订单检索）+ §4-3 归口 + §6-2；B12 落地注记（202~205 行）明文：全量流水端点 / 权限 / 分页随本包设计定稿 |
| ③ 剧本锚点 | C-7.2 计量计费中心 V1.2：S1-5 免费档 + S2 十一歩 → 界面承载；C-7.2 账单核对 V1.1：S1 三步 + S2 五步 → 界面承载（差异构造仍为附录 A 显式直改库动作，界面**不提供**改流水入口）；C-7.1 门户 V1.3：S2-7 运营订单检索"界面归 3.7.7"→ 本卡兑现 |
| ④ ADR 锚点 | ADR-005（响应 / 错误码 / 分页）；ADR-016 §2.7（演示期回环 + 身份头）；ADR-018（core 80%，前端不计）；ADR-001（栈冻结）；ADR-009（迁移——本卡零迁移）；零国密接触（界面无加解密面） |
| ⑤ 后端契约实测 | 3.7.6 八端点逐 Controller 实读（路径 / 权限 / 请求 / 响应 / 错误）；**B-R2 / B-R4 对 admin 放行**（运营详情与对账读面直接可用，无需新增端点）；B-R3 七维过滤；B-W1 三档（分成档必填基数，缺 → 1011C0013）；B-W2/B-W3 受状态机与冻结约束（1011C0011/C0012） |
| ⑥ 计量读面实测 | 3.7.5 四端点；`R-M1` 归属硬校验（非当事方含 admin 一律 404 1011C0008，已有用例锚定）；`metering_flow` 九列 + `uk(source_type, source_no)`；`MeteringSourceType` 四值；`common-pagination` 已引入 → **新增治理读面为加性端点，不动既有语义**（Q3） |
| ⑦ 订单读面实测 | `GET /api/v1/orders/governance`（`contract.governance`，四过滤 + 分页）已存在 → Tab4 零后端改动；`R-OR2` 详情仅当事方（admin 404）→ 行内不提供详情跳转（hifi E0-2） |
| ⑧ 前端实测 | 六目录结构 / 单 layout + `meta.permission` + menuOrder 制 / 菜单 23 项（普通 10、admin 23）/ 命名路由 36 / `api/` 无 billing / `client.ts` fetch 工厂 + 身份头（无 JWT）/ 错误文案按域内嵌 / **无按钮级权限点** / `vite` 16 键缺 billing·metering / 测试 58 文件 749 例 / `PortalSourceGuard.spec.ts` 三处静态断言需按开放口径更新 |
| ⑨ 演示身份实测 | `pageRolesHeader(operatorRoles, normalRoles)` 工厂；各域运营档一律 `admin`；门户视图禁 `admin` 字面量（静态断言）；本卡新增 `billingRolesHeader()` 沿同型 |
| ⑩ 门禁与依赖面 | 前端零新增 npm；后端零新增 Maven（`common-pagination` 已在）；零迁移 / 零权限点 / 零错误码 / 零容器；`gates-config.json` 预计零改动（SHA256 基线待编码段复核） |
| ⑪ 测试与体量面 | 基线：前端 58 文件 749 例 / metering-billing 48 例；本卡新增前端 100~130 例 + 后端 5~7 例；预估 2400~3200 行（D1 不拆分）；红相归档 `build-output/w377-red-*` |
| ⑫ DB-103 五项分解 | ① `skippedFree` 不实现 → 界面不展示该字段（无动作，登记）；② `reason` 转义 → **本卡 T13 落地**；③ 横切收敛建议（`CONTRACT_NO` 正则 4 份 / 错误工厂 / `toLocalDateTime` / `SqlArgs` / 金额常量）→ **不在本卡夹带**（后端横切批，登记转 `common` 沉淀评估）；④ 平台项（网关注入身份头信任 / k8s NetworkPolicy / `ClaimValidator` issuer / 网关路由测试计数 / `BillingProperties.Auto.cron` 双源）→ 归 3.9.x / 3.7.8（登记）；⑤ **E4 逐条全量计量流水读面 → 本卡 Q3 + Tab3 落地**（B12-2 归口闭合） |
| ⑬ 演示面 | 三容器 + subject 8080 + contract 8085 + metering-billing 8089 + 前端 5173；界面数据经端点产生，**禁直改库**（差异构造仅对账剧本附录 A 显式动作）；演示库迁移已至 V2（3.7.6） |

**设计要点摘要（供快速表决）**：

1. **2 菜单 + 2 深链**（Q1-A）：运营「计费管理」（`/billing`，menuOrder 24，admin，五 Tab = 账单管理 / 对账工作台 / 计量流水 / 订单检索 / 费率视图）+ 门户「我的账单」（`/portal/bills`，menuOrder 25，登录可见）+ 两个账单详情深链。
2. **费率配置只读化**（Q2-A）：写面唯一保留目录「产品上架」页（四档 + 留痕 + `catalog.product`）；计费侧只做「费率视图」说明 + 快照来源 + 跳转指路，**不造第二写面**；WBS 三词映射需确认留痕。
3. **全量计量流水新增 1 个只读端点**（Q3-A）：`GET /api/v1/metering/flows/governance`（`metering.governance`；分页 + 五维过滤；零迁移 / 零新码 / 零新权限点），3.7.5 归属硬校验零改动——兑现 B12-2 与 3.7.6 E4 归口。
4. **运营订单检索零后端改动**（Q4-A）：复用 `GET /api/v1/orders/governance`，兑现 C-7.1 S2-7；不提供运营订单详情（权限边界如实标注）。
5. **演示身份双档**（Q5-A）：`billingRolesHeader()` = 运营 `admin` / 普通 `requester,provider`；`myRole` 由服务端判定。
6. **复算展示不越界**（Q6-A）：明细逐行 + 前端"明细合计"算术对照，不一致时提示"以服务端为准"，不拦截、不写、不重算引擎。
7. **自由文本转义**（Q7-A）：`{{ }}` 插值、零 `v-html` + 注入用例锚定（DB-103 ②）。
8. **三剧本一次性修订**（Q8-A）：C-7.2 V1.3 / 账单核对 V1.2 / C-7.1 V1.4，业务判定一字不改，PO 批准。

## 二、决策点与确认留痕（待编排师一次确认；章程 2.6.3）

> 决策点全文与备选业务影响见 `docs/designs/WBS-3.7.7-lofi.md` §4；下表为摘要。

| 编号 | 问题 | 建议口径 |
| --- | --- | --- |
| **Q1** | 菜单结构与页面形态 | **A 建议**：2 菜单 + 2 深链——运营「计费管理」`/billing`（menuOrder 24，`billing.governance`，五 Tab：账单管理 / 对账工作台 / 计量流水 / 订单检索 / 费率视图）+ 门户「我的账单」`/portal/bills`（menuOrder 25，登录可见）+ 两个账单详情深链。B：单菜单按角色混排 Tab（运营面与需方面混载，越权对照失真）；C：按 WBS 每词一菜单（菜单膨胀，费率页仅一张说明卡） |
| **Q2** | WBS「费率配置页面」落点（术语映射 / 规格缺口） | **A 建议**：写面唯一保留在目录域「产品上架」页（既有四档 + 变更留痕 + `catalog.product`）；本卡 Tab5「费率视图」= 四档口径说明 + 快照来源说明 + 「前往产品上架页」指路，**只读**；WBS 三词映射 = 账单管理 / 对账 / 费率配置（只读视图 + 配置指路）——**需编排师确认留痕**（沿 3.6.7 Q1 概称映射先例）。B：新建费率配置页直连目录 `PUT /api/v1/data-products/{productId}`（第二写面：同数据两入口、权限点跨域、留痕在目录域而操作在计费域）；C：不做费率任何界面（WBS 第三词落空） |
| **Q3** | 运营全量计量流水读面（DB-103 E4 / B12-2 归口） | **A 建议**：新增 1 个加性只读端点 `GET /api/v1/metering/flows/governance`（`metering.governance`；合约 / 主体 / 源类型 / 时间区间过滤 + 分页；逐条九字段），3.7.5 既有端点与归属硬校验**零改动**；B12-2 在此闭合。B：只展示 R-M3 聚合读数（字面"逐条"未满足）；C：扩展既有 `/contracts/{no}/flows` 对 admin 放行（改已闭环语义 + 破防枚举用例，红线冲突） |
| **Q4** | 运营订单检索界面（C-7.1 S2-7 归口） | **A 建议**：Tab4「订单检索」只读列表（复用 `GET /api/v1/orders/governance`；四过滤 + 分页），**零后端改动**；不提供运营订单详情跳转（R-OR2 仅当事方）。B：新增运营订单详情端点（越出 B4-2 语义、扩面）；C：不做（S2-7 界面永久悬空） |
| **Q5** | 演示身份与角色头映射 | **A 建议**：`billingRolesHeader()` = 运营 `admin` / 普通 `requester,provider`；`myRole` 由服务端判定。B：普通档分 `requester` / `provider` 两档手动切换（演示步骤翻倍，收益低）；C：沿用全局 `demoRolesHeader()`（运营档无治理权限点，运营页整体 403） |
| **Q6** | 账单金额复算展示口径 | **A 建议**：详情 = 快照 + 逐行明细 + **前端"明细合计"算术对照**（与账单金额并列；不一致提示"以服务端账单为准"，不拦截 / 不改值 / 不重算引擎）。B：不做合计（复算演示需人工相加）；C：前端按档完整重算（复制计费引擎 = 重复实现 + 双口径风险） |
| **Q7** | 自由文本渲染安全（DB-103 ②） | **A 建议**：一律纯文本转义（模板插值），**billing 视图零 `v-html`** + 静态扫描锚 + 注入用例（`<img src=x onerror=...>` 按文本显示）。B：允许 HTML 渲染（存储型 XSS 面，安全评审必打回） |
| **Q8** | 剧本核对修订范围 | **A 建议**：三份一次性核对（业务判定一字不改，PO 批准）——C-7.2 计量计费中心 → **V1.3**（S2 操作路径界面化 + 接口路径保留为技术侧对照 + S1-5 免费档界面提示）；C-7.2 账单核对 → **V1.2**（S1/S2 界面入口 + 差异明细位置 + 冻结态确认提示）；C-7.1 门户 → **V1.4**（S2-7 改为实际路径）。B：只改 C-7.2 两份（S2-7 悬空）；C：不改（违剧本同步义务） |
| **D1** | 体量 / 是否拆分 | **建议不拆分**：预估 **2400~3200 行**有效变更（前端为主 + 后端 1 只读端点 + 测试 100~130 例），**1 个编码会话**（WBS 行 319 = 1 人日）；五 Tab 与两详情共享同一套 API / 常量 / 角色头，剧本走查链跨页连贯；超 400 行按界面包先例登记豁免 |

> **✅ 确认留痕（2026-11-02 23:2x 编排师会话回复「确认」= 全部按建议，Q1~Q8 全采建议口径 A + D1 不拆分）**：Q1-A 2 菜单 + 2 深链（计费管理五 Tab / 门户我的账单 / 两详情深链，menuOrder 24/25）/ Q2-A 费率配置只读化（写面唯一在目录「产品上架」页，计费侧费率视图 + 跳转指路；**WBS 三词映射按本节留痕确认**）/ Q3-A 新增 1 个只读端点 `GET /api/v1/metering/flows/governance`（`metering.governance`；零迁移 / 零新码 / 零新权限点）/ Q4-A 运营订单检索复用 R-OR3（零后端改动，不提供运营订单详情）/ Q5-A `billingRolesHeader()` 运营 `admin` / 普通 `requester,provider` / Q6-A 明细合计对照 + "以服务端为准"（不重算引擎）/ Q7-A 一律转义 + 零 `v-html` + 注入用例 / Q8-A 三剧本一次修订（C-7.2 **V1.3** / 账单核对 **V1.2** / C-7.1 **V1.4**，业务判定一字不改，PO 批准）/ **D1 = 不拆分**（预估 2400~3200 行，1 个编码会话，超 400 行按界面包先例登记豁免）。lofi / hifi 同批转 **V1.0（方向定稿 / 编码契约即时生效）** → **下一关口 = 编码段（新会话冷启动，测试先行，第一动作 = hifi §10 前置检查十二项逐项实测留痕）**。

## 三、规格行为 → 本卡承载 → 剧本步骤 映射表（编码段回填实测锚点）

| 规格承载（V1.0） | 本卡落点（界面 / 端点） | 剧本步骤 | 测试锚点（编码段回填） |
| --- | --- | --- | --- |
| B9-1（按次 = 事件数 × 次单价） | Tab1 出账（P1 合约）+ 详情明细逐笔 | C-7.2 S2-1 | T2 / T3 |
| B9-2（包月 = 周期费，与次数无关） | 同上（P2 合约；数量为空） | C-7.2 S2-3 | T2 / T3 |
| B9-3（分成 = 约定交易额 × 费率） | 出账弹窗基数输入 + 详情基数 / 费率双快照 | C-7.2 S2-4 | T2 / T3 |
| B9-4（可复算；改价不追溯） | 详情明细合计对照 + 零金额编辑入口 | C-7.2 S2-5 / S2-6 | T3 / T12 |
| B10-1（出账双轨 + 同合约同周期幂等） | 手动出账（自动腿无界面，说明卡） | C-7.2 S2-1/S2-2/S2-11 | T2 |
| B10-2（编号唯一终身不复用） | Tab1 列表账单号列（含更正单） | C-7.2 S2-8 | T1 |
| B10-3（不可篡改 + 更正单留痕） | 详情无修改入口 + 更正弹窗 + 更正链 | C-7.2 S2-7 | T5 / T15 |
| B10-4（账单读面；非本方不可读） | 门户列表 / 详情 + 运营治理列表（B-R3） | C-7.2 S2-9/S2-10 | T11 / T12 |
| B11-1（四方交叉验证正向） | Tab2 执行对账 + 四方数值表 | 账单核对 S1-1/S1-2 | T6 |
| B11-2（账实不符必被报，双向） | 差异结论 + 七类差异明细表 | 账单核对 S2-2 | T6 |
| B11-3（差异 fail-closed / 澄清解冻） | 冻结提示条 + 确认禁用 + 服务端 1011C0011 兜底 | 账单核对 S2-3/S2-5 | T4 / T6 |
| B11-4（留痕完整；不自动修数） | 对账记录列表 + 详情动作留痕；界面零修数入口 | 账单核对 S2-4 | T7 / T15 |
| B12-1（本方读面 + 对账结论可读） | 门户「我的账单」+ 详情（含对账结论区） | C-7.2 S2-9 + 账单核对 S1-3 | T11 / T12 |
| B12-2（运营全量 + 手动出账 / 对账） | Tab1 / Tab2 / Tab3（**计量全量流水 R-M4**） | C-7.2 S2-1 / 账单核对 S1-1 | T8 / T16 |
| B12-3（非本方一律拒绝） | 门户详情整页空态（1011C0009 同码同文） | C-7.2 S2-10 | T12 |
| B8-2（免费档不产生计费流水） | 出账弹窗对免费档合约 → 1011C0015 原样展示 | C-7.2 S1-5 | T2 |
| B4-2（运营全量订单检索） | Tab4（R-OR3 既有端点） | C-7.1 S2-7 | T9 |
| （B9~B12 错误语义） | 1011C0009~C0015 原样展示（零新码） | 全幕错误文案观察 | T2 / T4 / T5 / T11 |

> 界面权限面：`/billing/**` = `billing.governance`（admin 档）；`/portal/bills/**` = 登录可见（服务端 `billing.read` + 归属硬校验）。**不调用** `/internal/**`、不改任何上游端点；3.7.8 联调零触碰。

### 编码段实测锚点回填（T1~T16 → 实测文件与例数）

| 锚 | 实测落点（文件） | 例数 | 关键断言（实测） |
| --- | --- | --- | --- |
| T1 | `frontend/src/views/billing/BillingManageView.spec.ts`（T1 块） | 9 | 列表十列渲染 / 状态与合约过滤 / 重置回第 1 页 / 空态文案 / 失败态 + 重试 / 详情深链 / 未知状态原样 / **分页档位 10·20·50（修复批新增）** |
| T2 | 同上（T2 块） | 7 | 请求体（合约 + 默认上一自然月 + 分成基数）/ 空合约零请求 / 409·400·409 文案原样且弹窗不关 / 说明卡四档 |
| T3 | `frontend/src/views/billing/BillDetailView.spec.ts`（T3 块） | 10 | 快照按档显隐（按次 vs 分成）/ 明细逐行 / 合计相等·不等两态 / 留痕 / 更正链双向 / 1011C0009 空态 / 错误重试 / 生成人 |
| T4 | 同上（T4 块） | 4 | ISSUED 可确认 → CONFIRMED 刷新 / FROZEN 禁用 + 提示 / CONFIRMED 禁用 / 409 原样 + 自动刷新 |
| T5 | 同上（T5 块） | 6 | 空金额·空原因零请求 / 负金额字符串 / 成功跳更正单 / 409 原样 / 语义提示 / 200 字上限与计数 |
| T6 | `frontend/src/views/billing/components/ReconcilePanel.spec.ts`（T6 块） | 11 | 执行对账参数 / PASS 八方数值 / 无差异空态 / DIFFERENCE 七类逐条 + 冻结提示 / 未知类型原样 / 空值零请求 / C0009·C0014 原样 / 查看该期账单事件 |
| T7 | 同上（T7 块） | 3 | 记录四列渲染 / 分页参数 / 点击记录回填结论卡 |
| T8 | `BillingManageView.spec.ts`（T8 块）+ `api/billing.spec.ts`（URL 断言） | 5 | 九列渲染 / 合约·主体·源类型过滤 / 时间控件在位（from·to 查询串由 api 层精确断言）/ 空态 / 1000C0001 原样 |
| T9 | `BillingManageView.spec.ts`（T9 块） | 4 | R-OR3 三过滤参数 / 行内零详情链接 / 说明条 / 1011C0003 原样 |
| T10 | `BillingManageView.spec.ts`（T10 块） | 4 | 四档卡片 + 快照来源 / 目录指路 href / 只读结构（零 input·button·textarea）/ **自动出账说明（修复批新增）** |
| T11 | `frontend/src/views/portal/PortalBillListView.spec.ts` | 9 | 列表与我的角色中文 / 过滤·重置·分页 / 空态 / 失败文案原样 / 详情深链 / **分页档位（修复批新增）** |
| T12 | `frontend/src/views/portal/PortalBillDetailView.spec.ts` | 8 | 快照 + 明细 + 合计对照 / 零写动作按钮 / 更正链 / 对账结论区（E2）/ 1011C0009 空态 / 返回列表 |
| T13 | `BillDetailView.spec.ts`（T13 块） | 2 | 留痕原因与明细说明注入 HTML → 纯文本渲染、DOM 零 img/script、`window.__xss` 未定义 |
| T14 | `frontend/src/router/router.spec.ts`（计费域路由块 + 守卫三例）、`layouts/MainLayout.spec.ts` | 7 | 四路由结构（23/24 + 两深链）/ 普通档 `/billing` → denied / admin 放行 / `/portal/bills` 登录可见 / 菜单 11·25 |
| T15 | `frontend/src/views/billing/BillingSourceGuard.spec.ts`（8 例）+ `views/portal/PortalSourceGuard.spec.ts`（2 例口径更新） | 10 | 端点单点封装 / `makeDomainJson(billingRolesHeader)` / 零 fetch / 零 v-html / 零 traceId / 常量零角色名与 URL / 门户视图零计费写函数 |
| T16 | `services/metering-billing/src/test/java/com/ctds/metering/interfaces/MeteringGovernanceIntegrationTest.java` | 7 | 分页 + occurred_at 倒序 / 第二页 / 主体·源类型过滤 / 时间闭区间 / 非运营 403×2 / 四类非法参数 400 `1000C0001` / R-M1 非当事方 404 零回归 |
| 合计 | 前端新增 **109 例**（58→65 文件、749→858 例）；后端新增 **7 例**（48→55） | 116 | 反向锚全覆盖（T4/T6/T9/T12/T13/T15/T16③⑤） |

> **例数口径说明（评审修复 + 复审 P3 校正：消除"逐行相加 ≠ 合计"的歧义）**：表中分锚例数 = 该落点文件内的**用例总数**（含既有文件的更新例），故不逐行相加。
> **编码批新增 109 例的分解** = `BillingManageView` 27（T1 8 + T2 7 + T8 5 + T9 4 + T10 3）+ `BillDetailView` 22（T3 10 + T4 4 + T5 6 + T13 2）+ `ReconcilePanel` 14（T6 11 + T7 3）+ `PortalBillListView` 8 + `PortalBillDetailView` 8 + `BillingSourceGuard` 8 + `api/billing` 18 + `router` 4；后端 7 = `MeteringGovernanceIntegrationTest`。
> **修复批新增 5 例** = `BillingManageView` 2（T1 分页档位、T10 自动出账说明）+ `PortalBillListView` 1（分页档位）+ `PortalSourceGuard` 2（计费端点旁路扫描、门户渲染安全锚）；后端 2 = 空结果 / 分页上限。
> **本卡终值**：前端 **65 文件 863 例**（749 + 114 = 863）；后端 **57 例**（48 + 9）。逐文件基数经 `Select-String '^\s+it\('` 复核：`BillingManageView` 29 / `BillDetailView` 22 / `ReconcilePanel` 14 / `PortalBillListView` 9 / `PortalBillDetailView` 8 / `BillingSourceGuard` 8 / `api/billing` 18。

## 四、执行记录

| 项 | 内容 |
| --- | --- |
| 立卡段（2026-11-02） | 🟡 **立卡段完成（待一次确认）**：任务卡 + lofi **V0.9** + hifi **V0.9** + 静态原型（11 画面）落盘；素材盘点 = 两轮只读子智能体代码实测（前端结构 / 路由 / 身份 / API 层 / 测试基线 / 计费八端点 / 计量读面 / 订单治理读面 / 定价写面 / vite 转发 / 静态纪律断言）+ 主会话文档核对（规格 B4-2 / B9~B12 + 落地注记、三剧本、WBS 行 319~320、DB-103 五项）；**新发现三项** = ① 运营账单 / 对账读面可复用 B-R2 / B-R4（admin 放行，无需新增详情端点）② 运营订单检索端点已存在（Tab4 零后端改动）③ `vite` 缺 billing / metering 转发键（不补则走查误落 8080）；分支 `feat/WBS-3.7.7-计费管理界面` 自本地 main `8603098` 创建（ahead 3 随本卡 PR 入库——链式先例）；**本段零代码、零依赖、零门禁配置改动，未动 `main`**；台账"进行中"行暂未改（沿 3.3.6~3.7.6 先例，编码段更新）；立卡批本批提交（未推送）。 |
| 确认段（2026-11-02） | ✅ **一次确认完成（编排师会话回复「确认」= 全部按建议）**：Q1~Q8 全采建议 A + D1 不拆分（留痕 = §二 确认段引述行）；lofi **V1.0 方向定稿** / hifi **V1.0 编码契约即时生效**；零代码改动，确认批本地提交（未推送）。 |
| 编码段（2026-11-02） | ✅ **编码段完成（测试先行 → 实现 → 全量验证绿灯；提交未推送）**：① **前置检查十二项逐项实测**（分支/工作树 clean、`git log -1` = 确认批 `075f7e9`、本地 main ahead 3；前端基线 **58 文件 749 例** + typecheck/lint/build 全绿；metering-billing 基线 **48 例** + checkstyle 0 违规；3.7.6 八端点直连 8089 实测 **8/8 预期形态**〔B-R3/B-R1 200 空页、B-R2/B-W2/B-W3 404 `1011C0009`、B-R4/B-W4/B-W1 404 `1011C0014`〕+ R-OR3 实测 200/403；`metering_flow` 九列 + `idx_flow_contract` 实读；`PortalSourceGuard.spec.ts` 三处断言原文留证；vite 键序 16 键、新键置于兜底 `/api` 前；`gates-config.json` SHA256 `955bdbba…2ee2` 与基线逐字一致、零改动；`common-pagination` 在位；容器三件就绪 + 8089/8085 就绪）。**注**：前置直连实测前发现既有 jar 早于 3.7.6 合并（billing 端点 404），已重建 jar 后复测（`build-output/w377-precheck/`）。② **红相归档**：前端 6 个新 spec 加载失败 + 2 个守卫 spec 5 断言红（`build-output/w377-red/w377-red-frontend.log`）；后端 T16 7/7 红（404，`w377-red-backend.log`）。③ **实现**：前端 9 视图与组件 + `api/billing.ts`（9 函数）+ `api/order.ts` 追加 `listGovernanceOrders` + `constants/billing.ts` + `billingRolesHeader()` + 4 路由 + vite 2 转发键；后端 R-M4（`MeteringFlowRow` / `FlowSearchCriteria` / 仓储两方法 / `governanceFlows` / 控制器一方法 + 参数守卫）。④ **绿灯**：前端 **65 文件 858 例全绿**（+7 文件 / +109 例，基线零回归）+ typecheck 0 + lint 0 + build OK；后端 **55 例 0 失败 0 跳过**（48 基线 + 7 新增）+ checkstyle 0 违规；metering-billing 行覆盖 **94.88%**（missed 55 / covered 1020 / total 1075，≥80%）；全仓 **20/20 模块 compile SUCCESS**；R-M4 直连实测 200/403/400×2。⑤ 三剧本修订（C-7.2 计量计费中心 **V1.3** / 账单核对 **V1.2** / C-7.1 **V1.4**）+ hifi §12 勘误 E1~E4 + 本卡回填 + 开发日志。 |
| 评审段（2026-11-03） | 🟡 **4 视角评审循环 1 完成（单发独立只读子智能体，对象 = 编码批 `b612988`）**：① 规格与设计符合性 **FAIL**（1×P1 = 剧本 V1.3 承载对照 S1-1/S1-7 错配；4×P2 = 本方流水说明条指向不存在界面 / 分页 10·20·50 未实现 / E0-3 自动出账说明缺失 / 自检单"逐条一致"表述不实；8×P3）；② 安全与供应链 **PASS**（1×P2 = 锁文件第三方镜像，非本批引入；3×P3）；③ 一致性与重复 **PASS**（2×P2 = `CONTRACT_NO` 四件套新副本未标注 DB-103 ③ / 4 个 UI 常量死定义；9×P3）；④ 测试质量 **PASS**（3×P2 = 生成人空洞断言 / R-M4 分页边界缺失 / Guard 拦截面窄于声明；7×P3）。**零 P0 / 零 S0**；④ 独立复算：前端 65 文件 858 例、后端 55 例、行覆盖 94.8837%、R-M4 新增代码零行缺失 —— 与编码段声明一致。 |
| 修复批段（2026-11-03） | ✅ **P1 必修 + P2 全处置 + P3 择小**：**P1** = 剧本 S1-1/S1-7 承载改为"界面无承载 → 接口承载"并加更正说明（业务判定一字未改）；**P2 落地** = 分页档位 10/20/50（运营四区 + 门户列表，切换回第 1 页）/ E0-3 自动出账说明（费率视图 + 出账弹窗）/ P7 说明条改口径（E5）/ 自检单与例数口径更正（§三/§五/§六）/ `MeteringController` 补 DB-103 ③ 标注 / 4 个 UI 常量接入视图 / 生成人断言强化 / R-M4 分页边界 2 例（空结果、pageSize>100 与 pageNum>10000 → 400、越页空列表）/ Guard 旁路扫描 + 门户渲染锚；**P3 采纳** = `labelOf` 统一兜底、`encodeURIComponent` 路由参数；**登记跟踪** = requesterNo 长度守卫（承接 3.7.8/4.2.2 真链）、`MeteringFlowRow` 落位与 `SOURCE_TYPE_LABELS` 重命名（DB-103 类）、§2.4/§2.3 正文交叉标注（已由 §12 E5~E11 登记）。 |
| 验收段（2026-11-03） | ✅ **编排师验收通过**（会话回复「验收通过」）：业务口径 = 日志 `-2130` 业务可读总结；走查链 = 运营「计费管理」五页签（账单管理 / 对账工作台 / 计量流水 / 订单检索 / 费率视图）+ 账单详情（确认 / 更正）+ 门户「我的账单」两页；修复批验证数字（前端 863 例 / 后端 57 例 / 覆盖 94.88% / 20 模块 compile）为验收依据。**三剧本随验收生效**（C-7.2 计量 V1.3 / 账单核对 V1.2 / C-7.1 V1.4；业务判定一字未改，C-7.2 V1.3 的 S1-1/S1-7 按"接口承载"走查）。**验收通过 ≠ 合并授权**：未推送、未开 PR；下一关口 = 收口段（台账六处 + 跟踪项入债务登记簿 + 推送待编排师指示）。 |
| 合并与收口段（2026-11-03） | ✅ **合并闭环 + 收口完成**：① 编排师「合并、推送」指示 → 分支 5 笔推送 → **PR #27** → PR gate run `41648891584` **SUCCESS 11m22s 一发绿** → `gh pr merge 27 --merge` → **MERGED `2ae103c`**（非 squash，远端分支保留沿先例）；② 本地 main `--ff-only` 纯快进；main gate run `41650781697` **SUCCESS 16m36s 一发绿**（零重跑、零降级）；③ 收口段 = 台账六处（进行中 / 待编排师 / L1-3 **55→56/73** / V1.0 **84→85/136** / 下一包候选 = 3.7.8 或 3.8.1 / 本卡条目含两 run 号）+ 跟踪项入簿 **DB-104~107**（requesterNo 长度守卫 / `filters()`+`numberOrUndefined` 上提 / `MeteringFlowRow` 落包与 `SOURCE_TYPE_LABELS` 重命名 / 锁文件第三方镜像）+ **DB-103 ③ 计数实测更正**（`CONTRACT_NO` 4→6 份 / 错误工厂 11→15 处）；④ 收口批**纯文档、仅本地提交**（随下一卡 PR 入库） |

## 五、体量登记（立卡时填写）

| 项 | 数值 / 说明 |
| --- | --- |
| 实测（立卡段） | 任务卡 **103 行**；`docs/designs/WBS-3.7.7-lofi.md` **91 行**；`docs/designs/WBS-3.7.7-hifi.md` **223 行**；`docs/designs/WBS-3.7.7-原型-计费管理界面.html` **520 行** → 合计 **937 行**（行数口径 = `Measure-Object -Line` 实测，2026-11-02 23:20 立卡段）；**无代码、无测试、无新依赖** |
| 预估（编码段） | 有效变更 **2400~3200 行**：视图与组件 ≈1200~1600 + API / 常量 / 身份 / 路由 / 转发 ≈350~450 + 后端（R-M4 + 查询 + DTO + 测试）≈250~350 + 前端测试 ≈700~900 + 剧本与文档 ≈300~400；**1 个编码会话**；零新依赖 / 零迁移 / 零新权限点 / 零新错误码 |
| 实测（编码段，2026-11-02；**评审修复后按提交统计更正**） | **提交 `b612988` 全量 = 38 文件 `+4825 / −39`**（其中 docs 6 文件）。**代码**：新增 21 文件（前端 **18**：视图与组件 11 + 测试 5 + `api/billing.{ts,spec.ts}` 2 + `constants/billing.ts` 1 + 守卫 spec 1，与清单逐项对应；后端 **3**：`MeteringFlowRow` / `FlowSearchCriteria` / 集成测试）；修改 11 文件（前端 **7** / 后端 4）。**估算有效变更 ≈4800 行**（编码批口径修正：原记"≈4250 行 / 前端 14 文件 / 修改 11 文件"与实际不符，评审 ③F11 指出后按 git 统计重述）。**超立卡预估 2400~3200 约 50%**——主因 = 测试体量（前端 5 个新 spec 约 1400 行 + `api/billing.spec.ts` 255 行、后端集成测试 225 行）。**零新依赖 / 零迁移 / 零新权限点 / 零新错误码 / 零容器 / 零门禁配置改动**（`gates-config.json` SHA256 与基线一致）。超 400 行按 D1 确认"界面包先例登记豁免"执行。 |
| 实测（修复批，2026-11-03） | **12 个代码/测试文件 + 3 个文档文件**（前端 10：`constants/billing.ts`、`BillingManageView.vue/.spec.ts`、`BillDetailView.vue/.spec.ts`、`ReconcilePanel.vue`、`OutbillDialog.vue`、`PortalBillListView.vue/.spec.ts`、`PortalSourceGuard.spec.ts`；后端 2：`MeteringController.java`（注释标注）、`MeteringGovernanceIntegrationTest.java`（+2 例）；文档 3：C-7.2 剧本、hifi §12、本卡）+ 本段开发日志。**零新增依赖 / 迁移 / 权限点 / 错误码 / 门禁配置**；用例净增 5（前端 +5 → 863；后端 +2 → 57）。 |
| 超 400 行指引说明 | 本卡为"计费管理界面"原子集（运营五 Tab + 两详情 + 门户两页 + 1 只读端点 + 三剧本修订）；拆则出账 → 确认 → 对账 → 冻结 → 更正 → 门户查账走查链断裂；请编排师在 **D1** 一并表态 |

## 六、提交前自检单（章程附录 B1，编码段填写）

1. ✅ **两级设计门禁已过**：lofi **V1.0**（方向定稿）/ hifi **V1.0**（编码契约，2026-11-02 一次确认 Q1~Q8-A + D1）；实现与 hifi §1~§12 的差异**逐条登记**：编码段 **E1~E4**（常量复用 / P11 对账结论区 / T15 落点拆分 / T8 断言分层）+ 评审修复段 **E5~E11**（P7 说明条口径 / §2.3 常量表补登 / §2.4 写动作区边界 / P4 与 §6 冲突取后者 / 枚举显示口径 / 生成人来源 / T15 扫描面扩展）；评审发现的两处**设计原文要求**（分页档位 10·20·50、E0-3 自动出账说明）已按原文补齐，不登记为差异。业务判定与断言零削弱（**原表述"逐条一致、仅 4 处差异"系评审 ①F5 指出的不实表述，已按实测重述**）。
2. ✅ **映射表回填**：§三"编码段实测锚点回填"表（T1~T16 → 文件 + 例数 + 关键断言）+ 例数口径说明（新增 109 例的实际分解）；修复批后终值 = 前端 **863 例** / 后端 **57 例**。
3. ✅ **复用声明**：`api/client.ts` `makeDomainJson` / `pageQuery`（分页串唯一副本）；`constants/catalog.ts` `PRICING_MODEL_LABELS` / `labelOf`；`constants/order.ts` `MY_ROLE_LABELS` / 订单状态标签；`views/billing/components/` 分件先例（connector 域）；`common-pagination` `PageQuery/PageResult`；`common-auth` `@RequirePermission`；`common-errorcode` `BizException/ErrorCodes`；3.7.6 八端点与视图字段零重写；3.7.5 `MeteringSourceType` / `metering_flow` 列面。**检索过程**：先查 `common/` 分页与鉴权（命中即复用）→ 再查前端 `api/client.ts` 工厂与 `constants/*` 单一源（命中即复用）→ 未命中项才新建（`api/billing.ts` 等 9 件）。**规格外实现声明：空**（R-M4 = 3.7.6 验收段 E4 归口；Tab4 = C-7.1 Q8-A / S2-7 归口；Tab5 = WBS 行 319 第三词术语映射 Q2-A；P11 对账结论区 = hifi §8 映射行）。
4. ✅ **本地全部检查（命令与结果）**：**修复批终值** —— `frontend: npm run test` → **65 文件 863 例全绿**；`npx vue-tsc -b` → 0 错；`npm run lint` → 0 告警；`npm run build` → 成功；根 `mvn -pl services/metering-billing prepare-agent test report checkstyle:check` → **57 例 0 失败 0 跳过 + 0 Checkstyle 违规 + BUILD SUCCESS**；行覆盖 **94.88%**（missed 55 / covered 1020 / total 1075，jacoco.csv 复算）；根 `mvn compile` → **20/20 SUCCESS**；`gates-config.json` 零改动（SHA256 与基线一致）。编码批当时的记录为 858 例 / 55 例（现已被修复批复跑覆盖）。日志：`build-output/w377-fix/`。
5. ✅ **高风险点自查**：并发 = R-M4 纯只读分页（`ORDER BY occurred_at DESC, id DESC` 稳定），无写并发面；事务 = 本卡无写路径（确认 / 更正沿用 3.7.6 `@Transactional`）；加解密 = 零接触；性能 = R-M4 走 `idx_flow_contract(contract_no, occurred_at)` 前缀，全表分页在演示量级可接受（性能基准归 3.7.8 / 4.2.2，沿 3.7.5 划界）；**XSS = 全部模板插值、billing 视图零 `v-html`，T13 注入用例锚定（DOM 零 img/script、脚本未执行）**；越权 = `/billing` 路由 `billing.governance`（普通档守卫拦截用例）+ 服务端 403 实测；`myRole` 由服务端判定（前端不猜）。
6. ✅ **验收剧本更新建议**：C-7.2 计量计费中心 → **V1.3**（界面入口行 + 文末《界面承载对照 V1.3》16 行）；C-7.2 账单核对 → **V1.2**（界面入口 + 对照 6 行 + 附录 A 零改动）；C-7.1 门户 → **V1.4**（S2-7 运营检索界面落地）。**业务判定与预期结果三份均一字未改**，待 PO 批准留痕。**评审修复**：C-7.2 V1.3 的 S1-1 / S1-7 承载已由"运营 Tab3 / 门户账单空态"更正为"界面无承载 → 接口承载"（评审 ①F1：资源与错误码均不同，不得替代），并加更正说明；业务判定仍一字未改。
7. ✅ **业务可读交付说明**：见开发日志"业务可读总结"（做了什么 / 如何演示 / 注意事项）。

---

> **边界复述（红线自查）**：本卡**只做 WBS 行 319 的界面承载 + 两处明文归口**（B12-2 全量计量流水 / C-7.1 S2-7 运营订单检索）；**后端仅新增 1 个只读端点**（3.7.5 / 3.7.6 既有端点、表、错误码、权限点零改动）；**无费率写入**（写面唯一在目录域）；**无账单金额 / 明细修改入口**；**无对账自动修数**；**零资金动作**（无支付 / 退款 / 结算 / 发票）；**零新依赖 / 迁移 / 权限点 / 错误码 / 容器 / 门禁配置改动**；**自由文本一律转义**；**普通档不携带 `admin`**；**剧本业务判定一字不改**；规格缺口走变更流程 / 编排师裁定；发现冲突或疑似 S0 安全问题立即停止上报（红线 4）。
