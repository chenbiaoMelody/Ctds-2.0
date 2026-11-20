# WBS-3.10.4 低保真设计（AI 服务配置管理页 · 界面类）V1.0

| 项 | 内容 |
| --- | --- |
| 版本 | **V1.0（方向定稿，2026-11-20 编排师一次确认）**——编排师会话指示原文 = **"定稿确认，三份转 V1.0（推荐）"**，本文件与规格 / hifi 同批转 V1.0 = 编码契约；V0.9 = 立卡草案（2026-11-20） |
| 配套原型 | `docs/designs/WBS-3.10.4-原型-AI服务配置管理.html`（静态线框单页四区块：① 网关总控 ② 当前判定预览 ③ 供应商连接参数 ④ 变更留痕；示例数据 = 虚构样例，仅示意布局） |
| 规格派生声明 | 本设计只从规格 `C-11.1A V0.9`（B1 配置查看 / B2 编辑与校验 / B3 判定预览 / B4 密钥红线与边界 + 非目标 1~9）与既有已验收能力（3.10.1 配置结构与判定纯函数、3.9.5 界面惯例与菜单机制、evidence-service 平台管理落位先例）派生；**未新增任何规格外需求** |

## 1. 做什么 / 不做什么

**做什么（W1~W6）**：

| 编号 | 内容 | 承载 |
| --- | --- | --- |
| W1 | 路由与菜单：扁平追加 1 菜单（"AI 服务配置" menuOrder 32，挂 `platform.ai.config.read`，admin 演示档可见）；菜单计数断言三处同步（普通档 11 不变 / admin 31→32 / max 31→32） | `router/index.ts` + `MainLayout.spec.ts` + `router.spec.ts` |
| W2 | api 域模块：AI 配置 4 端点消费函数（读配置 / 保存配置 / 判定预览 / 变更留痕分页）+ 类型定义 + URL 常量单点；**复用既有 `/api/v1/platform` 代理键（零 vite proxy 改动）** | `api/ai.ts` |
| W3 | 常量层：供应商封闭清单双 map（编号↔中文名：glm=智谱 GLM〔默认〕/ deepseek=DeepSeek / qwen=通义千问 Qwen / kimi=Kimi）+ 判定三态 LABELS/TAG（ALLOW 绿 / DENIED_DISABLED 红 / DENIED_BUDGET_EXCEEDED 红）+ 未知枚举值原样显示 | `constants/ai.ts` |
| W4 | 配置页：四区块纵向单页——① 网关总控（开关 el-switch / 预算 el-input-number ≥0 / 默认供应商 el-select 四项）② 判定预览条（三态芯片 + 刷新 + "已用量恒为 0"口径灰字）③ 供应商连接参数表（四行固定，行内编辑服务地址/模型名；密钥列 = 固定灰字"部署期经配置中心/KMS 注入（页面不显示）"，**零输入框**）④ 变更留痕分页表（时间/操作者/字段/变更前/变更后）+ 保存/放弃按钮 + 诚实边界提示条（页面顶部） | `views/platform/AiConfigView.vue` |
| W5 | 校验与错误呈现：前端前置校验（预算 ≥0 整数 / URL http(s) 格式 / 模型名 ≤128）+ 后端校验拒绝统一呈现（页面顶部 el-alert 红条：1014 段错误码 + 可区分原因文案；库与留痕零变化）+ 保存成功反馈（ElMessage + 视图与留痕刷新） | `AiConfigView.vue` + `api/ai.ts` |
| W6 | 测试群：AiConfigView.spec（渲染/编辑保存/校验/预览/留痕）+ api/ai.spec（URL 单点/payload）+ 计数断言同步（MainLayout.spec / router.spec） | spec 群 |

**不做什么（N1~N9）**：

| 编号 | 不做 | 理由与去向 |
| --- | --- | --- |
| N1 | 密钥输入框 / 密钥回显 / 密钥引用编辑 | Q4-A + 规格 B4-1：页面零密钥；真实值部署期经配置中心/KMS 注入；表仅预留 api_key_ref 引用占位列（seed 空值），读 API 不返回 |
| N2 | 连通性测试按钮 / 任何真实外呼 | 规格非目标 1 + ADR-003 §4 零消耗红线；V1.5 承接 |
| N3 | 用量统计 / 预算熔断运行时 / 配置热更新消费桥 | 规格非目标 2；V1.0 无运行时消费方（配置即预留）；V1.5 / C-11.6 |
| N4 | 留痕回滚 / 归档 / 删除操作 | 规格非目标 6：留痕只读 |
| N5 | vite proxy 改动 | 设计实证：`/api/v1/platform` → 8090 代理键已存在（3.9.5 建立），本卡零 proxy 改动 |
| N6 | 菜单分组化改造 / 全站菜单机制重写 | 31 项既有菜单零改动（计数断言数字除外）；扁平追加沿 3.9.5 惯例 |
| N7 | e2e（Playwright）扩展 | 沿先例：组件测试 + 剧本演示 |
| N8 | `common/model-gateway` 任何改动 | 纯库消费（判定预览复用其判定语义）；3.10.1 已验收模块零改动 |
| N9 | 智能路由/降级/灰度、模型资产目录、计量计费、审计落库/上链 | C-11.1 P1 面 / C-8.4 / DB-137，均非本卡 |

## 2. 结构组成

- **S1 路由与菜单**：`router/index.ts` 平铺追加 1 条（`platform/ai-config`），meta `{title: 'AI 服务配置', menu: true, menuOrder: 32, icon, permission: 'platform.ai.config.read'}`；计数断言三处同步（普通 11 不变 / admin 31→32 / max 31→32）；
- **S2 api 层**：`api/ai.ts`——4 个消费函数走既有 `apiJson` 封装（身份头自动附），URL 常量单点；共享类型单点；分页走既有 `pageQuery` 约定；
- **S3 常量层**：`constants/ai.ts`——PROVIDER 双 map（含"默认"徽标位）+ DECISION 三态 LABELS/TAG + `labelOf()` 未知值原样；
- **S4 视图层**：`views/platform/AiConfigView.vue` 单页四区块（表单态 state 集中、保存 = 全量 PUT、预览 = 独立 GET）；
- **S5 测试层**：`AiConfigView.spec` / `api/ai.spec` / `MainLayout.spec`+`router.spec` 计数同步（+1）。

## 3. 主要流程

| 编号 | 流程 | 主路径 |
| --- | --- | --- |
| P1 | 打开配置页 | 侧边栏"AI 服务配置"（menuOrder 32）→ 自动拉 `GET /ai-config` → 渲染合并视图（无保存值 = C-11.1 缺省：关闭/glm/0/四供应商"未配置"灰字）→ 顶部诚实边界提示条常驻 |
| P2 | 编辑保存 | 修改开关/预算/默认供应商/供应商行内字段 → 保存 → 前置校验通过 → `PUT /ai-config` 全量提交 → 成功：ElMessage + 视图刷新 + 留痕刷新；失败：顶部 el-alert 红条（1014 段错误码 + 可区分原因），表单保留修改态 |
| P3 | 判定预览 | 自动执行 + "刷新预览"按钮 → `GET /ai-config/preview` → 三态芯片（拒绝：网关未启用〔红〕/ 允许〔绿〕）+ 口径灰字"预览按已用量 = 0 执行；V1.0 无用量源，超预算分支不可触发" |
| P4 | 变更留痕查询 | 页面底部留痕表 → `GET /ai-config/changes`（pageNum/pageSize）→ 分页五列（时间/操作者/字段/变更前/变更后）；空态 = "暂无变更记录" |
| P5 | 无权限呈现（负向） | 无 `platform.ai.config.read` → 403 文案统一呈现（沿既有错误态惯例）；无 `platform.ai.config.write`（读有写无）→ 页面可读、保存按钮隐藏（Q4 语义：写面缺权明确拒绝） |

## 4. 界面说明书（逐屏元素、状态与校验）

> 全局约定沿 3.9.5：页面顶部常规页头（标题 + 说明灰字）+ 常驻诚实边界提示条；错误 = el-alert + 原因文案；动作反馈 = ElMessage；表格 = el-table（size=small，空态插槽）+ el-pagination（仅超页时渲染）。

**字段表（区块 ① + ③）**：

| 字段 | 控件 | 校验 | 缺省（无保存值） | 权限 |
| --- | --- | --- | --- | --- |
| 网关总开关 enabled | el-switch | 布尔 | 关闭 | 读可见；写可改 |
| token 预算上限 tokenBudget | el-input-number | ≥0 整数（0=未设限）；后端复核 | 0 | 同上 |
| 默认供应商 defaultProvider | el-select（四项封闭） | 必须在清单内；后端复核 | glm | 同上 |
| 供应商 服务地址 baseUrl | 行内 el-input | http(s) URL 或空（空=未配置）；后端复核 | 未配置（灰字） | 同上 |
| 供应商 默认模型名 model | 行内 el-input | ≤128 字符或空；后端复核 | 未配置（灰字） | 同上 |
| 密钥（列） | —（无控件） | — | 固定灰字"部署期经配置中心/KMS 注入（页面不显示）" | 永不出现输入/回显（Q4-A） |

**判定预览（区块 ②）**：三态芯片——ALLOW「允许（当前配置下网关放行）」绿 / DENIED_DISABLED「拒绝：网关未启用」红 / DENIED_BUDGET_EXCEEDED「拒绝：超出预算」红（V1.0 预览不可触发，仅三态完备呈现）+ 口径灰字。

**变更留痕（区块 ④）**：五列只读（变更时间 / 操作者 / 字段路径 / 变更前 / 变更后；空值显示"（空）"）；仅变化字段有行。

**权限可见性**：菜单挂 `platform.ai.config.read`（hasPermission 占位实现下 = admin 演示档可见，沿 3.9.5 口径）；保存按钮挂 `platform.ai.config.write` 语义（写缺权隐藏/禁用）；后端 `@RequirePermission` 为权威（前端呈现不构成安全边界，负向 403 可演）。
