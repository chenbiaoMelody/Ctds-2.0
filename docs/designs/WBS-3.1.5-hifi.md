# WBS-3.1.5 主体审核后台 · 高保真设计

- 上游：`docs/designs/WBS-3.1.5-lofi.md`（同批一次确认）；规格 C-1.1 **V1.1** 行为 5；剧本 S3 步骤 4
- 性质：编码契约——实现与本文件不一致 = 打回项；本文件只从规格与已确认 lofi 派生，不新增规格外行为

## 确认记录（人签署；未签署 = 未确认 = 禁止进入编码）

| 轮次 | 结论（确认/打回） | 确认人（PO） | 日期 | 意见（打回必填） |
| --- | --- | --- | --- | --- |
| 1 | 确认 | 项目主导者（兼任 PO；会话回复"**三问均采建议**"——lofi 三问按建议口径裁决（A/A/全量），两级设计一次确认生效；表格由 AI 按该声明代录留痕） | 2026-09-13 | 无 |

## 行为清单（10 项，逐条对应规格与计划测试）

| # | 行为 | 规格依据 | 验证方式 |
| --- | --- | --- | --- |
| B1 | 审核员调清单端点，分页返回"待审核"主体（申请编号/主体名称/主体类型/申请时间），无待审核时返回空页 | 行为 5 第 1 条前半 | 集成：清单过滤断言（仅 PENDING_REVIEW）+ 空页 + 分页参数 |
| B2 | 审核员查看认证档案：复用既有档案查询端点与影像查看端点（reviewer 归属豁免 3.1.3 已通）；影像放大为前端呈现，查看行为留审计（既有 ACTION_IMAGE_VIEW 口径不变） | 行为 5 第 1 条后半 | 集成回归：reviewer 查企业/政务档案既有用例不回退 |
| B3 | 授权审核员执行"通过"：PENDING_REVIEW→ADMITTED，触发方=REVIEWER，留痕备注"审核通过"，审计动作 `certification.review.approve` SUCCESS | 行为 5 验收-1 | 集成：**直查库 subject.status=ADMITTED** + 留痕四要素 + 审计断言 |
| B4 | 审核员执行"驳回"：必填理由（空/全空白被 400 拒绝），PENDING_REVIEW→REJECTED，理由随留痕备注落库，审计动作 `certification.review.reject` SUCCESS | 行为 5 第 2 条 + 验收-2 | 集成：空理由 400 直查库状态不变；有理由 → 直查库 REJECTED + 备注含理由 |
| B5 | 状态门槛拒绝态：非 PENDING_REVIEW（已入驻/已驳回/待认证/认证失败）调任一审核端点 → 400 + 既有 1004C0002；**并发双审核**（两线程一通过一驳回同主体）乐观门槛单胜出，败者 1004C0002 | 行为 5 第 2 条 + 行为 4 状态机 + 3.1.4 教训（拒绝态负向用例强制） | 集成：四状态拒绝各 1 例 + 并发用例（latch 握手，胜者唯一断言） |
| B6 | 权限边界：审核三端点 `@RequirePermission("subject.review")`；无权限角色 → 403 + 拒绝审计（common-auth 既有 RBAC 审计口径）；**审核端点不走 OwnershipGuard**（reviewer-only 端点，权限即准入；申请人侧端点归属断言口径不变） | 行为 5 第 3 条 + 验收-3 | 集成：applicant 调审核端点 403 + 审计断言 |
| B7 | 驳回后申请人可修改重报（既有重报机制 + RESUBMIT_AFTER_REJECT_REMARK 留痕，本包零改动），Vue 申请人侧对"已驳回"展示驳回理由 | 行为 4 验收-2 | 回归：既有重报用例不回退；Vue 用例：驳回理由可见 |
| B8 | 3.1.4 观察项：①上传文件名控制字符归一化（U+0000~U+001F/U+007F 剥除、首尾空白去除、长度截 255）作用于执照上传与政务证书上传两入口，归一化后落库与档案展示；②TOCTOU 并发用例（见 B5）；③写放大监控登记升级至 2.5.x 监控基座（本包不实现，交付说明留痕）；④requireCertFile/requireImage 参数化评估（编码期核实，若审核端复用则参数化，结论随交付说明） | 3.1.4 日志交接提醒 | 单测：归一化三例（控制字符/空白/超长）；并发用例见 B5 |
| B9 | 审核工作台 Vue 页面：清单页 + 档案详情页 + 通过/驳回交互（驳回理由必填弹窗）+ 影像放大查看；菜单"主体审核"按 `subject.review` 权限点显隐（演示期 admin 兼审核员，lofi 问题 1 采 A）；不提供政务证书原件下载（lofi 问题 2 采 A） | 行为 5 第 1~2 条 + 3.1.3 Q2 裁决 | Vitest：清单渲染/驳回空理由拦截/权限菜单显隐/路由守卫 |
| B10 | 申请人侧 Vue 页面（3.1.2/3.1.3/3.1.4 静态原型 Vue 化，**接口与字段口径逐字复用已确认原型与 hifi 契约，不改接口**）：注册表单（企业/政务分支）、认证交互（上传执照→OCR 确认→法人核验；政务主体→政务 CA 证书提交）、状态与档案展示、驳回理由可见 | 行为 1/2/3/6 已交付口径的界面化 | Vitest：表单分支渲染/上传交互/状态展示 |

## 接口契约（编码契约 = 本表定稿）

### REST 端点（新增 `ReviewController`，权限一律 `subject.review`）

| 方法 | 路径 | 入参 | 出参 | 说明 |
| --- | --- | --- | --- | --- |
| GET | `/api/v1/subject/review/queue` | query：`pageNum`（默认 1）、`pageSize`（默认 10，上限 100） | `ApiResult<PageResult<ReviewQueueItem>>` | 固定 status=PENDING_REVIEW，按申请时间升序 |
| POST | `/api/v1/subject/registrations/{subjectNo}/review/approval` | 无 body | `ApiResult<ReviewActionResult>` | 通过：转已入驻 |
| POST | `/api/v1/subject/registrations/{subjectNo}/review/rejection` | body：`{ "reason": string }`（业务上限 = 配置参数 `review-reason-max-length` 默认 200，服务层校验；DTO `@Size(max=512)` 为传输面兜底；**硬上限 251** = 留痕列 VARCHAR(256) 减"审核驳回："前缀，调大配置将致落库失败） | `ApiResult<ReviewActionResult>` | 驳回：转已驳回，理由入留痕备注 |

### 响应记录（应用层 record）

```
ReviewQueueItem(String subjectNo, String subjectName, String subjectType, LocalDateTime createdAt)
ReviewActionResult(String subjectNo, String status)   // status = ADMITTED / REJECTED
```

### 流转与留痕（复用既有机制，零迁移）

- 流转一律 `SubjectStatusService.transition(subjectId, PENDING_REVIEW, ADMITTED|REJECTED, transition)`；
  `TriggerRole.REVIEWER`（3.1.3 预留枚举）；留痕备注：通过 = "审核通过"，驳回 = "审核驳回：" + 理由。
- 审计动作常量（新增于应用服务，沿既有命名域）：`ACTION_REVIEW_APPROVE = "certification.review.approve"`、
  `ACTION_REVIEW_REJECT = "certification.review.reject"`。
- 仓储新增方法（**实现补正，4 视角评审**：原契约为 `findByStatus(SubjectStatus, PageQuery) → PageResult<Subject>`；因架构门禁 LayerRulesTest 限定领域层不得依赖 common-pagination，收敛为 `countByStatus(SubjectStatus) → long` + `findByStatus(SubjectStatus, int offset, int limit) → List<Subject>`（申请时间升序稳定排序），`PageResult` 组装归应用层 `ReviewService.queue()`——语义等价，偏离已在 `SubjectRepository` javadoc 留痕）。

## 前端设计（真实 Vue 页面，Element Plus + 既有骨架）

### 路由与菜单

| 路由 | 组件 | meta | 说明 |
| --- | --- | --- | --- |
| `/review` | `views/review/QueueView.vue` | title 主体审核 / menu / icon / permission `subject.review` | 清单页 |
| `/review/:subjectNo` | `views/review/DetailView.vue` | title 审核详情 | 档案 + 操作 |
| `/subject/register` | `views/subject/RegisterView.vue` | title 主体入驻 / menu | 注册表单（企业/政务分支） |
| `/subject/certification/:subjectNo` | `views/subject/CertificationView.vue` | title 认证与档案 | 认证交互 + 状态展示 |

- 演示期身份传递（无网关直连，3.9.1 前过渡口径）：新增 `src/api/client.ts` fetch 封装，统一附
  `X-Ctds-Subject`（演示身份）与 `X-Ctds-Roles`（演示角色映射 applicant/reviewer 头）请求头；
  错误响应按 `ApiResult` 结构提取九位错误码与业务文案展示。
- 演示角色：沿用 user/admin（lofi 问题 1 采 A）；admin 兼任审核员（菜单可见 + 审核操作可用）。

### 审核工作台交互要点

1. 清单页：el-table（申请编号/主体名称/主体类型/申请时间/操作"查看档案"）+ el-pagination；
   空态文案"暂无待审核主体"。
2. 详情页：档案分区（注册信息 / 证照影像 / OCR 识别结果 / 核验记录；政务主体显示政务证书验证段、
   不显示法人核验段——3.1.4 档案口径）；影像缩略图点击放大（调既有影像查看端点 dataUrl，el-dialog 呈现）。
3. 操作区：通过（二次确认 elMessageBox）→ 调 approval → 成功提示"已入驻"并返回清单；
   驳回 → el-dialog 理由输入（必填校验，空值禁提交）→ 调 rejection → 提示"已驳回"。
4. 错误态：1004C0002（状态已变化）提示"当前状态不允许执行审核操作"并刷新档案；403 提示无权限。

### 申请人侧交互要点

1. 注册表单：主体类型切换企业/政府部门分支（政务分支隐藏执照字段、显示政务 CA 证书上传，规格行为 6
   "全程不出现"的界面兑现）；字段与校验逐字复用 3.1.2 已确认原型。
2. 认证页：企业流程 = 上传执照 → OCR 回填核对确认 → 法人核验（按 3.1.3 原型）；政务流程 = 提交证书 →
   结论展示（失败原因可见、可换证重提——3.1.4 原型政务分支）；状态徽标随档案端点刷新；"已驳回"展示驳回理由
   （取流转留痕/档案口径，B7）。
3. 页面文案与布局以已确认静态原型为准（Vue 化不改交互与字段），界面说明书补充件 = 本节 +
   `docs/designs/WBS-3.1.5-原型-审核工作台.html` 线框。

## 边界值与异常行为

| 编号 | 场景 | 预期 |
| --- | --- | --- |
| E1 | 驳回理由为空/全空白 | 400 参数校验拒绝（理由必填文案），状态不变（直查库断言） |
| E2 | 驳回理由 > 200 字符 | 400 参数校验拒绝 |
| E3 | 已入驻/已驳回/待认证/认证失败主体调审核端点 | 400 + 1004C0002，出审计 DENIED |
| E4 | 并发双审核同一主体（一通过一驳回） | 恰一个成功、一个 1004C0002（乐观门槛；latch 握手防 sleep） |
| E5 | 无 subject.review 权限调清单/通过/驳回 | 403 + 拒绝审计（common-auth 既有口径） |
| E6 | pageNum=0 / pageSize>100 | **400 参数校验拒绝**（实现补正，4 视角评审：common-pagination `PageQuery.of` 为拒绝语义，非收敛；未传参数默认 pageNum=1 / pageSize=10） |
| E7 | 上传文件名含控制字符/超长 | 归一化后落库与档案展示（B8①），上传行为本身不受影响 |
| E8 | 清单无待审核主体 | 空页（total=0），非错误 |

## 配置项（application.yml，沿 3.1.3 先例；前缀 `ctds.certification`）

| 键 | 默认 | 说明 |
| --- | --- | --- |
| `review-reason-max-length` | 200 | 驳回理由长度上限（E2） |

## 依赖清单

- 零新外部依赖（后端复用 common-pagination/auth/errorcode/logging + 既有 Testcontainers 体系；
  前端复用 Element Plus / vue-router / vitest 既有锁定版本）。`dependencies.md` 无需变更。

## 映射表（规格验收标准 → 设计行为 → 测试 → 剧本）

| 规格验收标准（行为 5） | 设计行为 | 测试 | 剧本 |
| --- | --- | --- | --- |
| 待审核主体执行"通过"→ 已入驻，档案记录审核人与时间 | B3 | 集成：直查库 + 留痕四要素 + 审计 | S3 步骤 4 后半（本包后可执行） |
| 驳回未填理由 → 拒绝并提示理由必填 | B4/E1 | 集成 400 + 状态不变 | S1/S3 演示补充步骤（剧本同步建议见下） |
| 未授权角色调用 → 拒绝并留拒绝记录 | B6/E5 | 集成 403 + 审计 | 界面说明注记（非授权菜单不可见） |
| 清单与档案可见、影像可放大 | B1/B2/B9 | 集成清单过滤 + Vitest 渲染 | S3 步骤 4 前半 |
| 并发门槛（行为 4 状态机完整性） | B5/E4 | 集成并发用例 | —（质量门） |
| 剧本更新 | — | — | **实现补正（4 视角评审）**：剧本 V1.0 已含 S1 步骤 8~10 与 S2 步骤 5~7，无需新增步骤；真实缺口 = ①S1 步骤 9"档案中新增审核记录"的屏幕可见性（随审核详情页"流转留痕"分区交付兑现）②S2 步骤 7 幂等窗口注记（重报落在注册后 600s 内会返回首次结果，剧本加判定说明）——随交付说明提交 PO 审批 |

## 规格缺口声明

1. 驳回理由上限与清单排序/分页参数：规格未定，本文件按"设计细化"授权定参（E2/E6），不加严规格语义。
2. 无规格外需求；申请人侧 Vue 页面为 3.1.2/3.1.3/3.1.4 已确认设计交付物的界面化，非新增行为。

## 问题确认：

已确认——PO 于 2026-09-13 会话回复"三问均采建议"（演示角色 A：admin 兼审核员；证书原件 A：不提供查看/下载；Vue 范围：审核工作台 + 申请人侧全量），两级设计一次确认生效。
