# WBS-3.7.4 低保真设计：产品评价系统（V1.0 已确认 = 方向定稿，2026-11-01）

> 派生自 `docs/specs/C-7.1-7.2-门户与计费.md` V1.0 行为 B5 + B6；任务卡 `docs/tasks/WBS-3.7.4-产品评价系统-2026-11-01.md`。
> 发现规格缺口走变更流程，本设计不私自补需求。**V1.0 = 2026-11-01 11:59 编排师一次确认（"全部按建议确认"：Q1~Q8 采 A + D1 不拆分）后的方向定稿。**

## 1. 做什么 / 不做什么

### W（做什么，8 项）

| # | 项 | 说明 |
| --- | --- | --- |
| W1 | 评价表迁移 | delivery-service 迁移 V4：`product_review`（uk 一人一产品一评）+ `product_review_action_log`（提交/更新/隐藏留痕） |
| W2 | 评价领域与应用服务 | 聚合/仓储/服务：提交与更新（upsert）、交付资格同库校验、统计、文字列表、本人回显、运营隐藏（留痕不删除） |
| W3 | 评价端点 5 个 | W-EV1 提交/更新；R-EV1 统计；R-EV2 文字列表；R-EV3 本人回显；G-EV1 运营隐藏（详见 hifi §1） |
| W4 | 错误码 1011 续段 | C0005 起评价域定稿（评分非法/无交付资格/不存在或非本方等，hifi §4） |
| W5 | 门户详情页评价区 | 统计条（均分+评价数）+ 文字评价列表 + 有资格者提交表单 + 本人评价回显修改 + 运营隐藏按钮（运营档可见） |
| W6 | 前端模块与守卫 | `api/review.ts` + `constants/review.ts`；评价区请求带 `deliveryRolesHeader()`；`PortalSourceGuard` 守卫面扩展（评价域收口） |
| W7 | 网关与 vite | `connector-evaluations` 路由 → 8087 + 路由测试；vite `/api/v1/evaluations` 转发键 |
| W8 | 文案锚与剧本修订 | `PORTAL_BROWSE_DESC` 终值修正（评价开放 + 订购补正）；C-7.1 剧本 V1.3 S2-8~12 界面入口核对（业务判定一字不改） |

### N（不做什么，8 项）

| # | 项 | 归属/理由 |
| --- | --- | --- |
| N1 | 订单/合约/交付既有端点语义与错误码改动 | 3.7.3/3.5.2 已交付面零触碰（资格查询 = 新增只读仓储方法） |
| N2 | catalog 产品读面（R7）改动、评分字段并入 R7 | catalog 零改动；统计走评价域端点 |
| N3 | 文字评价排序/精选/点赞/回复等运营语义 | 规格 B5-3 明文"沿最小实现"；运营管理界面归 3.7.7 |
| N4 | 评价审核流、敏感词过滤、举报机制 | 超规格（隐藏 = 最小治理动作，规格 B5-4） |
| N5 | 计费/计量联动、评价激励 | 归 3.7.5+ / C-7.3 |
| N6 | 推送/消息/轮询 | B6 明文不做 |
| N7 | 新服务/端口/库/镜像/依赖/权限点（按 Q4 裁决） | 复用 delivery.read/governance + 既有 @Idempotent |
| N8 | 跨服务内部通道（catalog→delivery 等） | Q1-A 同库判定后无此需要；不触碰 `/internal/**` |

## 2. 结构组成

```
delivery-service（8087）
├─ db/migration/V4__create_product_review_tables.sql      ← W1 两表
├─ domain/review/        ProductReview / ReviewAction / ReviewErrorCodes(1011C0005+) / ProductReviewRepository
├─ infrastructure/review/ JdbcProductReviewRepository（含交付资格 EXISTS 只读查询）
├─ application/review/   ReviewCommandService（提交/更新 upsert + 资格校验）
│                        ReviewQueryService（统计/列表/mine）
│                        ReviewGovernanceService（隐藏留痕）
└─ interfaces/           ReviewController（W-EV1/R-EV1/R-EV2/R-EV3）+ ReviewGovernanceController（G-EV1）

gateway-service（8086）  connector-evaluations 路由（/api/v1/evaluations/** → 8087）+ 路由测试

frontend（5173）
├─ api/review.ts          评价域单点封装（沿 api/order.ts 模式）
├─ constants/review.ts    评价文案/常量收口（沿 constants/order.ts 模式）
├─ views/portal/PortalProductDetailView.vue  新增「产品评价」区块（统计条+列表+表单+隐藏）
├─ constants/catalog.ts   PORTAL_BROWSE_DESC 终值修正
└─ 守卫/测试              PortalSourceGuard 扩展 + 新增 spec + 既有锚同步
```

## 3. 主要流程（业务语言）

### 3.1 提交/更新评价（S2-8 / S2-9 / S2-11）

需方在产品详情页评价区填写评分（1~5 星）与可选文字 → 提交：
① 服务端校验评分 ∈ [1,5]（非法 → 1011C0005，S2-9 负向）；② 同库校验交付资格 `EXISTS(delivery: requester_no=本人 ∧ product_id=产品 ∧ result='SUCCESS')`（无 → 1011C0006 业务可读拒绝，S2-10 负向——**无资格者界面表单可见可提交**，拒绝理由业务可读展示，负向演示路径 S2-10 必需）；③ uk(product_id, requester_no) 判存：无 → 新增评价行 + CREATE 留痕；有 → **更新本人既有评价行**（评分/文字/更新时点）+ UPDATE 留痕（评价总数不变，S2-11）；④ 前端刷新统计与列表。幂等 = upsert 天然语义 + uk 并发兜底（DuplicateKey 分支转更新），零新增幂等组件接入（Q8-A）。

### 3.2 展示（S2-12 前半）

详情页评价区加载：统计端点返回**仅未隐藏评价**的均分（1 位小数）与评价数；文字列表按提交时间倒序全列；本人已有评价时表单区转为回显 + "修改评价"；无资格者**表单照常可见可提交**（服务端拒绝后展示业务可读理由——S2-10 负向演示路径必需，防刷评门槛在服务端而非前端隐藏）。

### 3.3 运营隐藏（S2-12 后半）

运营档（admin）在评价列表条目见「隐藏」→ 确认弹窗（可选原因）→ G-EV1：隐藏标志置位 + 留痕（隐藏人/时点/原因）→ 该评价从统计与列表即时排除，数据不物理删除；已隐藏再隐藏 = 幂等成功不重复留痕。〔勘误指向（2026-11-01 修复轮 1）：原因输入由 hifi §1 G-EV1 定稿裁决为**不做**（confirm 固定文案），见 hifi §10 勘误 E2。〕

## 4. 决策点（Q1~Q8 + D1，全文与备选业务影响）

| 编号 | 问题 | 方案 A（建议） | 方案 B / C（不采理由） | 业务影响 |
| --- | --- | --- | --- | --- |
| **Q1** | 评价域落位 | **A**：delivery-service 内新增评价子域（迁移 V4 + /api/v1/evaluations；资格判定同库 EXISTS 零跨服务；沿 3.7.3"订单落 contract-service 同库子域"先例） | B：catalog-service（展示同域，但需新增 catalog→delivery 内部调用通道〔delivery 现无 internal 角色〕+ 跨服务一致性面，增量更重）；C：新建 evaluation-service（新增服务/端口/库/镜像/部署，超预算超范围） | A 以最小增量兑现 B5-1 防刷评；B/C 引入新依赖面或新部署面 |
| **Q2** | 资格判定口径 | **A**：`EXISTS(delivery WHERE requester_no=本人 ∧ product_id=产品 ∧ result='SUCCESS')`（B5-1"已完成有效交付"直译；delivery 落行即 SUCCESS） | B：按订单完成判定（contract status=COMPLETED）——不符 B5-1 明文（规格以"交付"为准，订单完成≠已交付，防刷评语义弱化） | A 与规格语义逐字对齐；B 需规格变更才能采 |
| **Q3** | 评价交互与展示最小口径 | **A**：详情页内嵌评价区；1~5 分 el-rate + 可选文字；列表时间倒序全列不分页（演示量级）；本人回显修改 | B：独立评价页 + 分页 + 排序（超最小实现，B5-3 明文最小口径） | A 一屏内闭环 S2-8~12 走查；B 增加页面与导航成本 |
| **Q4** | 权限点选型 | **A**：零新权限点——读/提交/更新/mine 复用 `delivery.read`（requester/provider/admin），隐藏复用 `delivery.governance`（仅 admin）；防刷评由服务端资格硬校验承担 | B：新增 `delivery.evaluate` 等权限点（语义精确但新增登记面与前后端映射；权限点=角色粗门槛，业务收益低） | A 沿"权限点=角色门槛、业务校验=规格负向保证"既有模式（3.7.3 同款论证） |
| **Q5** | 运营隐藏入口 | **A**：详情页评价列表条目内嵌「隐藏」按钮（仅运营档；确认弹窗 + 可选原因；即时排除 + 留痕） | B：独立运营治理页面（侵入 3.7.7 运营管理界面范围，超本卡） | A 零新页面兑现 S2-12；B 与 3.7.7 范围冲突 |
| **Q6** | 门户文案锚修正 | **A**：`PORTAL_BROWSE_DESC` 终值"浏览平台已上架的数据产品；对已完成有效交付的产品可提交评价。"（评价开放 + 订购补正一并落地；spec 锚同步） | B：只改评价半句、保留"订购…后续版本开放"（与 3.7.3 实际交付相悖，文案失实） | A 使门户文案与平台实际能力一致；B 留失实陈述 |
| **Q7** | 剧本修订与验收范围 | **A**：C-7.1 转 V1.3——S2-8~12 界面入口与操作路径按实测更新；S2-1~7、13 零改动；业务判定一字不改，PO 批准 | B：S2-8~12 后端验证不做界面（违背剧本界面入口行既有裁决"归 3.7.4"与界面类交付定义） | A 兑现界面类可演示定义；B 降级验收面 |
| **Q8** | 幂等与并发边界 | **A**：**upsert 语义天然幂等**——一人一评下重复提交即更新；并发双击由 uk 唯一索引兜底（DuplicateKey 分支转更新，不抛错）；隐藏动作幂等（已隐藏再隐藏成功且不重复留痕）；**零新增幂等组件接入**（delivery 既有幂等为业务键自管理模式，不为天然幂等的 upsert 引入 `@Idempotent`/Redis 新接入面） | B：引入 `@Idempotent` 组件（对天然幂等语义属冗余，新增接入核验与配置成本） | A 最小实现且语义完整；B 冗余组件面 |
| **D1** | 体量/拆分 | **不拆分**：预估 1800~2600 行，1 个编码会话（WBS 行 316 = 1 人日）；资格判定+提交更新+统计展示+运营隐藏为同一条 S2-8~12 走查链，拆则断裂；沿 6 个界面包先例豁免 400 行上限（登记制） | B：拆分（产生"有端点无界面/有界面无资格校验"中间态） | 超限登记沿先例，编排师在 D1 表态 |

## 5. 体量预估

- 有效变更 **1800~2600 行**（分解见任务卡 §五）；lofi/hifi/原型/任务卡 4 文档约 900~1100 行（立卡段）；
- 1 个编码会话；零新依赖 / 服务 / 端口 / 库 / 镜像；新增迁移 1 件（两表）；网关 1 路由 + vite 1 键；
- 测试增量：前端 +30~45 例、delivery 后端 +18~28 例、网关路由测试随例。
