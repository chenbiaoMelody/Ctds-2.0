# WBS-3.9.5 高保真设计（系统管理界面 · 前端编码契约）V1.0

| 项 | 内容 |
| --- | --- |
| 版本 | **V1.0（编码契约即时生效，2026-11-15 编排师一次确认）**——编排师会话回复"全部按建议确认（推荐）"（Q1~Q4 均采建议口径 A + D1 不拆分），与 lofi / 原型同批转 V1.0；V0.9 = 立卡草案（2026-11-15）。编码段前置检查发现与实测不符时，按 §9 E-勘误位回填（纯勘误），不得私自补需求 |
| 上游 | `WBS-3.9.5-lofi.md`（方向与界面说明书）+ `WBS-3.9.5-原型-系统管理界面.html`（四屏线框）；后端契约源 = evidence-service / connector-service 现行代码（3.9.2 PR #37 / 3.9.3 / 3.9.4 PR #40 已合并态，**零改动消费**；端点出参字段名以 §1 为准，编码段前置检查 P2 实测回填） |
| 铁律重申 | 纯前端包：后端零代码改动；零新第三方依赖；界面只读（写动作仅处置对话框 + 授予收回表单两处）；ApiError 原样展示；SourceGuard 纪律锚；零门禁配置改动 |

## 1. 端点消费契约（9 端点，出参字段 → 界面元素逐字段映射）

> 全部为既有端点（3.9.2/3.9.3/3.9.4 已交付）；字段名以本期实测为准，前置检查 P2 留痕样本于 `build-output/w395-precheck-data/`。

### 1.1 RBAC 面（4，`/api/v1/rbac`，evidence-service；越权 403 + `1000C0005` 文案统一）

| 端点 | 挂点（持方） | 出参 → 界面 | 消费点 |
| --- | --- | --- | --- |
| `GET /api/v1/rbac/roles` | `connector.health.read`（sysadmin） | `List<RbacRoleView{role, permissions[]}>` → 清单 tab 逐角色行（三员在前排序仅前端展示序，不做数据加工） | W7-C1 |
| `POST /api/v1/rbac/permissions/grant` | `connector.review`（secadmin） | 入参 `{role, permission, changeOrderNo}`（三元组幂等键；变更人 = `X-Ctds-Subject` 头由 client 统一带）→ 成功回显 + 提示留痕可查 | W7-C2 |
| `POST /api/v1/rbac/permissions/revoke` | `connector.review`（secadmin） | 同上（动作单选区分） | W7-C2 |
| `GET /api/v1/rbac/changes?pageNum&pageSize` | `evidence.governance`（audadmin） | `PageResult<RbacChangeView{changeNo, changeOrderNo, changedBy, changedAt, roleCode, permissionPoint, beforeValue, afterValue}>` → 留痕 tab 八列分页 | W7-C3 |

### 1.2 部署域面（1，`/api/v1/platform/domain`，evidence-service）

| 端点 | 挂点（持方） | 出参 → 界面 | 消费点 |
| --- | --- | --- | --- |
| `GET /api/v1/platform/domain` | `platform.domain.read`（sysadmin，V4） | `DomainView{domainId, domainName, platformVersion, lifecycleStatus〔ACTIVE·RETIRED〕, schemaList, serviceList, configConsistency〔CONSISTENT·NOT_APPLICABLE〕, scopeNote}` → 八字段 descriptions（scopeNote **原文**；lifecycleStatus / configConsistency 徽标映射）；**配置不一致 = HTTP 503 + `1013S0002`** → 整页错误态原文 + 重试（不显示"正常"）；退役态本端点豁免可读 | W8-D1/D2 |

### 1.3 监控面（3，`/api/v1/platform/**`，evidence-service；未接入 = 显式标注非 0）

| 端点 | 挂点（持方） | 出参 → 界面 | 消费点 |
| --- | --- | --- | --- |
| `GET /api/v1/platform/metrics` | `platform.monitor.read`（sysadmin，V5） | `MetricsView{services[]}`，行 `MonitorServiceView{service, job, integrated〔INTEGRATED·NOT_INTEGRATED〕, alive〔UP·DOWN·未接入=null〕, heapUsedBytes, heapMaxBytes, gcPauseSecondsRate5m, httpRatePerSec1m, errorRate5xx5m, p95LatencyMs5m, note}` → 四区块列；**`NOT_INTEGRATED` 行 = "未接入"徽标 + note 原文（"未接入指标采集，读数不可用"），读数列不渲染 0**；`alive=null` 仅在未接入行出现 | W5-A1 |
| `GET /api/v1/platform/alerts` | `platform.monitor.read` | `AlertsView{alerts[]}`，行 `AlertItemView{alertName, severity〔critical·warning〕, target, triggeredAt, currentValue, threshold, state〔firing·resolved〕, stateNote}` → 七要素列 + stateNote 灰字原文；封闭两规则固定入列（`CtdsBackendDown` / `CtdsBackendHighErrorRate`），前端零过滤零增删 | W6-B1 |
| `POST /api/v1/platform/alerts/actions` | `platform.alert.handle`（sysadmin，V5） | 入参 `DisposeRequest{alertName, action〔ACKNOWLEDGE·SILENCE·ESCALATE〕, note〔可空〕, durationMinutes〔仅 SILENCE，缺省 30，上限 1440〕}` → 出参 `DisposeView{alertName, action, result=OK, silenceId〔仅静默〕, recordedAt}` → 响应回显卡 + 列表刷新；**ESCALATE 回显附"通知外发通道待生效（G-04），以平台留痕为准"** | W6-B2/B3 |

### 1.4 连接器治理面（1，既有端点加性消费）

| 端点 | 挂点（持方） | 出参 → 界面 | 消费点 |
| --- | --- | --- | --- |
| `GET /api/v1/connectors/governance/connectors`（既有，分页 + status 过滤） | `connector.health.read` | `ConnectorView` **加性字段 `healthStatus〔ONLINE·OFFLINE·NEVER_REPORTED〕`**（3.9.4 交付）+ 既有 `online` 布尔 + `lastHeartbeatAt` → 治理页"健康状态"三态列（ONLINE→在线〔success〕/ OFFLINE→离线〔info〕/ NEVER_REPORTED→**从未上报**〔warning〕）+ 概览条三数；**既有 `online` 布尔列停止展示**（Q4-A 单列）；`api/connector.ts` `ConnectorRecord` 补 `healthStatus?: string` 类型 | W9 |

## 2. 前端文件清单（新增 / 修改）

| 文件 | 动作 | 内容与预估（非空行） |
| --- | --- | --- |
| `frontend/src/api/platform.ts` | 新增 | 9 类型 + 7 消费函数（roles/grant/revoke/changes/domain/metrics/alerts/actions）+ 域工厂两枚（`rbacJson` / `platformJson`）；URL 单点（~230） |
| `frontend/src/constants/platform.ts` | 新增 | LIFECYCLE / HEALTH_STATUS / INTEGRATED / ALERT_STATE / SEVERITY / DISPOSE_ACTION / CONSISTENCY 双 map + `labelOf()` 未知值原样（~90） |
| `frontend/src/stores/demoIdentity.ts` | 修改 | evidence 运营档 `admin`→`platform.audadmin`（DB-128）+ connector 运营档 `admin`→Q3 裁决组合 + `platformRolesHeader()` / `rbacRolesHeader()`（随三员视角）+ 三员视角状态（localStorage 键 `ctds-demo-admin-persona`，默认 `sysadmin`，切换触发既有 `markDemoIdentityChanged()` 信号）（+45） |
| `frontend/src/layouts/MainLayout.vue` | 修改 | 顶栏切换器：运营档时显示"三员视角"下拉（系统管理员〔默认〕/ 安全管理员 / 审计管理员），保存动作复用既有信号（+12） |
| `frontend/src/router/index.ts` | 修改 | +4 菜单路由（menuOrder 28~31，meta permission：`platform.monitor.read` ×2 / `connector.health.read` / `platform.domain.read`）+ 注释（+18） |
| `frontend/vite.config.ts` | 修改 | +`/api/v1/rbac`、`/api/v1/platform` 两键（→ `http://localhost:8090`，通用 `/api` 键之前，键序先行注释沿既有）（+10） |
| `frontend/src/views/platform/MonitorView.vue` | 新增 | 监控读数页（读数表 + 未接入标注 + 诚实边界条）（~380） |
| `frontend/src/views/platform/AlertView.vue` | 新增 | 告警与处置页（七要素表 + 处置对话框 + 回显）（~420） |
| `frontend/src/views/platform/RbacView.vue` | 新增 | 角色与权限页（el-tabs 三标签）（~560） |
| `frontend/src/views/platform/DomainView.vue` | 新增 | 部署域信息页（descriptions + 错误态）（~230） |
| `frontend/src/constants/connector.ts` + `api/connector.ts` + `views/connector/ConnectorGovernanceView.vue`（管理/详情页共用 helper 同步） | 修改 | 三态双 map（HEALTH 三值 LABELS/TAG）+ `ConnectorRecord.healthStatus` + "在线"列改"健康状态"三态列 + 概览条三数（~60） |
| `frontend/src/api/platform.spec.ts` | 新增 | api 单元（URL 单点 / 分页串 / 域头随视角 / 处置参数边界）（~140） |
| `frontend/src/views/platform/MonitorView.spec.ts` | 新增 | 四区块 + 未接入标注用例（~260） |
| `frontend/src/views/platform/AlertView.spec.ts` | 新增 | 七要素 + 处置对话框（动作/时长边界/回显/ESCALATE 提示）用例（~300） |
| `frontend/src/views/platform/RbacView.spec.ts` | 新增 | 三标签 + 视角显隐 + 表单校验（变更单号必填）+ 403 原文用例（~320） |
| `frontend/src/views/platform/DomainView.spec.ts` | 新增 | 八字段 + fail-closed 错误态 + 退役态用例（~160） |
| `frontend/src/api/PlatformSourceGuard.spec.ts` | 新增 | 静态纪律锚（URL 单点 / 零 v-html / 零角色字面量散写 / 裸 fetch 零豁免）（~60） |
| `frontend/src/stores/demoIdentity.spec.ts` | 修改 | DB-128 断言更新（evidence=audadmin / connector=裁决组合 / platform·rbac 随视角）+ 既有锚点回归（普通档不带 admin）（~60） |
| `frontend/src/layouts/MainLayout.spec.ts` + `frontend/src/router/router.spec.ts`（路径以实测为准） | 修改 | 计数断言三处：普通档 11 不变 / admin 27→31 / max 27→31（±8） |
| `frontend/src/views/connector/ConnectorGovernanceView.spec.ts` 等 | 修改 | 三态列 + 概览三数用例扩展（~80） |

## 3. api 层契约（`api/platform.ts`）

- `const rbacJson = makeDomainJson(rbacRolesHeader())` / `const platformJson = makeDomainJson(platformRolesHeader())`——两域头函数随三员视角（§5）；`pageQuery` 复用 `client.ts` 既有实现（DB-41：不建第三份分页副本）；
- URL 常量单点：`RBAC_ROLES = '/api/v1/rbac/roles'` 等 9 条，视图零 URL（SourceGuard 断言）；
- 处置函数签名 `disposeAlert(req: DisposeRequest): Promise<DisposeView>`——`durationMinutes` 仅 SILENCE 传、其余 undefined（后端缺省 30）；前端对 `>1440` / 静默未填 给出控件级约束（数字输入 max=1440 + 缺省 30），越界最终裁决在后端（前端不复制规则副本，仅体验引导）；
- `changeOrderNo` 前端必填校验（空值拦截 + 占位提示），提交后成败均原文呈现。

## 4. 视图组件契约

- **MonitorView**：挂载即拉 metrics；列渲染按 §1.3；`integrated==='NOT_INTEGRATED'` 行：存活列"未接入"灰徽标、读数四列合并显示 note 原文；页底 `infoline` 诚实边界条（静态文案常量）；加载 / 空态 / 错误三态齐备；
- **AlertView**：挂载即拉 alerts；处置按钮仅 `platform.alert.handle` 可用视角渲染（§5 映射：系统管理员视角）；对话框动作单选驱动时长输入显隐；提交成功 → 回显卡（result / silenceId〔复制〕/ recordedAt / ESCALATE 提示）+ 重拉列表；失败 → ElMessage.error 原文；
- **RbacView**：el-tabs 三标签（懒加载沿 EvidenceAuditView 惯例：无条件型 tab 首次激活自动拉，条件型由用户驱动）；清单 tab 自动拉；授予收回 tab 表单（视角不符时表单仍渲染、提交后 403 原文呈现——**不做前端预判拦截以外加规则**，负向判定权威在后端）；留痕 tab 分页表；
- **DomainView**：挂载即拉 domain；成功 → descriptions 八字段；`configConsistency` 徽标；503 → 整页错误态（el-alert 原文 + 重试）；`lifecycleStatus==='RETIRED'` → 灰徽标 + 信息卡照常（端点豁免可读）；
- 全部页面：页头（title + 说明灰字）+ `el-card` 承载 + size=small 表格 + #empty 插槽。

## 5. 权限与身份（DB-128 承接 + 三员视角）

| 域 | 头函数 | 普通主体档（subject） | 运营档（operator） |
| --- | --- | --- | --- |
| platform 新域（metrics/alerts/actions/domain） | `platformRolesHeader()`（新） | 无管理角色（服务端拒，fail-closed） | 随三员视角：sysadmin / secadmin / audadmin |
| rbac 域（roles/grant/revoke/changes） | `rbacRolesHeader()`（新） | 同上 | 随三员视角（同上） |
| evidence 域（既有） | `evidenceRolesHeader()` | `requester`（不变） | **`platform.audadmin`**（DB-128 修复，原 `admin`） |
| connector 域（既有） | `connectorRolesHeader()` | `applicant`（不变） | **Q3-A 裁决组合 `platform.secadmin,admin`**（核验写面 + 既有读面零回归；原 `admin`） |
| 其余域 | 既有函数 | 零改动 | 零改动 |

- **三员视角状态**：localStorage 键 `ctds-demo-admin-persona`，值 `sysadmin`〔默认〕/ `secadmin` / `audadmin`；仅运营档显示切换下拉；切换触发 `markDemoIdentityChanged()` → `router-view :key` 整页重挂重拉（复用既有机制，零新机制）；
- **菜单可见性**：4 新菜单 `meta.permission` 挂真实权限点（`platform.monitor.read` / `connector.health.read` / `platform.domain.read`）；`hasPermission` 占位判定（有点仅 admin 放行）下可见性 = admin 演示档，与既有治理菜单口径一致；**真实权限点 key 为诚实标注**（规格 :259 已注明前端菜单权限点与后端口径不完全一致属既有事实，收敛以实现为准，本卡不扩占位判定语义）；
- **既有锚点回归**：`demoIdentity.spec` 既有硬约束（普通档不得携带 admin，T2/T13 口径）零破坏；8 个 `getActorMode()` 硬编码分支视图零改动。

## 6. 运行与演示口径

- **dev 代理**：`/api/v1/rbac`、`/api/v1/platform` → `http://localhost:8090`（evidence-service，与既有 evidence 域键同款；插入位置在兜底 `/api` 键之前——键序先行）；连接器治理沿用既有 connector 域键（8088）；
- **后端运行期前提**（演示/走查环境）：`CTDS_AUTH_RBAC_SOURCE=jdbc` + `CTDS_AUTH_RBAC_SCHEMA=ctds_evidence`（application.yml config 兜底含 admin 旧点为非运行期事实，DB-128 复现与验证均以 jdbc 为准）；
- **演示链**：沿"演示链九服务在跑"口径（runbook §3.1/§3.3）；真机走查（关闭条件④）= 监管审计页 + 连接器治理·核验面 200，另加四新页主流程走查；
- **e2e**：不扩展（N6，门禁 disabled = PENDING-CI）。

## 7. 前置检查（编码段第一动作，逐项实测留痕 `build-output/w395-precheck-*`）

- **P1**：确认裁决回读（Q1~Q4 + D1 落定口径）+ `demoIdentity.ts` / `MainLayout.spec` / `router.spec` 现行断言清单提取（锚点行号以实测为准）；
- **P2**：真机实测 9 端点出参形状（演示链在跑 + jdbc 环境）：roles/changes/domain/metrics/alerts 各存样本 JSON 至 `build-output/w395-precheck-data/`，字段名与 §1 逐一对账，**不一致处按 §9 E-勘误位回填**；
- **P3**：connector 治理端点 `healthStatus` 三态实测（盘点既有演示数据三态覆盖；不足则按剧本 S1-4~S1-7 既有构造手段补心跳/停报样本）；
- **P4**：处置 API 参数边界实测（`durationMinutes` 0 / 1441 / 静默缺省；非法 action；错误体形状）；
- **P5**：越权错误体实测（403 + `1000C0005` 文案原文；503 + `1013S0002` 原文）——确定前端呈现字面；
- **P6**：vite proxy 现行键序与兜底行为实测（新增两键插入位验证）；
- **P7**：三员视角切换后各端点双向矩阵实测（3 视角 × rbac 4 端点 + platform 3 端点 = 逐格留痕，S1-4/S1-12/S2-7 界面演示动线依据）；
- **P8**：MainLayout 切换器扩展点与 `demoIdentityRevision` 信号机制实测（切换后重挂行为确认）。

## 8. 测试锚点（T1~T15，映射任务卡 §三；测试先行，红相归档 `build-output/w395-red-*`）

| 编号 | 用例 | 断言要点 |
| --- | --- | --- |
| T1 | RbacView·清单 tab | `GET roles` stub 三员 + 业务角色逐行渲染；权限点等宽标签；排序仅展示序 |
| T2 | RbacView·留痕 tab | 分页八列渲染（beforeValue/afterValue 原文）；翻页参数正确 |
| T3 | RbacView·授予收回 | 三元组传参断言；`changeOrderNo` 空值前端拦截；成功回显 |
| T4 | 越权呈现 | ApiError 原文 el-alert（403 文案含"无权限执行该操作"）；普通档菜单不可见 + 直达 URL denied |
| T5 | 视角显隐矩阵 | 3 视角 × （清单/授予收回/留痕/处置按钮）可见性与 403 面断言 |
| T6 | DomainView·八字段 | scopeNote 原文；lifecycleStatus/configConsistency 徽标映射；零操作按钮（结构断言） |
| T7 | DomainView·fail-closed | 503 → 错误态原文 + 重试；不渲染"正常"态 |
| T8 | MonitorView·四区块 | UP/DOWN/读数列渲染；同源口径说明条 |
| T9 | MonitorView·未接入 | NOT_INTEGRATED 行徽标 + note 原文；**断言不出现 0 值渲染** |
| T10 | connector 三态 | healthStatus 三值映射；NEVER_REPORTED ≠ 离线；概览三数；既有用例回归 |
| T11 | AlertView·七要素 | 七列 + stateNote 透传；两规则固定入列（前端零过滤） |
| T12 | AlertView·处置 | 动作单选 → 请求体断言（durationMinutes 仅 SILENCE / 缺省 30 / max 1440 控件约束）；回显 silenceId；ESCALATE 提示文案 |
| T13 | AlertView·双态 | firing→已触发〔红〕/ resolved→已恢复〔灰〕；恢复后条目仍可见（列表权威 = 读面） |
| T14 | 菜单纪律 | 计数断言三处同步（11 / 31 / max 31）；4 新路由 meta title/icon/permission 齐备 |
| T15 | demoIdentity 断言 | evidence=audadmin / connector=裁决组合 / platform·rbac 随视角；普通档零 admin 回归 |

## 9. 剧本映射与诚实边界（+ E-勘误位）

- **剧本回填（PO 批准义务）**：C-10.1 V1.4→V1.5——:11 占位落定（「角色与权限」= `/platform/rbac`、「部署域」= `/platform/domain`）+ 操作路径表界面路径回填（S1-1/S1-5/S1-6/S1-10/S1-12/S2-1/S2-6/S3-1/S3-2 相关行，S1-3~S1-9 与 S3-3~S3-8 保持 API/runbook/既有审计面承载不在回填范围）；C-10.2 V1.2→V1.3——:11 占位落定（监控读数页 / 告警列表页）+ S1-1/S1-3/S1-4~S1-7/S2-1/S2-6/S2-7 相关行回填；C-8.2 V1.5→V1.6——界面路"暂不可用"注记恢复（DB-128 闭环）；业务判定与步骤编号零变更；
- **诚实边界**：未接入不显示 0/绿（B5-R4）/ "从未上报"≠离线（B6-R1）/ 越权与不可用原文呈现（B3-AC1）/ ESCALATE 与监控页"待生效"标注（G-04/G-07/不持久化/单副本/无集群层指标）/ 无删除伪造通道（B8-R2）/ fail-closed 不显示"正常"（B9-R4）/ DB-128 以真机走查为准不虚报；
- **E-勘误位**（V1.0 生效后编码段专用）：E-1 ______；E-2 ______；E-3 ______（前置检查实测与本文不符时逐条回填，正文语义不改，勘误即登记）。

## 10. 体量与门禁

- **体量**：预估 3200~4000 行（§2 明细合计；D1 不拆分，登记制豁免沿 3.8.5/3.8.6/3.9.2~3.9.4 先例）；
- **门禁**：`frontendTypeCheck`（vue-tsc）+ `frontendLint`（eslint）+ `frontendTest`（vitest，600s 预算）三关必过；`frontendE2E` disabled 不拦；后端零改动 → 全仓 `run-gates.ps1` 单发 GREEN 零回归预期（零门禁配置改动，ConfigSha256 与基线逐字一致）；测试重型用例按既有惯例逐例显式超时，**禁改 `test.testTimeout` 门禁配置**；
- **测试惯例**（沿全仓先例）：每 spec 自建 memory-history router（防跨文件污染）/ `global.plugins: [ElementPlus, router]` / `vi.mock` 域 api + `vi.mocked` / `beforeEach localStorage.clear()` / 异步后 `flushPromises`；el-table 断言前 `flushPromises`；computed 内不读 localStorage 函数（响应性坑，沿 frontend 记忆）。
