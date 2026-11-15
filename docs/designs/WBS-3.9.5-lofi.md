# WBS-3.9.5 低保真设计（系统管理界面 · 界面类）V1.0

| 项 | 内容 |
| --- | --- |
| 版本 | **V1.0（方向定稿，2026-11-15 编排师一次确认）**——编排师会话回复"全部按建议确认（推荐）"（Q1~Q4 均采建议口径 A + D1 不拆分），本文件与原型 / hifi 同批转 V1.0 = 编码契约；V0.9 = 立卡草案（2026-11-15） |
| 配套原型 | `docs/designs/WBS-3.9.5-原型-系统管理界面.html`（静态线框四屏：① 监控读数 ② 告警与处置 ③ 角色与权限 ④ 部署域；示例数据 = 演示链口径虚构样例，仅示意布局） |
| 规格派生声明 | 本设计只从规格 `C-10.1-10.2 V1.2`（B1 / B2 / B3-AC1 / B4-R3 / B9 / B5 / B6 / B7 / B8 + 非目标 3 / 边界 8）与既有已验收后端能力（3.9.2 rbac 4 端点 / 3.9.3 domain 1 端点 / 3.9.4 platform 3 端点 + connector 治理端点加性字段）派生；**未新增任何规格外需求**；发现规格缺口走变更流程 |

## 1. 做什么 / 不做什么

**做什么（W1~W10）**：

| 编号 | 内容 | 承载 |
| --- | --- | --- |
| W1 | 路由与菜单：扁平追加 4 菜单（"监控读数" menuOrder 28〔挂 `platform.monitor.read`〕/ "告警与处置" 29〔挂 `platform.monitor.read`〕/ "角色与权限" 30〔挂 `connector.health.read`〕/ "部署域" 31〔挂 `platform.domain.read`〕，均 admin 演示档可见）；菜单计数断言三处同步（普通档 11 不变 / admin 27→31 / max 27→31） | `router/index.ts` + `MainLayout.spec.ts` + `router.spec.ts` |
| W2 | api 横切模块：platform 域 7 端点消费函数（rbac grant/revoke/roles/changes + domain + metrics/alerts）+ 处置 actions 函数 + 类型定义；URL 单点 | `api/platform.ts` |
| W3 | 常量层：生命周期 / 健康三态 / 集成态 / 告警状态与级别 / 处置动作 / 一致性 双 map（LABELS + TAG type），未知枚举值原样显示 | `constants/platform.ts` |
| W4 | 身份层：`platformRolesHeader()`（platform 域随三员视角）+ `rbacRolesHeader()`（rbac 域随三员视角）+ evidence 运营档 `platform.audadmin` + connector 运营档裁决组合（Q3）；MainLayout 切换器扩展"三员视角"下拉（运营档时显示） | `stores/demoIdentity.ts` + `MainLayout.vue` |
| W5 | 监控读数页：四区块读数表（服务 / 存活 / 堆内存与 GC / HTTP 速率 / 5xx 与 P95）+ 未接入显式标注行 + 诚实边界提示条 | `views/platform/MonitorView.vue` |
| W6 | 告警与处置页：七要素列表（封闭两规则固定入列）+ firing/resolved 双态徽标 + 处置对话框（确认/静默〔时长〕/升级）+ 处置响应回显 | `views/platform/AlertView.vue` |
| W7 | 角色与权限页：三标签——权限清单（只读，逐角色权限点）/ 授予与收回（表单三元组 + 变更单号必填）/ 变更留痕（分页八列） | `views/platform/RbacView.vue` |
| W8 | 部署域信息页：八字段只读（标识/名称/版本/生命周期状态/schema 清单/服务清单/一致性结论/口径声明 scopeNote 原文）+ fail-closed 错误态呈现 | `views/platform/DomainView.vue` |
| W9 | 连接器健康三态呈现：治理页"在线"列升级"健康状态"三态列 + 概览条三数；管理页/详情页共用 helper 同步（Q4） | `views/connector/*` + `constants/connector.ts` + `api/connector.ts` |
| W10 | vite proxy 两键（`/api/v1/rbac`、`/api/v1/platform` → `localhost:8090`，键序先行）+ SourceGuard 纪律锚 spec + api/视图测试群 | `vite.config.ts` + spec 群 |

**不做什么（N1~N9）**：

| 编号 | 不做 | 理由与去向 |
| --- | --- | --- |
| N1 | 阈值/告警规则编辑 UI | B7-R2 规则封闭两条（新增/修改须任务卡与设计流程）+ B7-R4 阈值取值 3.9.4 已设计落定 + B8-R3 阈值配置无在线 API；做编辑页 = 越界须走规格变更（Q1-B 不采） |
| N2 | 部署域生命周期操作按钮（开通/升级/退役） | B9-R3：生命周期动作由部署变更执行、不提供在线业务 API；页面只做状态呈现，操作走 runbook §3.2 |
| N3 | 后端任何代码 / 端点 / 迁移 / 权限点 / 网关 / 监控栈改动 | 纯前端包；9 端点 + 加性字段全现成（3.9.2/3.9.3/3.9.4 已交付） |
| N4 | 角色或权限点的新增/改名界面级主张 | B1-R2 平台角色值域封闭、B1-R1 权限点值域封闭；界面只呈现 seed 现状（sysadmin 12 点 / secadmin 3 点 / audadmin 1 点 / admin 24 点） |
| N5 | 告警/监控数据的手工补录、修改、删除入口 | 界面只读铁律（B8-R2 数据不可删除）；处置经唯一平台 API；直连 Alertmanager 不作验收证据（B8-R1） |
| N6 | e2e（Playwright）扩展 | 沿先例 e2e 仅登录冒烟且门禁 disabled（PENDING-CI）；界面验收靠组件测试 + 剧本演示 |
| N7 | 菜单分组化改造 / 全站菜单机制重写 | 27 项既有菜单零改动（计数断言数字除外）；扁平追加沿惯例（Q2-C 不采） |
| N8 | 新演示身份档位（第三档） | 沿两档 subject/operator 结构，三员以"三员视角"子选择承载（Q3-A）；普通主体档零改动 |
| N9 | Grafana 看板 / Alertmanager UI 的替代或封装 | 各自是 2.5.3/3.9.4 已交付独立通道；本卡页面为平台内读数呈现，读面与看板并存（B7-R3 通知出口口径） |

## 2. 结构组成

- **S1 路由与菜单**：`router/index.ts` 平铺追加 4 条（`platform/monitor` / `platform/alerts` / `platform/rbac` / `platform/domain`），meta `{title, menu, menuOrder: 28~31, icon, permission}`；深链不占菜单（本卡无深链页）；计数断言三处同步；
- **S2 api 层**：`api/platform.ts`——查询与动作函数走 `makeDomainJson(...)` 域工厂（rbac 域与 platform 域两个头函数）+ `pageQuery` 复用；共享类型单点；
- **S3 常量层**：`constants/platform.ts`——LIFECYCLE / HEALTH / INTEGRATED / ALERT_STATE / SEVERITY / DISPOSE_ACTION / CONSISTENCY 双 map + `labelOf()` 未知值原样；
- **S4 身份层**：`stores/demoIdentity.ts`——+`platformRolesHeader()` / `rbacRolesHeader()`（随三员视角）+ evidence/connector 运营档映射修正（DB-128）+ 三员视角状态（localStorage 键 + 信号量沿既有惯例）；`MainLayout.vue` 切换器运营档时显示"三员视角"下拉；
- **S5 视图层**：`views/platform/`（MonitorView / AlertView / RbacView / DomainView 四文件）+ `views/connector/` 三态呈现改造（共用 helper 收敛在 `constants/connector.ts`）；
- **S6 代理层**：`vite.config.ts` server.proxy 在通用 `/api` 键之前插入 `/api/v1/rbac` 与 `/api/v1/platform`（→ `http://localhost:8090`，注释沿既有域同款）；
- **S7 测试层**：`MonitorView.spec` / `AlertView.spec` / `RbacView.spec` / `DomainView.spec` / `api/platform.spec` / `PlatformSourceGuard.spec` / `demoIdentity.spec` 扩展 + 计数断言同步（`MainLayout.spec` / `router.spec`）+ connector 三态用例扩展。

## 3. 主要流程

| 编号 | 流程 | 主路径 |
| --- | --- | --- |
| P1 | 打开监控读数 | 进入"监控读数" → 自动拉 `GET /platform/metrics` → 渲染四区块读数表（逐服务行）→ `NOT_INTEGRATED` 行显式"未接入"标注（note 原文）→ 底部诚实边界提示条（不持久化/单副本/无集群层指标"待生效"） |
| P2 | 告警查看与处置 | 进入"告警与处置" → 自动拉 `GET /platform/alerts` → 七要素列表（firing 红 / resolved 灰徽标）→ 系统管理员视角行内"处置"→ 对话框选动作（确认/静默〔时长缺省 30 分钟，上限 1440〕/升级 + 备注）→ 提交 → 响应回显卡（result=OK + silenceId〔仅静默〕+ recordedAt）→ 列表刷新 |
| P3 | 角色与权限清单 | 进入"角色与权限"·清单 tab → 拉各角色权限点 → 逐角色表格（三员在前）→ 权限点等宽字体展示 |
| P4 | 授予/收回（安全管理员） | 切"安全管理员"视角 → 授予与收回 tab → 表单（角色下拉 / 权限点下拉 / 变更单号必填）→ 提交 → 成功回显 + 提示留痕可查；系统管理员视角同 tab 显示 403 原文（S1-4 负向界面可演） |
| P5 | 变更留痕查询（审计管理员） | 切"审计管理员"视角 → 变更留痕 tab → 分页八列表（变更号/变更单号/变更人/时点/角色/权限点/变更前/变更后） |
| P6 | 部署域查看 | 进入"部署域" → 自动拉 `GET /platform/domain` → 八字段只读卡（生命周期徽标 ACTIVE 绿 / RETRIRED 灰 + configConsistency 结论 + scopeNote 原文灰字）→ 配置不一致时整页错误态（503 原文，不显示"正常"） |
| P7 | DB-128 界面路恢复 | 运营档保存 → 监管审计页（evidence 域 audadmin）200 / 连接器治理·核验面（connector 域裁决组合）200 → 真机走查两页（关闭条件④） |
| P8 | 越权呈现 | 普通档登录：4 新菜单不可见 → 直达 URL → `/dashboard?denied=1` 提示条；页内接口 400/403/503 → ApiError 原文 el-alert 展示（不转译不美化）；三员视角切换后无权 tab 如实 403 |

## 4. 界面说明书（章程 2.6.3 界面类核心交付）

> 逐屏元素、状态与剧本步骤映射；线框见配套原型 HTML 四屏。**全局约定**：页面顶部常规页头（标题 + 说明灰字）；列表 = 筛选区 + el-table（size=small，空态 #empty 插槽）+ el-pagination（仅超页时渲染）；错误 = el-alert + "重试"链接按钮；动作反馈 = ElMessage；**写动作仅两处**（告警处置对话框 / 授予收回表单），其余全只读；未接入/越权/不可用一律原文呈现，不显示 0 或绿。

### 屏 A：监控读数（单页）

| 区块 | 元素与行为 | 剧本映射 |
| --- | --- | --- |
| A-1 读数表 | ① 说明灰字"读数与 `/actuator/prometheus` 同源，未接入服务如实标注"；② 四区块列：服务 / 存活（UP 绿·DOWN 红·未接入灰）/ 堆内存（used/max + GC 暂停速率）/ HTTP 速率（req/s 1m）/ 5xx 错误率 + P95 时延；③ 未接入行：非读数列显式"未接入"徽标 + note 原文"未接入指标采集，读数不可用"（不显示 0/绿） | C-10.2 S1-1（四区块基线 + 抽查）；S1-3（未接入标注） |
| A-2 诚实边界条 | 底部灰字提示：监控读数不持久化 / 单副本部署 / 无集群层指标 / 外发通道"待生效"（G-04）——沿 ADR-014 v1 不做口径 | S2-9（诚实边界核对） |

### 屏 B：告警与处置（单页）

| 区块 | 元素与行为 | 剧本映射 |
| --- | --- | --- |
| B-1 告警列表 | 七要素列：告警名 / 级别（critical 红·warning 橙）/ 对象 / 触发时点 / 当前值 / 阈值 / 状态（firing 红·resolved 灰）+ stateNote 灰字原文；封闭两规则固定入列（`CtdsBackendDown` / `CtdsBackendHighErrorRate`） | S2-1（要素基线）；S2-3~S2-5（触发与恢复双向复看） |
| B-2 处置入口 | 行内"处置"按钮（仅系统管理员视角可见——`platform.alert.handle`；其他视角按钮不渲染）→ 对话框：动作单选（确认 ACKNOWLEDGE / 静默 SILENCE / 升级 ESCALATE）+ 备注 + 静默时长（仅静默时显示，数字输入，缺省 30，上限 1440，分钟）→ 提交 | S2-6（处置留痕）；S2-7（写面双向） |
| B-3 处置回显 | 响应卡：动作 / result=OK / silenceId（仅静默，等宽字体 + 复制）/ recordedAt + 灰字"处置已留痕（处置人/时点/对象/结论）"；升级动作灰字提示"通知外发通道待生效（G-04），本次处置以平台留痕为准" | S2-6（留痕四要素回显） |

### 屏 C：角色与权限（三标签页）

| 区块 | 元素与行为 | 剧本映射 |
| --- | --- | --- |
| C-1 清单 tab | 逐角色卡片式表格（platform.sysadmin / platform.secadmin / platform.audadmin 在前，业务角色与演示档随后）：角色名 + 权限点标签列（等宽字体）；数据 = `GET /rbac/roles`；无聚合加工（清单即读面） | C-10.1 S1-1（逐角色记录权限点集合）；S1-13（两两比对支撑） |
| C-2 授予与收回 tab | 表单：角色下拉 / 权限点下拉 / **变更单号（必填，占位提示"如 CHG-…"）**/ 动作单选（授予/收回）→ 提交 → 成功回显；视角非安全管理员时：表单区显示 403 原文（后端返回，前端不预判拦截以外加规则——按钮仍渲染，提交后如实呈现） | S1-4（系统管理员越权 403）；S1-5/S1-6（授予/收回成功） |
| C-3 变更留痕 tab | 分页八列：变更号 / 变更单号 / 变更人 / 时点 / 角色 / 权限点 / 变更前 / 变更后（beforeValue/afterValue 原文）；视角非审计管理员时显示 403 原文 | S1-12（读取拒绝留痕 + 变更留痕） |
| C-4 视角提示 | 页顶灰字"当前三员视角：系统管理员——各标签页按三员职责面呈现，越权调用如实展示后端拒绝结果" | S1-13（三员分立演示动线） |

### 屏 D：部署域（单页，只读）

| 区块 | 元素与行为 | 剧本映射 |
| --- | --- | --- |
| D-1 信息卡 | 八字段 descriptions：部署域标识 / 名称 / 平台版本 / 生命周期状态徽标（ACTIVE 绿·RETIRED 灰）/ 库 schema 清单 / 服务清单 / 配置一致性结论（CONSISTENT 绿·NOT_APPLICABLE 灰）/ 口径声明（scopeNote **原文**灰字）；**零操作按钮** | C-10.1 S2-1（部署域信息）；S3-1/S3-2（信息与核对） |
| D-2 错误态 | 配置不一致 → 整页 el-alert（503 + `1013S0002` 原文）+ "重试"；**不得显示"正常"**（B9-R4 fail-closed）；退役态：本端点豁免可读（信息卡照常呈现 + 生命周期徽标 RETRIRED） | S3-4（fail-closed 呈现）；S3-5（退役态呈现） |

### 通用交互与状态

- **加载**：表 / 卡 v-loading；**空态**：#empty 插槽"暂无数据"（不伪造数据）；**错误**：ApiError 原文 el-alert + 重试（列表）/ ElMessage.error（动作）；
- **三员视角**：MainLayout 顶栏运营档时显示"三员视角"下拉（系统管理员〔默认〕/ 安全管理员 / 审计管理员），切换后 rbac 域与 platform 新域头随之变化、页面数据重拉；既有页面（目录/合约/交付/计费/连接器/存证）行为不受视角影响；
- **敏感呈现**：权限点 / silenceId / changeNo 等宽字体；时间显示 = 后端 ISO 字符串原样（不做时区换算）；
- **未知枚举**：`labelOf()` 原样显示原始值（不吞不猜）。

## 5. 决策点（待编排师确认，Q1~Q4 + D1）

> 摘要表见任务卡 §二；此处补业务影响说明。

- **Q1 "配置页面"口径**：A 把界面收敛在"读数 + 列表 + 处置"，阈值列只读展示即封闭两规则登记映射——零规格冲突；B 编辑页 = 先变更规格再立项，本卡不做。
- **Q2 菜单组织**：A 的代价 = 4 个计数断言数字同步（机械）；收益 = 与两剧本 :11 占位一一对应、每页独立演示动线；C 分组改造牵动全站 27 项菜单与既有测试基线，超出本包。
- **Q3 三员视角**：A 用"视角子选择"承载三员分立演示（S1-4/S1-12/S2-7 负向界面可演），既有两域固定映射保 DB-128 恒 200；代价 = MainLayout 切换器 +1 下拉、demoIdentity 映射表 +2 函数；B 最小但负向演示走 API 路，界面三员分立"看不见"；C 纯 secadmin 使治理列表 403，直接违反 DB-128 走查意图。
- **Q4 三态呈现**：A 单列三态 = B6-R1 呈现面唯一不歧义形态；B 双列并存制造同页双口径。
- **D1 体量**：预估 3200~4000 行（主源 ~2000 + 测试 ~1350 + 剧本回填与断言同步 ~110）；拆分产生"有页面无身份（DB-128 未清）/ 有监控无处置"中间态，不可独立验收；超 400 行登记制豁免沿先例。

## 6. 体量预估

| 部分 | 预估（非空行） |
| --- | --- |
| 主源：视图 4 文件（Monitor ~380 / Alert ~420 / Rbac ~560 / Domain ~230） | ~1590 |
| 主源：api / constants / identity / MainLayout / router / proxy | ~410 |
| 测试：视图 4 spec + api + SourceGuard + demoIdentity 扩展 + 计数同步 + connector 三态扩展 | ~1320 |
| 剧本回填三份（C-10.1 V1.5 / C-10.2 V1.3 / C-8.2 V1.6） | ~80 |
| **合计** | **~3200~4000**（1 个编码会话；超 400 行登记制豁免） |

## 7. 诚实边界与验收观察点

1. **未接入不显示 0/绿**：`NOT_INTEGRATED` 行显式"未接入"徽标 + note 原文（B5-R4）；不伪造读数；
2. **"从未上报"≠离线**：三态列 NEVER_REPORTED 橙色独立呈现（B6-R1），概览条三数；
3. **越权原文**：403/400/503 一律 ApiError 原文呈现，文案含统一口径"无权限执行该操作"（B3-AC1），前端不预判改写；
4. **升级动作诚实**：ESCALATE 回显附"通知外发通道待生效（G-04），以平台留痕为准"；监控页 G-04/G-07/不持久化/单副本/无集群层指标"待生效"标注（B7-R3/B8-R4/ADR-014 :64-68）；
5. **无删除/伪造通道**：界面无任何删除、补录、手工修改监控/告警/留痕数据入口（B8-R2）；
6. **fail-closed 呈现**：部署域配置不一致整页错误态，不显示"正常"（B9-R4）；
7. **DB-128 不虚报**：界面路恢复以真机走查两页 200 为准（关闭条件④），不以"代码已改"代验收。
