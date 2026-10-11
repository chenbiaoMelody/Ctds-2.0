# WBS-3.4.7 高保真设计：合约工作台界面（hifi · 编码契约）

> **版本**：**V1.0（编码契约，2026-10-11 编排师"立卡任务已确认，继续"一次确认）**——**Q1~Q7 均采建议口径 A + D1 不拆分**（确认记录落本文件末节；此前 V0.9 草案随立卡批 `4678d8e` 落盘）｜ 落点任务卡：`docs/tasks/WBS-3.4.7-合约工作台界面-2026-10-11.md` ｜ 规格：`docs/specs/C-4.1-4.3-数字合约与使用控制.md` V1.0 行为 1~7（界面承载）+ §7 Q9 ｜ 上游契约：`ADR-019`/`ADR-020`/`ADR-021`
> **确认状态**：✅ 已确认（2026-10-11，编排师会话回复"立卡任务已确认，继续"）——本文件为**编码契约**，实现与设计逐条一致（评审①锚）；编码归新会话（测试先行）

- 型态：**界面类**（高保真 = 编码契约 + 界面说明书）
- 任务卡：`docs/tasks/WBS-3.4.7-合约工作台界面-2026-10-11.md`；低保真：`docs/designs/WBS-3.4.7-lofi.md`（W1~W15 / N1~N8 / Q1~Q7 / D1）
- 线框原型：`docs/designs/WBS-3.4.7-原型-合约工作台界面.html`
- 上游契约：`docs/designs/WBS-3.4.2-hifi.md` §1/§2（W1~W4、R1~R5）、`WBS-3.4.3-hifi.md` §1/§2（W5~W12、R6~R11）、`WBS-3.4.5-hifi.md` §6（R12）、`WBS-3.4.6-hifi.md` §2~§4（模拟 / 测试台 / 受控执行）+ `ADR-021`（§2.2 双通道 / §2.7 可见性与权限 / §2.9 诚实边界）；通用约定 `ADR-005`（REST / 九位错误码 / 分页 §3.2 / 401-403 文案）、`ADR-006`（国密——界面零加解密）、`ADR-007`（幂等）、`ADR-016 §2.7`（演示期安全边界）、`ADR-001`（前端栈冻结）
- 本文件 §1~§11 **已经编排师一次确认（2026-10-11，Q1~Q7 均采建议口径 A + D1 不拆分）= 编码契约**（实现与设计逐条一致 = 评审①锚，章程 2.6.1）

## 确认记录（人签署；未签署 = 未确认 = 禁止进入编码）

| 项 | 内容 |
| --- | --- |
| 高保真确认（编码契约） | ✅ **已确认（2026-10-11，编排师会话回复"立卡任务已确认，继续"= Q1~Q7 均采建议口径 A + D1 不拆分；与低保真同批一次确认，章程 2.6.3）**——本文件 §1~§11 = 编码契约；确认留痕落本文件末节 |

---

## 1. 接口契约表

**通用约定**：路径前缀 `/api/v1`；响应统一 `ApiResult<T>`（`code="0"` 成功）；分页请求 `?pageNum&pageSize`（`pageSize` 上限 100），响应 `PageResult<T>{list,total,pageNum,pageSize,totalPages}`（`common-pagination`）；身份与角色经 `common/auth`（`X-Ctds-Subject` / `X-Ctds-Roles`）；**权限与可见性判定一律在服务端**，前端只做体验层显隐。

### 1.1 复用面（合约域 27 端点 + 目录域 1 个只读端点；**全部零改动**）

| # | 端点 | 权限 / 判定（服务端） | 界面落点 |
| --- | --- | --- | --- |
| R4 | `GET /contract-templates?type&pageNum&pageSize` | 认证 + `contract.template.read` + ADMITTED（未入驻/不存在统一文案 `1008C0003`；**仅启用中**） | 模板页浏览区列表 + 发起弹窗模板下拉（含 `currentVersion`） |
| R5 | `GET /contract-templates/{templateNo}` | 同上；停用/不存在同形 `1008C0001` | 模板详情抽屉（条款框架 `/clauseFramework.status` 渲染）+ 发起弹窗槽位表单数据源 |
| R1 | `GET /contract-templates/manage?status&type&pageNum&pageSize` | 认证 + `contract.template.manage`（仅运营档） | 模板页运营区列表（含停用） |
| R2 | `GET /contract-templates/manage/{templateNo}/versions` | 同上 | 运营区「版本历史」抽屉 |
| R3 | `GET /contract-templates/manage/action-logs?templateNo&pageNum&pageSize` | 同上 | 运营区「维护留痕」页签（含 `DENIED_MANAGE` 拒绝行） |
| W1 | `POST /contract-templates` `{name,type,clauseFramework}` | 认证 + `contract.template.read`（注解门槛）+ **应用层维护权**（非运营方 `1008C0002` + `DENIED_MANAGE` 留痕） | 运营区「新增模板」弹窗 |
| W2 | `POST /contract-templates/{templateNo}/revisions` `{clauseFramework}` | 同 W1（响应 = 模板号 + 新版本号 + 修订前版本号） | 「修订」弹窗（新版本；旧版本保留） |
| W3 | `POST /contract-templates/{templateNo}/disable` | 同 W1；同态重复 `1008C0007` | 「停用」二次确认 |
| W4 | `POST /contract-templates/{templateNo}/enable` | 同 W1 | 「启用」确认 |
| W5 | `POST /contracts` `{productId,templateNo,templateVersionNo,clauseValues}` | 认证 + `contract.deal` + ADMITTED（发起文案 `1008C0003`）+ 产品在架（`1008C0010`）+ 双方异主体（`1008C0016`）+ 模板可发起（`1008C0017`）+ 条款/策略校验（`1008C0014`/`1008C0015`）+ 幂等（ADR-007） | 工作台「发起合约」弹窗（201 = 发起即协商中） |
| W6 | `POST /contracts/{contractNo}/proposals` `{clauseValues}` | 认证 + `contract.deal` + 参与方 + `NEGOTIATING`（其余 `1008C0013`） | 详情「提案」弹窗（版本 +1） |
| W7 | `POST /contracts/{contractNo}/confirmations`（空体） | 同上 | 详情「确认」按钮（双齐 → 待签署） |
| W8 | `POST /contracts/{contractNo}/negotiation-terminations`（空体） | 同上（任一方；终态不可逆） | 详情「终止协商」二次确认 |
| W9 | `POST /contracts/{contractNo}/signatures` `{did}` | 认证 + `contract.deal` + 参与方 + 待签署/部分签署 + DID 归属校验（`1008C0018`） | 详情「签署」弹窗（后签 → 已生效 + 存证） |
| W10 | `POST /contracts/{contractNo}/signature-refusals`（空体） | 认证 + `contract.deal` + 参与方 + 未双签生效（已生效 → `1008C0013`） | 详情「拒签终止」二次确认（含"已签方撤回"语义） |
| W11 | `POST /contracts/{contractNo}/release-consents`（空体） | 认证 + `contract.deal` + 参与方 + `EFFECTIVE` | 详情「合意解除」确认（双齐 → 已完结） |
| R6 | `GET /contracts/mine?status&role&pageNum&pageSize` | 认证 + `contract.read`（恒仅本主体参与） | 工作台列表 |
| R7 | `GET /contracts/{contractNo}` | 认证 + `contract.read` + 参与方/治理例外（非参与方与不存在**同码同文** `1008C0012`，404 + 拒绝留痕） | 详情总览（含模板版本快照 / 定价快照 / 条款全文 / 签署状态 / 存证摘要 / 终止信息） |
| R8 | `GET /contracts/{contractNo}/clause-versions` | 同 R7 | 详情「条款版本链」（逐版全文 + 变更明细 from→to） |
| R9 | `POST /contracts/{contractNo}/signature-verifications` | 同 R7（双方 + 运营方）；FAIL/UNAVAILABLE 异常留痕 `VERIFY_SIGNATURE_FAILED` | 详情「验签核验」按钮 + 结果表 |
| R12 | `GET /contracts/{contractNo}/usage-summary?pageNum&pageSize` | 认证 + `contract.read` + 参与方/治理例外（`1008C0012` 防枚举） | 详情「策略与使用」区——使用摘要（quota / 放行 / 拒绝 / 记录分页） |
| —— | `POST /contracts/{contractNo}/policy-simulations` `{actionType,purpose?,territory?,assumedUsedCount?,assumedDate?,strategyDocument?}` | 认证 + `contract.deal` + 参与方/治理例外（`1008C0012`）；**拒绝以数据返回（HTTP 200 + `allowed=false`）**；草稿非法 `1008C0015` | 详情「模拟试算」弹窗 |
| —— | `POST /contracts/{contractNo}/policy-testbench-runs` | 认证 + `contract.deal` + 参与方/治理例外 | 详情「测试台」一键报告（11 场景三态） |
| —— | `POST /contracts/{contractNo}/usage-executions` `{actionType,purpose,territory}` | 认证 + `contract.deal` + **仅参与方**（治理档 `1008C0012`——t12 口径）；放行 200 + `{allowed,usedCount,occurredAt}`；越界 `1008C0020`(403) / 状态失效 `1008C0013`(409) / 不可见 `1008C0012`(404) | 详情「发起使用」弹窗（**治理页无此入口**） |
| W12 | `POST /contracts/governance/{contractNo}/force-termination` `{reason}`（1~512） | 认证 + `contract.governance`（仅运营档）+ 应用层 admin 判定 + `EFFECTIVE` | 治理页「强制终止」弹窗 |
| R10 | `GET /contracts/governance?status&pageNum&pageSize` | 认证 + `contract.governance`（**每次调用写 `GOVERNANCE_VIEW` 留痕**） | 治理页全量列表（含协商中/已终止） |
| R11 | `GET /contracts/governance/{contractNo}` | 同 R10（不存在 `1008C0012` 同形；**查看留痕**） | 治理页直查详情（+ 版本链 + 留痕区） |
| —— | `GET /api/v1/data-products?keyword&pageNum&pageSize`（**目录域**，只读复用） | 认证 + `catalog.read` + ADMITTED | 发起弹窗「检索产品」回填（跨域调用走 `api/catalog.ts`，**目录域角色头**，见 §4） |

**出参（界面所需字段；以各上游 hifi 为准）**：`TemplateView{templateNo,name,type{code,displayName},currentVersion}`；`TemplateDetailView{templateNo,name,type,currentVersion,clauseFramework{slots:[{key,name,required,guide}]},publishedAt}`；`ManageTemplateView{templateNo,name,type,currentVersion,status,createdBy,createdAt,updatedAt}`；`TemplateVersionView{versionNo,publishedBy,publishedAt,clauseFramework}`；`TemplateActionLogView{id,templateNo,versionNo,action,actorSubjectNo,reasonCode,fromValue,toValue,createdAt}`；`RevisionView{templateNo,versionNo,previousVersionNo}`；`Initiated{contractNo,status,clauseVersionNo,templateNo,templateVersionNo,productId}`；`Detail{contractNo,productId,productName,pricingModel,priceAmount,providerSubjectNo,requesterSubjectNo,templateNo,templateVersionNo,status,terminationType,terminationReason,terminatedBy,effectiveAt,endedAt,currentClauseVersion,clauseValues{slots,strategy},contentHash,signatures[{partyRole,subjectNo,did,signedAt}],attestation{…},createdAt,updatedAt}`；`Mine{contractNo,productName,counterpartySubjectNo,myRole,status,currentClauseVersion,effectiveAt,updatedAt}`；`ClauseVersion{versionNo,clauseValues,changes[{slot,from,to}],proposedBy,proposedAt,confirmedProviderAt,confirmedRequesterAt}`；`Verification{contractNo,results[{partyRole,did,result,reason,verifiedAt}]}`；`UsageSummary{contractNo,quota{limit,used},allowedCount,deniedCount,records:PageResult<{requesterNo,actionType,outcome,reasonCode,violations,usedCount,occurredAt}>}`；`Simulation{contractNo,contractStatus,strategySource,strategyEffective,allowed,violations[],assumedUsedCount,assumedDate,policySnapshot}`；`TestbenchReport{contractNo,contractStatus,strategyEffective,runAt,scenarios[{code,elementKey,direction,expectation,outcome,actual{allowed,violations},note}],summary{total,pass,fail,skipped}}`；`Execution{contractNo,allowed,usedCount,occurredAt}`；治理 `ContractGovernanceItem{contractNo,productName,providerSubjectNo,requesterSubjectNo,status,terminationType,currentClauseVersion,effectiveAt,updatedAt}` + `GovernanceDetail{detail,clauseVersions}`。

### 1.2 新增只读端点（**仅 Q2 采 A 时实施**；编号续 R12）

| # | 端点 | 权限判定（服务端） | 入参 | 出参 | 失败码 |
| --- | --- | --- | --- | --- | --- |
| **R13** | `GET /api/v1/contracts/{contractNo}/action-logs` | 认证 + `contract.read` +（**参与方** 或 `contract.governance`〔运营档治理例外〕，走共享守卫 `ContractVisibilityGuard`） | `pageNum/pageSize` | `PageResult<ContractActionLogView>`：`id`、`contractNo`、`versionNo`、`action`（12 动作码值域）、`actorSubjectNo`、`reasonCode`（码位尾号如 `C0012`）、`fromValue`、`toValue`、`createdAt`；按 `created_at DESC, id DESC` | 不存在 / 非参与方且非治理档 → **`1008C0012` 同码同文**（404；非参与方命中写 `DENIED_ACCESS` 留痕，沿 R6~R12 既有守卫口径）；分页非法沿 common（`1000C0001`） |

**读面留痕口径（本卡登记）**：R13 治理档读取**不另写留痕**（R10/R11 的治理查看留痕义务不变；避免"看留痕产生留痕"递归——沿 3.3.6 R14/R15 同款口径）；参与方读取**不写留痕**（本人合约读面既有口径，沿 R6~R8）；非参与方命中写 `DENIED_ACCESS`（守卫既有行为，零新增）。

**零新增**：错误码（复用 `1008C0012` / `1000C0001`）、库表、迁移、状态枚举、依赖；**既有 27 端点零改动**（方法签名 / 字段 / 错误码一字不动）。

---

## 2. 前端模块与类型

| 文件 | 内容 |
| --- | --- |
| `src/api/contract.ts`（**新增**） | 端点调用函数（命名 `listTemplates / getTemplate / listManageTemplates / listTemplateVersions / listTemplateActionLogs / createTemplate / reviseTemplate / disableTemplate / enableTemplate / initiateContract / proposeClauses / confirmClauses / terminateNegotiation / signContract / refuseSignature / releaseConsent / listMyContracts / getContractDetail / listClauseVersions / verifySignatures / getUsageSummary / simulatePolicy / runPolicyTestbench / executeUsage / listGovernanceContracts / getGovernanceDetail / forceTerminate / listContractActionLogs`〔Q2-A〕）；**页面级角色头** `headers: {'X-Ctds-Roles': contractRolesHeader()}`（覆盖 `apiJson` 全局头，见 §4）；主体编号空白 → **零请求**前置拦截（`ApiError('1000C0002', DEMO_SUBJECT_REQUIRED_TIP)`，沿 3.2.6 E3 口径）；复用 `apiJson`（**不新增 `fetch` 直连**） |
| 同上（类型） | 就地声明响应类型（§1.1 出参清单对应的 TS 接口：`ContractTemplateItem` / `TemplateDetail` / `ManageTemplate` / `TemplateVersionItem` / `TemplateActionLogItem` / `RevisionResult` / `InitiatedResult` / `MyContract` / `ContractDetail` / `ClauseVersionItem` / `VerificationReport` / `UsageSummary` / `SimulationResult` / `TestbenchReport` / `ExecutionResult` / `GovernanceContract` / `GovernanceDetail` / `ContractActionLogItem`）；分页复用 `src/api/types.ts` 的 `PageData<T>`（**不重复声明**）；产品检索复用 `src/api/catalog.ts` 的 `searchProducts` 与 `CatalogProduct` |
| `src/constants/contract.ts`（**新增**） | 合约状态（`NEGOTIATING / PENDING_SIGNATURE / PARTIALLY_SIGNED / EFFECTIVE / COMPLETED / TERMINATED` → 中文 + tag 色）/ 终止类型（`NEGOTIATION_TERMINATED / SIGNATURE_REFUSED / GOVERNANCE_FORCE_TERMINATED`）/ 我的角色（`PROVIDER / REQUESTER`）/ 模板类型（三类）/ 模板状态（`ENABLED / DISABLED`）/ 留痕动作码（12 值）/ 策略要素键与显示名（`quota / term / purpose / territory / noRedistribution`）；**统一提示文案常量**（`TEMPLATE_NOT_ACCESSIBLE_TIP` = "模板不存在或不可用"、`TEMPLATE_MAINTAIN_FORBIDDEN_TIP` = "无权进行模板维护操作"、`CONTRACT_NOT_ACCESSIBLE_TIP` = "合约不存在或不可见"、`PRODUCT_NOT_DEALABLE_TIP` = "产品不存在或未在架，无法发起合约"、`TEMPLATE_NOT_INITIABLE_TIP` = "所选模板不可用于发起合约"、`ADMISSION_REQUIRED_*_TIP`〔浏览/发起/维护三语境后端原文〕、`SIMULATION_HONESTY_TIP` = "模拟结论不是放行承诺；正式放行以真实执行通道为准"、`EXECUTION_DEMO_TIP` = "受控执行为演示期受控入口，正式交付链接入后同判定面"）——页面**禁止散写**这些中文（§6.6） |
| `src/stores/demoIdentity.ts`（**扩展**） | 新增 `contractRolesHeader()`：普通档 `provider`；运营档 `admin`；既有 `spaceRolesHeader()` / `catalogRolesHeader()` / 档位读写零改动 |
| `src/views/contract/TemplateView.vue`（**新增**） | 合约模板页（§6.2） |
| `src/views/contract/WorkbenchView.vue`（**新增**） | 合约工作台（§6.3） |
| `src/views/contract/ContractDetailView.vue`（**新增**，深链） | 合约详情（§6.4） |
| `src/views/contract/GovernanceView.vue`（**新增**） | 合约治理页（§6.5） |
| `src/views/contract/components/ClauseEditor.vue`（**新增**） | 条款槽位编辑器：**框架模式**（发起——按 `clauseFramework.slots` 渲染行：名称 / 必填标记 / 说明 / 值输入）与**值模式**（提案——按当前 `clauseValues.slots` 渲染：键只读 + 值可改 + 增行〔键+值〕+ 删行）；值 ≤2000 字符（`maxlength` 对齐） |
| `src/views/contract/components/StrategyEditor.vue`（**新增**） | 策略编辑器：五要素（次数：开关 + 整数输入〔**不设前端下限**——非法值可提交交服务端判定〕；期限：开关 + 起始/截止日期；用途 / 域内：开关 + 文本；禁止再分发：开关）+ 显式"无使用限制"勾选；输出 `{quota:{enabled,maxCount},term:{enabled,startDate,endDate},purpose:{enabled,text},territory:{enabled,text},noRedistribution:{enabled},noRestrictionDeclared}` 形态（对象键固定与后端文档字段名一致） |
| `src/router/index.ts`（**改**） | 见 §3 |
| `src/layouts/MainLayout.spec.ts` / `src/router/router.spec.ts`（**改**） | 菜单计数断言 admin **13 → 16**、user 仍 **5**；`menuOrder` 最大值 **13 → 16**（两处）；新路由与权限守卫断言；**e2e 登录冒烟零改动** |
| `vite.config.ts`（**改**） | 开发期转发新增 2 键 → **8085**：`'/api/v1/contracts'`、`'/api/v1/contract-templates'`（键序位于通用 `'/api'` 之前；属开发期转发，不改服务契约与门禁配置） |

---

## 3. 路由与菜单（`src/router/index.ts`）

| path | name | 组件 | meta |
| --- | --- | --- | --- |
| `contracts/templates` | `contract-templates` | `views/contract/TemplateView.vue` | `{title: '合约模板', menu: true, menuOrder: 14, icon: 'Document', permission: 'contract.template'}` |
| `contracts/workbench` | `contract-workbench` | `views/contract/WorkbenchView.vue` | `{title: '合约工作台', menu: true, menuOrder: 15, icon: 'Briefcase', permission: 'contract.deal'}` |
| `contracts/workbench/:contractNo` | `contract-detail` | `views/contract/ContractDetailView.vue` | `{title: '合约详情'}`（**须声明在 `contracts/workbench` 之后**；不占菜单；无权限点——门槛在服务端） |
| `contracts/governance` | `contract-governance` | `views/contract/GovernanceView.vue` | `{title: '合约治理', menu: true, menuOrder: 16, icon: 'Stamp', permission: 'contract.governance'}` |

**菜单计数**：admin 演示角色 **13 → 16**；普通 `user` 角色仍 **5**（三个新菜单均带权限点 → 仅 admin 演示角色可见，e2e 冒烟零改动）。`MainLayout.spec.ts` 与 `router.spec.ts` 四处断言同步更新（13 → 16 两处 + 新增三条路由断言）。

> 注：路由 meta 权限点为本仓库既有**演示角色**体验层机制（`stores/demoRole.hasPermission`：admin 全量 / user 无），与合约域**档位角色头**（§4，服务端判定源）为两套独立机制（沿目录域先例）——即：演示执行人通常以"演示角色 admin + 档位按剧本切换"操作，菜单可见性由前者承载，服务端判定由后者承载。

---

## 4. 演示身份与角色头

- **主体编号**：复用 `api/client.ts` 的 `getDemoSubject()` / `setDemoSubject()`（localStorage `ctds-demo-subject`）；**档位**：`stores/demoIdentity.ts`（`ctds-demo-actor-mode`，`subject` 默认 / `operator`）；顶栏控件零新增（3.2.6 已交付）。
- **合约域角色头（本卡新增）**：`contractRolesHeader()` = 普通档 **`provider`**；运营档 **`admin`**（服务端 `contract-service` yml 实测映射：`provider` = `contract.template.read,contract.read,contract.deal`；`admin` = 五点含 `contract.template.manage,contract.governance`）。
- **硬约束（测试锚点 T18）**：**普通档请求不得携带 `admin`**——否则模板维护类与"越权被拒"类剧本步骤会被服务端放行（让判定失真）。
- **档位门槛提示（体验层）**：运营档打开"参与方面动作"（发起 / 签署 / 受控执行）→ 服务端按判定拒绝（发起/签署 = `contract.deal` admin 亦持 → 进应用层按参与方判定拒绝；受控执行 = 仅参与方 → `1008C0012`）→ **原样展示** + 体验层提示"当前为平台运营方档，参与方动作请切回普通主体档"（不得伪装成功）；普通档打开治理页 → 菜单不可见（体验层）+ 直链 → 403 原样展示。
- **跨域调用例外**：发起弹窗「检索产品」走 `api/catalog.ts`（**目录域角色头** `catalogRolesHeader()`，普通档 `provider` / 运营档 `admin`）——页面级头各自独立，互不污染（沿 3.3.6"空间下拉走 `api/space.ts`"先例）。
- **边界**：`X-Ctds-Subject` 为空 → `AuthContextFilter` 整头作废（按未认证）→ 合约域 API 封装在主体编号空白时**前置拦截零请求**。
- **不改全局**：`client.ts` 的 `demoRolesHeader()` 一字不动（最小权限面，沿 3.1.11 / 3.2.6 / 3.3.6 先例）。

---

## 5. 后端新增只读端点（**仅 Q2 采 A 时实施**，否则本节整体不实施）

| 项 | 内容 |
| --- | --- |
| 落点 | `services/contract-service`（**不新建模块**）：R13 方法并入既有 `interfaces/ContractQueryController`（读面归属一致；**既有方法零改动**）、`interfaces/dto/ContractActionLogView`（新增，字段集 = §1.2 出参）、`application/ContractQueryService`（+`actionLogs(contractNo, operatorNo, pageQuery)` 方法；可见性经共享守卫 `ContractVisibilityGuard.requireReadable` 单点）、`domain/ContractRepository` + `infrastructure/JdbcContractRepository`（+只读分页方法：参数化 SQL、`WHERE contract_no = ?`、`ORDER BY created_at DESC, id DESC`） |
| 权限实现 | `@RequirePermission("contract.read")` 为第一道功能门槛（admin 亦持）；**参与方 / 治理档的行级判定在应用服务经共享守卫单点**（第三/第四消费方共用，沿 ADR-021 §2.7 口径）：非参与方且非治理 → `1008C0012` 同码同文 + 守衞既有 `DENIED_ACCESS` 留痕；治理档读取**不另写留痕**（§1.2 口径） |
| 数据 | 只读既有 `contract_action_log`（V2 迁移；12 动作码值域封闭）；`reasonCode` = 码位尾号（DB-31 口径，既有列值）；**不含敏感原文与签名值**（表本身不含——值级明细在加密版本行） |
| 分页映射 | 沿用 3.4.3/3.4.6 既有 `PageViews.page(...)` 单点助手（本卡零新增映射面） |
| 错误码 / 库表 / 迁移 | **零新增**（复用 `1008C0012` / `1000C0001`）；**零新表、零迁移** |
| 既有端点 / 服务 | **零改动**（27 端点的签名、字段、错误码、留痕行为一字不动；`ContractVisibilityGuard` 零改动——本卡只做"新消费方"） |
| 测试 | 见 §7 T1~T2（`SharedMySqlContainer` 实跑）；**新测试类**（不触碰 `ContractPolicySimulatorIntegrationTest`——跟踪-29 合规）；与既有 226 例零回归 |

---

## 6. 界面说明书

### 6.1 全局布局

- 左侧菜单：新增「合约模板 / 合约工作台 / 合约治理」（`menuOrder 14/15/16`，均按演示角色显隐）；顶栏：既有「演示模式」+ 角色切换 + 「演示身份」控件（3.2.6 交付，零新增）；
- 全部合约域请求经 `apiJson` 自动带 `X-Ctds-Subject` 与 `X-Ctds-Roles`（`contractRolesHeader()`）；发起弹窗的产品检索带目录域角色头（§4）；
- 页内统一模式：列表 = 过滤条 + 分页表格 + 空态；详情节 = 分区（卡片）+ 动作条；危险动作 = 二次确认。

### 6.2 页面 1：合约模板（`/contracts/templates`）

**浏览区（所有已入驻主体；默认页签）**

| 区块 | 内容 |
| --- | --- |
| A 过滤条 | 类型下拉（全部 / 公共数据授权 / API 调用 / 隐私计算）+「查询」「重置」 |
| B 模板列表（R4，分页） | 列：模板编号 / 名称 / 类型 / 当前版本 / 操作（「查看详情」）；仅启用中；空态"暂无启用中的模板" |
| C 详情抽屉（R5） | 模板元数据（编号 / 名称 / 类型 / 当前版本 / 发布时间）+ **条款框架表**（逐槽位：名称 / 是否必填〔"必填"·"可选"标签〕/ 填写说明）；页面**不含任何数据本体与敏感原文** |
| D 不可达分支 | 停用 / 不存在 → **同一条**提示常量（`TEMPLATE_NOT_ACCESSIBLE_TIP`，原样展示后端文案）；未入驻 → 统一业务文案 `1008C0003`（浏览语境原样）；不得前端区分"不存在 / 已停用" |

**运营区（仅运营档可见；页内"运营维护"页签，Q1-A）**

| 区块 | 内容 |
| --- | --- |
| E 运营列表（R1，分页） | 列：模板编号 / 名称 / 类型 / 当前版本 / **状态**（启用中 / 已停用 tag）/ 创建者 / 更新时间 / 操作（「修订」「停用」或「启用」「版本历史」）；状态与类型可选过滤 |
| F 操作条 | 「新增模板」按钮（弹窗 A） |
| G 版本历史抽屉（R2） | 逐版本：版本号 / 发布人 / 发布时间 / 条款框架表（**旧版本保留可查**） |
| H 维护留痕页签（R3，分页） | 列：动作（新增 / 修订 / 启用 / 停用 / 维护被拒）/ 模板编号 / 版本号 / 操作者 / 时间 / 从何值→到何值 / 拒绝码；**含 `DENIED_MANAGE` 拒绝行**；空态"暂无维护记录" |
| I 档位提示 | 普通档：运营区页签隐藏（体验层）；写面被拒 `1008C0002` 原样展示 + 提示"模板维护仅限平台运营方" |

### 6.3 页面 2：合约工作台（`/contracts/workbench`）

| 区块 | 内容 |
| --- | --- |
| A 操作条 | 「发起合约」按钮（弹窗 B） |
| B 我的合约表格（R6，分页） | 列：合约编号 / 产品名称 / 对方主体编号 / 我的角色（提供方 / 需求方）/ 状态（六态 tag）/ 当前条款版本 / 生效时间 / 更新时间 / 操作（「查看详情」）；过滤条：状态下拉 + 我的角色下拉 + 「查询」「重置」；空态"暂无合约" |
| C 不可达分支 | 未入驻 → 统一业务文案（发起语境 `1008C0003`）原样；不存在无列表分支（恒仅本人） |

### 6.4 页面 3：合约详情（`/contracts/workbench/:contractNo`，深链）

| 区块 | 内容 |
| --- | --- |
| A 总览 | 合约编号 / 状态 tag / 产品名称与定价快照（定价模型 + 数值）/ 双方主体编号 / **模板引用（编号 + 版本快照——C-4.1 S2-3 核对面）** / 生效时间 / 完结时间 / 内容哈希 / 创建与更新时间 |
| B 当前条款全文（R7 `clauseValues.slots`） | 逐槽位渲染（键 → 名称映射优先经模板详情框架；键缺失时显示键名）+ 内容哈希对齐工具提示；**无数据本体段落** |
| C 条款版本链（R8） | 逐版：版本号 / 提案方 / 提案时间 / 双方确认时间 / **变更明细"从何值 → 到何值"**（V1 无明细显示"—"）；可展开条款全文 |
| D 协商动作条（按状态 + 我的角色显隐） | 协商中：「提案」「确认」「终止协商」；待签署 / 部分签署：「拒签终止」；已生效：「合意解除」；**已生效无"单方撤回签署"入口**（体验层；服务端强制）；动作结果一律以响应刷新（无乐观更新） |
| E 签署与存证区（R7） | 逐方签署状态（角色 / 主体编号 / DID / 签署时间；**签名值不出站无展示**）+ 存证事件摘要（内容哈希 / 双方 DID 与签署时间 / 存证时间）；「验签核验」按钮（R9）→ 结果表（逐方：结果 PASS / FAIL / UNAVAILABLE 原样 + 原因 + 核验时间）；「签署」弹窗（DID 必填；提示"可在『DID 管理』页查询双方 DID"） |
| F 策略视图（R7 `clauseValues.strategy`） | 五要素逐项：启用状态 + 取值（次数上限 / 期限起止 / 用途 / 域内范围 / 禁止再分发）+ 显式"无使用限制"声明；未启用要素显示"未启用" |
| G 策略与使用区 | ①「模拟试算」按钮（弹窗 C）②「测试台」按钮（抽屉/内联报告 D）③「发起使用」按钮（弹窗 E；**仅参与方**）④ 使用摘要（R12）：quota 已用/上限 + 放行/拒绝计数 + 执行记录表（操作者 / 动作 / 结果 tag / 触发要素 / 已用计数 / 时间；拒绝行高亮）+ 分页；⑤ 诚实边界说明（`SIMULATION_HONESTY_TIP` + `EXECUTION_DEMO_TIP`） |
| H 合约留痕区（R13，**仅 Q2-A**，分页） | 列：动作（发起/提案/确认/协商终止/签署/拒签终止/存证/合意解除/强制终止/治理查看/验签异常/越权拒绝）/ 版本号 / 操作者主体编号 / 时间 / 从何值→到何值 / 拒绝码；空态"暂无记录"；**不含敏感原文** |
| I 不可达分支 | 非参与方 / 不存在 → **同一条**提示常量（`CONTRACT_NOT_ACCESSIBLE_TIP`）；终止类合约查看终止类型与理由（R7 `terminationType/terminationReason/terminatedBy`——治理强制终止理由全文可见） |

### 6.5 页面 4：合约治理（`/contracts/governance`，运营档）

| 区块 | 内容 |
| --- | --- |
| A 全量列表（R10，分页） | 列：合约编号 / 产品名称 / 提供方 / 需求方 / 状态（含协商中 / 已终止）/ 终止类型 / 当前版本 / 生效时间 / 更新时间 / 操作（「查看治理详情」）；状态过滤 + 分页；**每次列表加载写一条治理查看留痕**（提示文案说明："查看即留痕"） |
| B 直查详情（R11） | 复用 §6.4 A~C、F、H 的各分区（只读），**无动作条**（治理页不提供参与方动作与"发起使用"入口）；「强制终止」按钮（弹窗 F） |
| C 留痕说明 | 每次查看写 `GOVERNANCE_VIEW`（谁/何时/看哪份合约）；合约参与方在详情留痕区（R13）可核对；非运营档访问 → 403 原样展示（读面拒绝不留痕，沿既有口径） |

### 6.6 文案与样式纪律

1. 状态 / 角色 / 类型 / 动作码 / 要素名的中文**一律**取 `constants/contract.ts`（页面源码不得出现裸枚举码或裸状态中文字面量——由源集守卫断言 T20）；
2. 错误展示统一 `ElMessage.error(err.message)`（后端业务文案），**不得改写、不得吞掉**；模拟试算的"拒绝"是**数据**（`allowed=false`）不是错误——**不弹错误提示**，以结果面板展示；
3. 同形拒绝（`1008C0001` / `1008C0002` / `1008C0003` / `1008C0010` / `1008C0012` / `1008C0017`）各自用**同一条**提示常量与样式（防存在性探测口径一致），不得前端区分"不存在 / 无权 / 停用 / 未上架"；
4. 危险动作（终止协商 / 拒签终止 / 合意解除 / 停用启用 / 强制终止 / 注销类）二次确认；取消 = **零请求**；必填留空 = 前置拦截（零请求）；
5. 写动作**不做乐观更新**：一律以响应为准刷新；被拒后界面状态不变；
6. 空态与无结果使用表格空态文案，不作报错展示；认证失效（`1000C0002`）→ 共享 `authGuide` 引导回登录页；
7. 诚实边界两则文案（`SIMULATION_HONESTY_TIP` / `EXECUTION_DEMO_TIP`）常驻"策略与使用"区（不折叠隐藏）。

### 6.7 弹窗清单

| 编号 | 弹窗 | 关键点 |
| --- | --- | --- |
| A | 新增 / 修订模板（W1/W2，运营档） | 名称（必填 ≤128）/ 类型（三选一；修订时只读）/ **条款框架槽位行编辑器**（增行 / 删行；每行：键〔英文标识〕+ 名称 + 必填勾选 + 填写说明；空框架 = 前置拦截零请求）；同名 `1008C0005`、框架非法 `1008C0004` 原样展示；修订成功提示新版本号（旧版本保留） |
| B | 发起合约（W5） | 产品编号（必填，直填）+「检索产品」回填（关键词检索目录域，结果点选回填编号与名称提示）/ 模板下拉（R4 启用中；选中后加载 R5 框架渲染条款表单 + 显示锁定版本号）/ 条款槽位表单（必填标记）/ 策略编辑器（至少一项或显式"无使用限制"——服务端判定，界面做说明文案）；成功 → 跳转详情；`1008C0010` / `1008C0016` / `1008C0017` / `1008C0014` / `1008C0015` 原样 |
| C | 提交提案（W6） | 条款值模式编辑器（值可改 + 增删槽位行）+ 策略编辑器；提交成功提示"版本 +1"；非协商中 `1008C0013` 原样 |
| D | 模拟试算（模拟端点） | 动作类型（使用 / 再分发）/ 用途（可选）/ 地域（可选）/ 假想已用次数（可选；缺省 = 真实计数，占位提示）/ 假想日期（可选；缺省 = 今天）/ 策略草稿（开关：开 → StrategyEditor，不填 = 用当前合约策略）；结果面板：放行/拒绝 tag + 触发要素列表 + 策略来源（生效策略 / 草稿）+ 策略快照回显 + 假想上下文回显 + 诚实边界文案；草稿非法 `1008C0015` 原样（表单错误提示） |
| E | 发起使用（受控执行） | 动作类型（使用 / 再分发）/ 用途（必填）/ 地域（必填）；提交：放行 → 成功提示 + `usedCount` 展示 + 刷新使用摘要；越界 `1008C0020` / 状态失效 `1008C0013` 原样展示 + 刷新摘要（拒绝行出现）；**无幂等提示**（重复提交 = 新一次调用，界面如实提示"每次提交计一次使用"） |
| F | 强制终止（W12） | 理由必填（1~512）；留空 = 前置拦截（零请求）；成功 → 刷新详情（状态"已终止" + 终止理由全文 + 留痕区新增行） |
| G | 终止协商 / 拒签终止 / 合意解除（W8/W10/W11） | 二次确认明示终态语义（"终止后不可恢复"）；合意解除为双方各自确认（先确认方展示"已确认，等待对方"） |
| H | 签署（W9） | DID 必填（文本框 + 取值路径提示）；`1008C0018`（DID 不可用）/ `1008C0013`（状态不允许）原样展示 |

---

## 7. 测试锚点（T1~T21；映射见任务卡 §三）

**后端（仅 Q2-A；Testcontainers MySQL 8 实跑，沿 `SharedMySqlContainer` 先例；新测试类）**

| 锚点 | 内容 | 规格依据 |
| --- | --- | --- |
| T1 | **R13 权限矩阵与同形拒绝**：参与方 200（四要素行齐：action / actorSubjectNo / fromValue / toValue + versionNo + reasonCode）/ 非参与方 → `1008C0012` **同码同文案** + `DENIED_ACCESS` 恰 1 行 / 不存在同码同文案 / admin（治理档）200 且**零新增留痕** / 未认证 401 / 无权限 403 / 分页字段齐备 + 越界 `1000C0001` + 排序稳定（同秒按 id 倒序） | 行为 2 规则 6、行为 7 规则 1/3/5 |
| T2 | **R13 值域与字段白名单**：12 动作码行齐备（构造 CREATE / PROPOSE / CONFIRM / SIGN / ATTEST / DENIED_ACCESS / GOVERNANCE_VIEW 等经真实写链路产生）/ `reasonCode` = 码位尾号（如 `C0012`）/ 字段白名单（无敏感原文、无签名值、无条款明文）/ 既有 **226 例零回归**（Skipped 0） | 行为 7 规则 4/5 |

**前端**

| 锚点 | 内容 | 剧本步骤 |
| --- | --- | --- |
| T3 | 模板浏览：类型过滤传参 + 列表渲染（仅启用中）+ 详情抽屉框架表（名称/必填/说明）+ 空态 + 分页；停用/不存在分支共用同一提示常量（反向探针：改文案必红） | C-4.1 S1-1/S1-2 |
| T4 | 模板未入驻：`1008C0003` 浏览语境统一文案**原样展示**（断言不出现"主体不存在"差异表述） | C-4.1 S1-3 |
| T5 | 模板运营：R1 列表（含停用）+ W1 新增（名称/类型/框架槽位行）成功 + 同名 `1008C0005` 原样 + W2 修订成功（新版本号）且 R2 版本历史可见旧版本 + W3/W4 停用/启用 + 同态 `1008C0007` 原样 + 普通档运营区隐藏（体验层） | C-4.1 S2-1/S2-2/S2-4/S2-6 |
| T6 | 模板留痕区（R3）：新增 / 修订（from→to 版本号）/ 启停 / `DENIED_MANAGE` 拒绝行渲染 + 空态 + 翻页 | C-4.1 S3-1（留痕核对面）/S3-3 |
| T7 | 工作台列表（R6）：状态/角色过滤传参 + 六态渲染（`constants/contract.ts`）+ 分页 + 空态 | C-4.2 S1~S3 入口面 |
| T8 | 发起弹窗（W5）：产品编号直填 + 「检索产品」调用目录域检索（断言跨域调用参数与目录角色头）+ 模板下拉（仅启用中、传 `templateVersionNo`）+ 框架槽位表单渲染（必填标记）+ **必填前置（无产品编号/无模板 = 零请求）** + 提交契约字段逐字（`productId/templateNo/templateVersionNo/clauseValues{slots,strategy}`）+ `1008C0010`/`1008C0016`/`1008C0017`/`1008C0015` 原样 + 成功跳详情 | C-4.2 S1-1/S1-4/S1-5/S1-6 |
| T9 | 条款/策略编辑器：框架模式逐槽位渲染；值模式（键只读 + 值可改 + 增删行）；策略五要素开关与取值输出形态逐字；**非法取值可提交**（断言不前端拦截 → 提交调用发生） | C-4.3 S1-3/S1-4 |
| T10 | 协商动作：提案成功提示（版本 +1，以响应为准）+ 确认按钮（双齐 → 待签署展示）+ 终止协商二次确认（取消 = 零请求）+ 非协商中 `1008C0013` 原样 | C-4.2 S1-2/S1-3/S1-8 |
| T11 | 签署与验签：签署弹窗 DID 前置（空 = 零请求）+ 部分签署状态渲染 + 生效时间展示（= 最后一签）+ 存证摘要渲染 + 拒签终止二次确认 + 验签报告逐方渲染（PASS/FAIL 原样）+ `1008C0018` 原样 + **已生效无"单方撤回"按钮**（反向探针：按钮复现即红） | C-4.2 S2-1~S2-5 |
| T12 | 策略视图 + 模拟试算：五要素全文渲染（含"未启用"与无限制声明）+ 模拟弹窗假想上下文提交形态 + **拒绝 = 数据展示（不触发错误提示）** + violations 渲染 + 草稿策略 `1008C0015` 原样 | C-4.3 S1-2/S1-5；ADR-021 §2.2 |
| T13 | 测试台：一键运行 + 报告三态渲染（PASS/FAIL/SKIPPED + summary 计数 + runAt）+ 要素未启用行 SKIPPED 展示 | 行为 5 规则 4；ADR-021 §2.5 |
| T14 | 受控执行：发起使用提交形态逐字（`actionType/purpose/territory`）+ 放行提示含 `usedCount` + 越界 `1008C0020` 原样 + 状态失效 `1008C0013` 原样 + 提交后刷新使用摘要（拒绝行出现）+ 治理页**无**"发起使用"入口（反向探针） | C-4.3 S2-1/S3-1/S3-6/S3-7 |
| T15 | 使用摘要（R12）：quota 已用/上限渲染 + 放行/拒绝计数 + 记录行（拒绝行含触发要素）+ 分页 + 空态 + 无配额口径（quota=null）渲染 | C-4.3 S2-2/S2-3 |
| T16 | 合约留痕区（R13，Q2-A）：四要素 + from→to + 拒绝码行渲染 + 治理查看/越权拒绝行 + 空态 + 翻页 | C-4.2 S1-1/S2-1/S3-1/S3-2/S3-4（留痕核对面） |
| T17 | 治理页：R10 列表（含协商中/已终止 + 终止类型）+ R11 详情（复用分区只读、无动作条）+ 强制终止（理由前置零请求 / 成功刷新 / 终止理由全文展示）+ 非 admin 路由守卫（体验层） | C-4.2 S3-2/S3-3 |
| T18 | **演示身份**：普通档 `provider`（**断言不含 `admin`**）/ 运营档 `admin` / 空主体零请求 / `contractRolesHeader` 单点（源集守卫：`'admin'` 角色头字面量仅限 `stores/demoIdentity.ts` 与 `api/contract.ts`）/ 跨域产品检索走目录角色头 | 剧本多主体对照 |
| T19 | 路由与菜单：admin 16 项 / user 5 项；三条新路由权限守卫（user → `/dashboard?denied=1`）；`menuOrder` 最大值 16（两处断言）；深链路由不占菜单且在列表路由之后 | 界面可达性 |
| T20 | 源集守卫：① 四个视图 + 两个组件源码无裸枚举/状态中文字面量（须经 `constants/contract.ts`）；② 无 `fetch(` 直连；③ 同形提示常量在页面中"只被引用、不被散写"（反向探针） | §6.6；lofi Q1/Q4 |
| T21 | 同形与深链：合约详情深链非参与方/不存在 → 同一条提示常量（`CONTRACT_NOT_ACCESSIBLE_TIP`；断言不区分差异表述）；治理直查对象缺失同款；**一切写动作被拒后界面状态不变（不做乐观更新断言）**；错误文案原样展示（不吞不改写） | C-4.2 S3-1/S3-4；行为 7 规则 1 |

---

## 8. 边界值与异常行为

1. **必填前置（零请求）**：演示身份主体编号（各 API 封装统一）/ 发起（产品编号、模板、条款必填槽位）/ 签署 DID / 强制终止理由 / 模板新增（名称、类型、框架非空）——前端 `maxlength`/必填校验对齐后端口径；
2. **长度门槛**：模板名称 ≤128、槽位值 ≤2000 字符、条款值总量 ≤32768 字节、强制终止理由 1~512（前端 `maxlength` 对齐，服务端为准）；模板框架键为英文标识（界面提示格式，服务端判定为准）；
3. **空态与无结果**：模板空 / 我的合约空 / 版本链（仅 V1 无明细显示"—"）/ 使用记录空 / 留痕空 / 治理列表空 → 一律空态文案（非报错）；
4. **同形口径**：`1008C0001`（模板不可用）/ `1008C0002`（模板维护越权）/ `1008C0003`（未入驻统一文案——浏览/发起/维护三语境后端原文）/ `1008C0010`（产品不可用）/ `1008C0012`（合约不可见）/ `1008C0017`（模板不可发起）在界面上各为**同一条**提示，不区分内部差异；
5. **模拟通道语义**：模拟"拒绝"是数据（`allowed=false`），**不得**展示为错误弹窗；模拟请求零副作用——界面**不得**在模拟后本地增减计数（只在真实执行后重拉 R12）；
6. **乐观更新禁止面**：所有写动作（发起 / 提案 / 确认 / 终止 / 签署 / 拒签 / 解除 / 强制终止 / 模板维护 / 受控执行）被拒后**不得**改本地数据，一律以响应为准刷新；
7. **档位门槛提示**：运营档打开参与方面（发起 / 签署 / 受控执行）→ 原样展示服务端拒绝 + 体验层提示切回普通档；普通档打开治理页 → 菜单不可见（体验层）+ 直链 403 原样；
8. **分页越界**：`pageNum` 超界 → 后端 `1000C0001` 原样展示；
9. **服务不可达**：`1008S0001`（主体服务）/ `1008S0002`（DID 服务）/ `1008S0003`（目录服务）原样展示；前端不伪造成功；
10. **401/403**：`1000C0002` → 共享 `authGuide`（提示 + 清登录态 + 回登录页）；`1000C0005` 原样展示；
11. **幂等语义**：发起（W5）与模板新增/修订（W1/W2）为服务端业务键幂等（ADR-007）——界面重复提交返回首次结果（提示不显示"新增失败"）；**受控执行无幂等键**（按调用计数，重试 = 新一次调用）——界面如实提示"每次提交计一次使用"，不做去重伪装；
12. **越权步骤的界面观察点**：非参与方合约在界面上**无入口**（列表恒仅本人参与、详情同形拒绝）——这正是服务端强制的正确行为；拒绝留痕（`DENIED_ACCESS`）由技术侧接口直调产生，参与方可在详情留痕区（R13）核对（剧本修订承载说明，见 §9）；
13. **签署取值链路**：DID 手输（不代查不预填）；`1008C0018` 覆盖"DID 未登记 / 已吊销 / 非签署方归属"三情形（原样展示，不前端预判）。

---

## 9. 剧本步骤 → 界面入口映射（交付后"界面核对修订"的对照基线）

> 口径：**能界面的走界面**；越权类步骤因"他人对象在界面上无入口"（安全面正确行为）按 **技术侧配合 + 留痕区核对** 承载（沿 C-3.2 S3-2 先例）；技术侧配合步骤（幂等重放 / 直调接口）逐条标注；业务判定一字不改，修订由 PO 批准。

| 剧本 | 幕 / 步骤 | 本包界面入口（拟定名称）与承载 |
| --- | --- | --- |
| C-4.1 | S1-1 | 「合约模板」→ 浏览列表（三类预置、启用中）|
| C-4.1 | S1-2 | 「合约模板」→ 行「查看详情」→ 条款框架表（无本体段落） |
| C-4.1 | S1-3 | 「合约模板」→ 以 C 主体（未入驻）打开 → 统一业务文案原样 |
| C-4.1 | S2-1 | 「合约模板」→ 运营维护页签 → 行「修订」→ 槽位行编辑器加一个可选条款项 → 发布（新版本号 + 旧版本保留） |
| C-4.1 | S2-2 | 「合约模板」→ 浏览列表 → 该模板显示最新版本 V2 |
| C-4.1 | S2-3 | 「合约工作台」→ 草案详情「模板引用（编号 + 版本快照）」字段 + 条款全文对照（**技术侧取数对照可并行**） |
| C-4.1 | S2-4 | 「合约模板」→ 运营区 →「停用」（二次确认）→ 浏览列表不再呈现 |
| C-4.1 | S2-5 | **技术侧配合**（直调 W5 以停用模板发起 → `1008C0017`；界面模板下拉只列启用中——体验层） |
| C-4.1 | S2-6 | 「合约模板」→ 运营区 →「启用」→ 恢复可浏览 |
| C-4.1 | S3-1 | 普通档打开「合约模板」→ 运营区不可见（体验层）；**技术侧配合**（以 B 主体直调 W1~W3）→ `1008C0002`；**运营在运营区留痕页签核对 `DENIED_MANAGE` 行** |
| C-4.1 | S3-2 | **技术侧配合**（C 主体直调维护）→ 统一业务文案；界面承载同 S3-1 |
| C-4.1 | S3-3 | 「合约模板」→ 运营区「维护留痕」页签（四要素 + 版本号 + 拒绝行） |
| C-4.2 | S1-1 | 「合约工作台」→「发起合约」（产品检索 / 直填 + 模板 + 条款与策略 + 提交）→ 详情（协商中 + 快照字段） |
| C-4.2 | S1-2 | 详情 →「提案」（条款与策略编辑）→ 版本 +1 + 变更明细 |
| C-4.2 | S1-3 | 详情 →「确认」（双方）→ 待签署 |
| C-4.2 | S1-4 | 「发起合约」→ 产品编号直填（技术侧代造下架态产品编号）→ `1008C0010` 原样 |
| C-4.2 | S1-5 | 「合约工作台」→ 以 C 主体（未入驻）打开 → 统一业务文案 |
| C-4.2 | S1-6 | 「发起合约」→ 以 A 主体对 A 的产品发起 → `1008C0016` 原样 |
| C-4.2 | S1-7 | **技术侧配合**（同请求标识重放） |
| C-4.2 | S1-8 | 详情 →「终止协商」（二次确认）→ 终态 + 留痕行 |
| C-4.2 | S2-1 | 详情 →「签署」（DID）→ 部分签署展示 |
| C-4.2 | S2-2 | 详情 → B「签署」→ 已生效 + 生效时间（最后一签）+ 存证摘要 |
| C-4.2 | S2-3 | 详情 →「验签核验」→ 逐方结果（PASS / DID / 时间） |
| C-4.2 | S2-4 | 对照合约详情（部分签署态）→「拒签终止」（二次确认）→ 终态 + 留痕 |
| C-4.2 | S2-5 | **技术侧配合**（直调 W10 于已生效合约 → `1008C0013`；界面无撤回入口——体验层正确行为） |
| C-4.2 | S3-1 | 非参与方按编号直开详情/深链 → **同一条提示**（`1008C0012`）；**技术侧配合**（直调同判）→ 参与方在留痕区（R13）核对 `DENIED_ACCESS` 行 |
| C-4.2 | S3-2 | 「合约治理」→ 列表 + 按编号直查详情 → 允许；**留痕区（R13）出现 `GOVERNANCE_VIEW` 行** |
| C-4.2 | S3-3 | 「合约治理」→ 对照生效合约 →「强制终止」填理由 → 终态 + 终止理由全文 + 留痕行（含理由与操作者） |
| C-4.2 | S3-4 | **技术侧配合**（B 直调 W12/W8 于他人合约）→ `1008C0011`/`1008C0012`；留痕区核对拒绝行 |
| C-4.2 | S3-5 | 对照合约详情 →「合意解除」（双方各确认）→ 已完结 + 留痕 |
| C-4.2 | S3-6 | 「产品上架」（C-3.3 界面）→ 对照组在架产品「下架」→ 回「合约工作台」：既有生效合约状态不变（详情核对）；对该产品新发起 → `1008C0010` 原样 |
| C-4.3 | S1-1 | C-4.2 链路（详情「签署」）——策略条款随发起/提案时用策略编辑器配置（附录 B 预置值）|
| C-4.3 | S1-2 | 详情「策略视图」→ 五要素全文 + 无限制声明 |
| C-4.3 | S1-3 | 详情「提案」→ 策略编辑器次数填负数 → 提交 → `1008C0015` 原样（示例文案） |
| C-4.3 | S1-4 | 详情「提案」→ 策略全不启用且不勾"无使用限制" → 确认定稿时被拒（`1008C0015`）原样 |
| C-4.3 | S1-5 | **技术侧配合**（直调 W6 于已生效合约 → `1008C0013`；界面已生效无"变更策略"入口——体验层正确行为） |
| C-4.3 | S2-1 | 详情「发起使用」×3（用途"风控建模"、域内"本市域"）→ 全部放行 + 计数 3 |
| C-4.3 | S2-2 | 详情「使用摘要」→ 计数 3 与实际一致 |
| C-4.3 | S2-3 | 提供方登录同一合约详情 → 使用摘要可见（双方可查） |
| C-4.3 | S3-1 | 「发起使用」直至第 101 次 → `1008C0020` 原样 + 摘要拒绝行（触发要素 = 次数）；**技术侧配合**（第 100→101 次演示口径可经对照合约或受控执行端点连续提交） |
| C-4.3 | S3-2 | 对照届满态合约「发起使用」→ `1008C0020`（触发要素 = 期限） |
| C-4.3 | S3-3 | 「发起使用」用途填"营销外呼"→ 拒（触发要素 = 用途） |
| C-4.3 | S3-4 | 「发起使用」地域填市域外 → 拒（触发要素 = 域） |
| C-4.3 | S3-5 | 「发起使用」动作类型选"再分发" → 拒（触发要素 = 再分发） |
| C-4.3 | S3-6 | **技术侧直调本身即"绕过界面"路径**：受控执行端点直调越界 → 同判 `1008C0020`（界面按钮仅为入口之一，判定在服务端）；界面侧承载 = T14 断言 |
| C-4.3 | S3-7 | 「合约治理」强制终止对照合约 → 回详情「发起使用」→ `1008C0013` 原样（策略同步失效） |
| C-4.3 | S2/S3 幕（模拟/测试台） | 详情「模拟试算」（假想上下文复现五类越界，不消耗计数）+「测试台」一键报告（双向用例可视化）——3.4.6 移交-1 的界面兑现面 |

> 三剧本**业务判定一字不改**；交付后按实际界面名称/路径 **+ 上表承载口径**核对修订并提示 PO 批准（W15）；跟踪-12（C-4.3 剧本同步建议）随交付说明给 PO 一并裁决。

---

## 10. 变更登记

| 面 | 变更 |
| --- | --- |
| 契约 | 零 ADR 变更；Q2-A 时登记新增 1 个只读端点（既有 27 端点零改动） |
| 错误码 | 零新增（复用 `1008C0001`~`C0020`、`1008S0001`~`S0003`、`1000C0001/0002/0005`） |
| 枚举 | 零新增（复用六态 / 终止类型 / 模板两态 / 12 动作码） |
| 库表 / 迁移 | 零新增、零迁移（Q2-A 仅新增只读分页查询） |
| 依赖 | 前端零新增；后端零新增模块依赖 |
| 配置 | 服务端零改动；`frontend/vite.config.ts` 开发期转发新增 2 键 → 8085；**门禁配置零改动** |
| 前端 | 4 视图 + 2 共享组件 + 2 模块（api/constants）+ 1 store 扩展 + 3 菜单项/4 路由；既有 2 个 spec 断言同步；e2e 零改动 |
| 后端 | 仅 Q2-A：1 只读端点 + 1 视图 DTO + 1 仓储只读分页方法 + 1 服务方法 + 1 组测试（新测试类）；既有 27 端点与共享守卫零改动 |
| 剧本 | 三剧本界面入口核对修订（名称/路径 + 越权类/技术侧步骤承载说明，业务判定不变，提示 PO 审批）；跟踪-12 建议随交付说明一并裁决 |
| 演示构件 | contract 服务（Q2-A 时）与前端需重新构建；走查前预置多主体 + 演示载体（在架产品链 + 演示合约）+ 执行 Q7-A 清理（须授权） |

---

## 11. 实施前置检查项

同 lofi §6 八项（编码会话第一步逐项实测留痕，本节不重复）。

---

## 12. 勘误登记（实施期回填）

**（编码/评审期按 `E1…` 顺延登记，格式沿 `WBS-3.2.6-hifi.md` §12 / `WBS-3.3.6-hifi.md` §12 先例）**

| 编号 | 位置 | 原表述 | 实施口径（澄清，非改需求） | 登记 |
| --- | --- | --- | --- | --- |
| — | — | — | — | — |

---

## 确认记录

> **确认留痕（2026-10-11，编排师会话回复"立卡任务已确认，继续"）**：**Q1~Q7 均采建议口径 A + D1 不拆分**——本文件转 **V1.0（编码契约）**（实现与设计逐条一致，评审①锚）；`docs/designs/WBS-3.4.7-lofi.md` 同批转 **V1.0（方向定稿）**（确认记录亦落其末节）；随后进入编码段（测试先行，新会话冷启动——第一动作 = §11 前置检查项八项逐项实测留痕，随后后端 T1~T2（R13）+ 前端 T3~T21 先红，红相即时归档 `build-output/w347-red-phase-*.txt`〔3.4.4 教训-1〕）。V0.9 草案随立卡批 `4678d8e` 落盘。