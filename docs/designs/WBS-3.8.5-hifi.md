# WBS-3.8.5 高保真设计（审计界面与查询 · 前端编码契约）V0.9

| 项 | 内容 |
| --- | --- |
| 版本 | **V0.9（立卡草案，2026-11-08）**——待编排师一次确认；确认后与 lofi / 原型同批转 **V1.0 = 编码契约即时生效** |
| 上游 | `WBS-3.8.5-lofi.md`（方向与界面说明书）+ `WBS-3.8.5-原型-审计界面与查询.html`（三屏线框）；后端契约实测源 = evidence-service 现行代码（3.8.2 `3571d09` 态，零改动消费） |
| 铁律重申 | 纯前端包：后端零代码改动（application.yml:28-29 注释回填两行除外）；零新第三方依赖；界面只读；不自建密码学；ApiError 原样展示；SourceGuard 纪律锚 |

## 1. 端点消费契约（10 端点，出参字段 → 界面元素逐字段映射）

> 全部经网关同前缀（生产）或 vite proxy（dev 5173 → `localhost:8090`）；出参均为 ADR-005 `ApiResult`（`code="0"` 解包 data）。**前端零契约改动、零二次加工**。

### 1.1 治理读面（5，`evidence.governance`；403 + 1000C0005 口径）

| 端点 | 出参 → 界面 | 消费点 |
| --- | --- | --- |
| `GET /api/v1/evidence/records?stageType&businessNo&status&from&to&pageNum&pageSize` | `PageData<EvidenceRecordView>`：evidenceNo / stageType / businessNo / operationType / subjectDidDigest / occurredAt / contentDigest / status / chainTxId / chainBlockNo / retryCount / lastErrorSummary / createdAt / updatedAt → 存证记录 tab 表列与详情抽屉六要素 | W5-A2 |
| `GET /api/v1/evidence/records/{evidenceNo}` | `EvidenceDetailView(record, actions[])`：actions = action / fromStatus / toStatus / **actorSubjectNo〔原文〕** / reasonCode / detail / createdAt → 抽屉留痕表 + 核验工具 B-3 留痕表 | W6 / W7 |
| `POST /api/v1/evidence/records/{evidenceNo}/verify` | `EvidenceVerifyView`：conclusion（PASS / MISMATCH / NOT_ON_CHAIN）/ evidenceNo / businessNo / status / expectedDigest / actualDigest / localDigest / chainTxId / verifiedBy〔原文〕/ verifiedAt → 三态结论卡（HTTP 恒 200，**结论不映射 HTTP 错误**） | W7 |
| `GET /api/v1/evidence/overview` | `EvidenceOverviewView(onChainCount / pendingCount / failedCount / lastFailedAt)` —— **本卡不消费**（总览 tab 用 E1 矩阵；此端点留给剧本接口承载，界面不调用，避免双读数） | 不消费 |
| `POST /api/v1/evidence/records/{evidenceNo}/retry` | —（写动作）**本卡不提供界面入口**（N9）；S2-7 重试保持剧本接口承载 | 不消费 |

### 1.2 监管审计读面（5，服务内守卫；400 + 1000C0003"记录不存在"同码同文口径）

| 端点 | 出参 → 界面 | 消费点 |
| --- | --- | --- |
| E1 `GET /api/v1/evidence/audit/overview?from&to` | `OverviewView(matrix: Map<环节大写, StageStatusView{PENDING, ON_CHAIN, FAILED}>〔@JsonProperty 大写键〕, anomalies{pendingCount, failedCount, mismatchCount}, snapshotAt)` → 总览矩阵表 + 三计数卡 + 快照时点 | W5-A1 |
| E2 `GET /api/v1/evidence/audit/anomalies?from&to&pageNum&pageSize` | `PageData<AnomalyItemView>`（18 字段：记录 14 字段 + anomalyType / verifyConclusion / verifiedBy〔摘要〕/ verifiedAt）→ 异常项 tab 表（异常类型标签列 + MISMATCH 附核验三列） | W5-A3 |
| E3 `GET /api/v1/evidence/audit/trails?stageType&businessNo&subjectDigest&from&to&pageNum&pageSize` | `TrailView` 双模：**TRAIL**（businessNo 命中）= evidenceRecord + evidenceActions[]〔actorDigest 摘要〕+ businessTrail[]〔source / occurredAt / actorDigest / action / result / summary / **sourceStatus = OK \| UNAVAILABLE**〕+ timelineAvailable；**LIST** = total / pageNum / pageSize / totalPages / list[] + trailHint → 时间线双段渲染 / 清单分页表；`mode` 字段判模 | W5-A4 |
| E4 `POST /api/v1/evidence/audit/reports`（body：stageType / businessNo / subjectDigest / from / to / remark，条件 ≥1） | `ReportView(reportNo, scope: Map, recordCount, conclusionSummary: Map, anomalyCount, contentDigest, remark, generatedBy, generatedAt)` → 生成结果卡（编号 / 范围 / 条数 / 结论分布徽标 / 异常数 / 内容摘要截断复制 / 生成要素） | W5-A5 |
| E5 `GET /api/v1/evidence/audit/reports/{reportNo}/export?format=csv\|json` | **双形态分流**：成功 = `byte[]` + `Content-Disposition: attachment; filename="…"` + `Content-Type: text/csv \| application/json` → Blob 另存；失败（完整性校验不过 1012S0003 等）= HTTP 500 + ApiResult JSON → 解析 message 原文弹出。前端按钮显式传 `format=csv` / `format=json` | W5-A5 |

## 2. 前端文件清单（新增 / 修改）

| 文件 | 动作 | 内容与预估（非空行） |
| --- | --- | --- |
| `frontend/src/api/evidence.ts` | 新增 | 10 类型 + 8 查询函数 + `downloadAuditReport(reportNo, format)` + `filters()` 空值过滤助手；URL 单点（~200） |
| `frontend/src/constants/evidence.ts` | 新增 | STAGE / STATUS / CONCLUSION / ANOMALY / ACTION 双 map（LABELS + TAG type）+ `labelOf()` 未知值原样（~80） |
| `frontend/src/stores/demoIdentity.ts` | 修改 | +`evidenceRolesHeader()` = `pageRolesHeader('admin', 'requester')`（普通档无治理角色 → 服务端拒，fail-closed；**实际普通档角色值以 P5 实测 pageRolesHeader 语义为准回填**）（+3） |
| `frontend/src/views/evidence/EvidenceAuditView.vue` | 新增 | 5 tab 页（~520） |
| `frontend/src/views/evidence/components/EvidenceRecordDrawer.vue` | 新增 | 详情抽屉（props：evidenceNo / 显隐；内部自取 detail；~210） |
| `frontend/src/views/evidence/EvidenceVerifyView.vue` | 新增 | 核验工具单页（~230） |
| `frontend/src/router/index.ts` | 修改 | +2 菜单路由（26 / 27）+ 注释行（+10） |
| `frontend/vite.config.ts` | 修改 | +`/api/v1/evidence` 条目（→ `http://localhost:8090`，通用 `/api` 键之前，注释沿交付域同款）（+6） |
| `frontend/src/api/evidence.spec.ts` | 新增 | api 单元（URL 单点 / filters / 分页串 / 身份头）（~120） |
| `frontend/src/views/evidence/EvidenceAuditView.spec.ts` | 新增 | 5 tab 用例（~350） |
| `frontend/src/views/evidence/components/EvidenceRecordDrawer.spec.ts` | 新增 | 抽屉用例（~130） |
| `frontend/src/views/evidence/EvidenceVerifyView.spec.ts` | 新增 | 三态卡 + 留痕表用例（~220） |
| `frontend/src/views/evidence/EvidenceSourceGuard.spec.ts` | 新增 | 静态纪律锚（~60） |
| `frontend/src/layouts/MainLayout.spec.ts` | 修改 | 计数断言：普通档 11 不变 / admin 25→27（±2） |
| `frontend/src/router/router.spec.ts` | 修改 | `Math.max(menuOrders)` 25→27（±2） |
| `docs/manuals/C-8.1-区块链存证-验收剧本.md` | 修改 | V1.3→V1.4：行 11 界面入口行 + 操作路径表首行界面路径（~8） |
| `docs/manuals/C-8.2-合规审计-验收剧本.md` | 修改 | V1.2→V1.3：行 11 + 行 64（首行 + S1-8 / S2-7 界面路径核对方式）+ 行 80（~8） |
| `services/evidence-service/src/main/resources/application.yml` | 修改 | :28-29 参与方读面评估注释终版回填（**注释级两行，零代码**）（±2） |

## 3. api 层契约（`api/evidence.ts`）

1. **URL 单点**：全部端点 URL 常量仅存在于本文件（沿 `api/billing.ts:5-11` 注释口径）；
2. **查询函数**：`listRecords(query)` / `getRecordDetail(no)` / `verifyRecord(no)` / `auditOverview(from?, to?)` / `auditAnomalies(q)` / `auditTrails(q)` / `generateReport(body)`——统一 `makeDomainJson(evidenceRolesHeader())` + `apiJson` 解包；分页走共享 `pageQuery`（复用，不建私有副本——DB-105 口径）；
3. **`downloadAuditReport(reportNo, format)`**：唯一非 apiJson 函数——原生 fetch + 身份头 → `res.ok` 判定 → 成功读 `Content-Disposition` 文件名（UTF-8 `filename*=UTF-8''…` 与 plain `filename="…"` 双正则，沿 `delivery.ts:184-227`）→ Blob 交视图触发 `a[download]`；**失败分支**：读 JSON → ApiResult → 抛 `ApiError(code, message)`（原文透传）。函数头**逐字注释**："裸 fetch 豁免 №2（WBS-3.8.5 Q4-A；№1 = delivery.ts downloadDeliveryPackage）：文件响应非 JSON，须独立通道"；
4. **类型**：`EvidenceRecordView` / `EvidenceActionView` / `EvidenceVerifyView` / `AuditOverview` / `AnomalyItem` / `TrailView`（TRAIL / LIST 两接口 + `mode` 判别）/ `ReportView` + 查询参数接口；`PageData<T>` 复用 `api/types.ts`；
5. **零状态管理**：不建 pinia / 不建全局 store——页面局部状态（沿全仓先例）。

## 4. 视图组件契约

| 组件 | 契约 |
| --- | --- |
| `EvidenceAuditView` | `el-tabs` v-model 五面懒加载（首次激活时拉数）；tab = audit-overview / audit-records / audit-anomalies / audit-trails / audit-reports；页内 tab 跳转（总览计数卡点击 → anomalies）；每 tab 独立 loading / error（el-alert + 重试）/ empty 三态；**零写按钮** |
| `EvidenceRecordDrawer` | props `{ evidenceNo: string, visible: boolean }` + emit `update:visible`；打开时自取 detail；el-drawer size 480（沿交付先例）；sections：六要素 el-descriptions（2 列）+ actions el-table；**不提供任何动作按钮**（含核验——核验入口唯核验工具页，Q2-A 单一入口） |
| `EvidenceVerifyView` | 单卡流：输入 `evidenceNo` → `verifyRecord` → 结论卡（conclusion 三态 v-if）+ `getRecordDetail(no).actions` 留痕表（核验后同步刷新）；MISMATCH 卡三摘要等宽截断 + 复制；NOT_ON_CHAIN 卡用出参 `status` + `lastErrorSummary`（由 records 表行或 verify 出参 status 判定文案）；表单回车提交；空编号前置提示（纯 UI 非业务校验） |
| 通用 | 时间显示 = 出参 ISO 字符串原样；摘要字段（`*Digest` / `*DidDigest` / `actorDigest` / `verifiedBy〔审计面〕`）等宽 + 中截断 + `navigator.clipboard` 复制（ElMessage 成功提示）；`labelOf()` 兜底未知枚举原样显示 |

## 5. 权限与身份

1. **菜单与守卫**：两路由 `meta.permission = 'evidence.governance'`——普通档菜单不渲染（MainLayout computed）、直达 URL → `/dashboard?denied=1`（既有守卫零改动）；页面**不**做二次权限判断；
2. **身份头**：`evidenceRolesHeader()` 页面级注入（普通档不带 admin → 服务端 403 / 400 拒，错误原样展示——fail-closed 演示即 C-8.2 S1-7 界面面）；`X-Ctds-Subject` 全局头沿 client.ts；
3. **两类错误口径并存**（前端零加工）：治理族 403 + 1000C0005；审计族 400 + 1000C0003"记录不存在"（同码同文防枚举——**前端不得据此做任何"存在性"分支**）；
4. **无角色字面量散落**：角色串仅出现在 `demoIdentity.ts`（SourceGuard 断言）。

## 6. 运行与演示口径

1. **dev 链路**：`npm run dev`（5173）→ vite proxy `/api/v1/evidence` → `localhost:8090`（evidence 服务）；演示链 = `build-output/w384-demo/w384-demo-start-c81.ps1` 九服务（无 gateway / frontend——**与 3.8.4 演示口径一致，接口路径演示不受影响**）；
2. **界面路径演示**（剧本 V1.4 / V1.3 修订后）：浏览器 5173 登录（localStorage `ctds-demo-auth=1` + `ctds-demo-role=admin`）→ 菜单直达；普通档负向 = 切 `ctds-demo-role=user`；
3. **nginx 容器零改动**（无 /api 反代，沿 3.2.6/3.3.6/3.4.7/3.7.7 界面包先例——部署面无 API 代理，界面演示走 dev 5173）；
4. **演示数据**：沿 DB-64 ⑥ 段登记态（四环节 1/1/1/2 + FAILED 1 + 5 条 ON_CHAIN），**零造数**（DB-64 严禁脚本造数清理）。

## 7. 前置检查（编码段第一动作，逐项实测留痕）

| 编号 | 检查 | 通过判据 |
| --- | --- | --- |
| P1 | evidence 服务起线（演示链 c81 脚本）+ 治理 / 审计 10 端点 curl 探活（admin 身份头） | E1 矩阵 1/1/1/2+1 FAILED 实测与 hifi §1 出参逐字对照 |
| P2 | 前端基线全绿 | `npm run test` 65 spec 849 例 EXIT=0（含重型用例超时口径沿 DB-48） |
| P3 | vite proxy 键序确认 | `/api/v1/evidence` 条目插入后位于通用 `/api` 键之前；dev 5173 探针 `/api/v1/evidence/audit/overview` 透传 8090 |
| P4 | 菜单计数现状复核 | MainLayout.spec 11 / 25 + router.spec max=25 三处断言实测定位（改前基线） |
| P5 | `pageRolesHeader(admin, normal)` 语义实测 | 确认第二参为普通档角色串口径 → 回填 `evidenceRolesHeader` 实参 |
| P6 | SourceGuard 范式文件确认 | BillingSourceGuard.spec.ts 四类断言逐条读取（复制为 evidence 版底稿） |
| P7 | 下载先例逐行确认 | delivery.ts downloadDeliveryPackage 双正则 + Blob 流程（含 UTF-8 文件名分支） |
| P8 | E5 失败形态实测 | 构造或复用既有 1012S0003 场景（或以 404 报告号等价验证 HTTP 500 / ApiResult JSON 分流分支——以可构造者为准，如实登记） |

## 8. 测试锚点（T1~T16，映射剧本步骤；测试先行，红相归档 `build-output/w385-red-*`）

| 编号 | 用例 | 剧本 / 规格 |
| --- | --- | --- |
| T1 | 菜单计数三处断言同步（11 不变 / admin 27 / max=27）+ 两新菜单权限过滤（普通档不可见） | C-8.2 S1-7 界面面 |
| T2 | 总览 tab：E1 矩阵渲染（四环节行×三状态列，失败 >0 红色）+ 三计数卡 + snapshotAt；加载 / 错误 / 空态 | C-8.2 S1-1 |
| T3 | 存证记录 tab：五维过滤请求参数拼接（filters 空值剔除）+ 分页 + 行点击开抽屉 | C-8.2 S1-3 |
| T4 | 详情抽屉：六要素 descriptions + actions 留痕表（核验行 VERIFIED 渲染）+ 零动作按钮断言 | C-8.1 S2-8 / C-8.2 S1-8 |
| T5 | 异常项 tab：三类标签 + MISMATCH 行核验结论 / 核验人〔摘要〕/ 时点列 | C-8.2 S1-1 |
| T6 | 时间线 TRAIL：单笔卡 + 双段 timeline 节点（存证事件 + 业务留痕）+ **UNAVAILABLE 红色降级节点 + 警示条断言** + 调阅留痕灰字 | C-8.2 S1-2 / S1-4 / S1-6 |
| T7 | 时间线 LIST：分页表 + trailHint 展示；mode 判模分支 | C-8.2 S1-3 |
| T8 | 错误原文呈现：零条件 1012C0002 / 宽窗超限 / 守卫 400+1000C0003 / 403+1000C0005 → ApiError.message 原样 el-alert（不转译断言） | C-8.2 S1-5 / S1-7 |
| T9 | 报告生成：条件表单（≥1 条件引导灰字 + remark）→ E4 请求体 → 结果卡全字段（编号 / 范围 / 条数 / 分布 / 异常 / 摘要截断复制） | C-8.2 S2-1 / S2-2 |
| T10 | 报告下载：`downloadAuditReport` 双格式（Content-Disposition 解析 + Blob 触发 a[download]）+ 失败 ApiError 原文弹出；按号补下载区 | C-8.2 S2-4 / S2-7 |
| T11 | 核验三态卡：PASS 字段 / MISMATCH 三摘要定位字段 / NOT_ON_CHAIN 当前状态提示文案（status 驱动） | C-8.1 S2-1 / S2-3 / S2-5 |
| T12 | 核验留痕表渲染 + 核验后刷新；空编号前置提示 | C-8.1 B3-3 |
| T13 | 路由守卫：普通档直达 /evidence → denied=1；admin 放行 | C-8.2 S1-7 |
| T14 | api/evidence 单元：URL 单点 / pageQuery 复用 / 身份头注入 / filters | —（DB-41/105 口径） |
| T15 | SourceGuard：URL 单点 / 零 v-html / 零角色字面量散落 / **裸 fetch 全域恰 2 处**（delivery №1 + evidence №2 豁免注释锚逐字断言） | C-8.1 S2-9 / B5-3 |
| T16 | 脱敏观察点：审计面组件渲染出口不含主体编号原文（mock 数据驱动断言——渲染原样、加工为零） | C-8.2 S1-8 / S2-6 |

## 9. 剧本映射与诚实边界

1. **修订范围**（Q7-A，业务判定与步骤编号一字不改）：C-8.1 行 11（界面入口 = 存证核验工具页〔屏 B〕+ 监管审计页总览 tab 承载 S1-1 / S2-6 总览读数）+ 操作路径表首行界面路径；C-8.2 行 11（界面入口 = 监管审计页五 tab〔屏 A〕）+ 行 64（首行 + S1-8"界面路径"核对方式 = 界面渲染抽查 + S2-7 = 界面按号下载重算）+ 行 80 联动行；两版均提示 PO 批准；
2. **S1-6 调阅留痕**：界面灰字提示 + 查库核对（evidence_access_log），前端零重复留痕；
3. **S2-8 留存边界**：界面不展示留存期限承诺；交付说明沿承 B8-2 / B8-4"待生效"逐字口径；
4. **DB-115**：界面文案与交付说明零"真实区块链 / 对外出证"表述；链上标识字段名沿出参（chainTxId / chainBlockNo）；
5. **豁免登记**：裸 fetch №2（Q4-A）；除此之外 SourceGuard 其余纪律与全仓一致。

## 10. 体量与门禁

1. 体量：主源 ~1400~1700 + 测试 ~900~1200 ≈ **2300~2900 行**（超 400 行登记制豁免沿 3.8.3 / 3.8.4，理由 = 两页面共享底座 + 契约映射注释密度 + 三态/双模分支测试）；
2. 门禁面：gates-config.json 零改动（ConfigSha256 与基线逐字一致）；全仓 compile / checkstyle 零影响（唯一后端文件 = application.yml 注释两行——checkstyle 不涉 yml）；前端四步（typecheck / build / lint / test）EXIT=0；
3. 覆盖率：前端不计入 JaCoCo；后端零代码改动 → evidence 68 例基线零回归；
4. 评审与验收关口沿 3.8.4 流程（4 视角 ≤3 轮 + 独立复审 → 编排师验收 → 推送 / PR / 合并显式指示）。
