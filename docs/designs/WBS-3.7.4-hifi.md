# WBS-3.7.4 高保真设计：产品评价系统（V0.9 待确认，2026-11-01）

> 承 `WBS-3.7.4-lofi.md` V0.9；确认后同批转 V1.0（= 编码契约即时生效）。规格派生：C-7.1-7.2 V1.0 B5 / B6 / §6-3。
> 勘误编号自 **E1** 起（§10）；实现与本文不一致 = 打回项。

## 1. 端点契约表（5 个，全部 `/api/v1/evaluations` 前缀，ADR-005 统一响应）

| # | 方法与路径 | 权限点（复用） | 请求 | 响应 data | 错误码 | 说明 |
| --- | --- | --- | --- | --- | --- | --- |
| W-EV1 | `POST /api/v1/evaluations` | `delivery.read` | `{ productId, score, comment?, requestId }`（score 必填 int；comment ≤500 可选；requestId 客户端生成 UUID） | `{ reviewId, productId, score, comment, createdAt, updated: 首次false/更新true }` | 1011C0005（400）/ 1011C0006（403）/ 1011C0007（404 同形）/ 1000C0001（参数通用） | 提交与更新同端点（upsert，Q8-A）；服务端依次校验评分→资格→upsert |
| R-EV1 | `GET /api/v1/evaluations/products/{productId}/stats` | `delivery.read` | 路径参数 productId | `{ productId, averageScore: number\|null, reviewCount: number }` | 1000C0001（productId 非法） | **仅计未隐藏评价**；averageScore 1 位小数（HALF_UP）；无评价 = null + 0 |
| R-EV2 | `GET /api/v1/evaluations/products/{productId}` | `delivery.read` | 路径参数 productId | `{ productId, items: [{ reviewId, requesterNo, score, comment, createdAt, hidden }] }`（hidden 恒 false——隐藏项不出列） | 1000C0001 | 时间倒序全列不分页（Q3-A 最小口径，演示量级）；评价人展示 = requesterNo 原样（沿订单/交付读面同口径，零新增 subject 依赖） |
| R-EV3 | `GET /api/v1/evaluations/mine?productId=` | `delivery.read` | query productId | 本人评价或 `null`（未评价时 data=null，200） | 1000C0001 | 本人数据无枚举面，不走 404 同形；前端回显"修改评价"用 |
| G-EV1 | `POST /api/v1/evaluations/{id}/hide` | `delivery.governance`（仅 admin） | 路径参数 id；body 可省略 | `{ reviewId, hidden: true }` | 1011C0007（404 目标不存在） | 隐藏留痕（隐藏人/时点）不物理删除；**已隐藏再隐藏 = 200 幂等成功且不重复留痕**（Q8-A）；原因输入不做（N3 最小，confirm 弹窗固定文案） |

> 非 admin 调 G-EV1：权限层 403（既有权限组件口径）；productId/id 类型非法：1000C0001（分页与参数通用码沿 common，3.7.3 勘误 E6 同口径）。

## 2. 表结构（迁移 V4，delivery-service，`V4__create_product_review_tables.sql`）

```sql
CREATE TABLE product_review (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    product_id   BIGINT       NOT NULL COMMENT '产品 id（沿 delivery.product_id 快照口径）',
    requester_no VARCHAR(32)  NOT NULL COMMENT '评价人（需方主体编号快照）',
    score        TINYINT      NOT NULL COMMENT '评分 1~5（服务端校验，B5-1）',
    comment      VARCHAR(500) NULL     COMMENT '可选文字评价',
    hidden       TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '0=有效 1=已隐藏（留痕不物理删除，B5-4）',
    hidden_by    VARCHAR(32)  NULL     COMMENT '隐藏人（运营主体编号）',
    hidden_at    DATETIME     NULL,
    created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '首次提交时点（留痕可溯 B5-1）',
    updated_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_review_once (product_id, requester_no),
    KEY idx_product_visible (product_id, hidden, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '产品评价（一人一产品一评；隐藏留痕不删除）';

CREATE TABLE product_review_action_log (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    review_id    BIGINT       NOT NULL,
    product_id   BIGINT       NOT NULL,
    requester_no VARCHAR(32)  NOT NULL,
    action       VARCHAR(16)  NOT NULL COMMENT 'CREATE / UPDATE / HIDE',
    actor_no     VARCHAR(32)  NOT NULL COMMENT '动作人（评价人 / 运营）',
    created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_review (review_id, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '评价域动作留痕（提交/更新/隐藏全程）';
```

- 资格查询走既有 `delivery` 表（**零结构改动**）：`SELECT EXISTS(SELECT 1 FROM delivery WHERE requester_no=? AND product_id=? AND result='SUCCESS')`——新增只读仓储方法，既有索引 `idx_requester(requester_no, created_at)` 前缀可覆盖（演示量级不加新索引，方法注释说明）；
- 更新评价时 `updated_at` 随行变更，`created_at` 不变（首次提交时点留痕语义）；隐藏不更新 `updated_at`（隐藏信息由 hidden_by/hidden_at + 留痕表承载）。

## 3. 提交/更新事务与并发（upsert 语义，Q8-A）

`ReviewCommandService.submit`（`@Transactional`）：
1. 参数校验：score ∈ [1,5] 且为整数（非法 → 1011C0005，400）；comment trim 后 ≤500（超长 → 1000C0001）；productId > 0（非法 → 1000C0001）；
2. 资格校验：EXISTS 交付记录（无 → 1011C0006，403，文案"完成对该产品的有效交付后方可评价"——业务可读，不暴露内部实现）；
3. upsert：按 uk 查本人既有评价——无 → INSERT + CREATE 留痕；有 → UPDATE（score/comment）+ UPDATE 留痕（**评价总数不变**）；
4. 并发兜底：INSERT 撞 `uk_review_once`（DuplicateKeyException）→ 捕获后转 UPDATE 分支（同事务内重查更新，不向客户端抛错）；
5. request/幂等：重复提交天然 = 更新（B5-2），**不引入 @Idempotent 组件**（delivery 既有幂等为业务键自管理模式，Q8-A）。

`ReviewGovernanceService.hide`（`@Transactional`）：目标存在且未隐藏 → hidden=1 + hidden_by/hidden_at + HIDE 留痕；目标不存在 → 1011C0007（404）；已隐藏 → 直接返回成功（幂等，不追加留痕）。

## 4. 错误码（1011 段续用，规格 §6-3 预留；C0001~C0004 已由 3.7.3 订单域占用）

| 码 | HTTP | 业务语义 | 文案（业务可读） |
| --- | --- | --- | --- |
| 1011C0005 | 400 | 评分值非法 | 评分需为 1~5 的整数 |
| 1011C0006 | 403 | 无有效交付资格（防刷评） | 完成对该产品的有效交付后方可评价 |
| 1011C0007 | 404 | 评价不存在或非本人/目标不存在（同形防枚举，3.7.3 C0002 同口径） | 评价不存在或不可操作 |

- 登记位置：`ReviewErrorCodes`（delivery-service domain/review/）+ `ContractExceptionHandler` 同款异常处理器模式落 delivery 侧（新增 `ReviewExceptionHandler` 或复用 common 全局处理器登记制——编码段按 delivery 既有异常处理结构落位，**语义与本文档表逐字一致**）；
- 前端同名词表收口 `constants/review.ts`（沿 `constants/order.ts:33` 模式），透传断言进 spec。

## 5. 前端模块与界面说明书（评价区，PortalProductDetailView.vue 底部新区块）

### 5.1 模块

| 件 | 说明 |
| --- | --- |
| `src/api/review.ts` | 评价域唯一 API 出口：`submitEvaluation` / `fetchEvaluationStats` / `fetchProductEvaluations` / `fetchMyEvaluation` / `hideEvaluation`；请求头带 `deliveryRolesHeader()`（普通档 `requester,provider` / 运营档 `admin`，现成函数复用） |
| `src/constants/review.ts` | 文案与常量收口：区块标题、暂无评价、无资格拒绝文案（与后端 C0006 对应）、评分标签、隐藏确认文案、错误码同名词表 |
| `PortalProductDetailView.vue` | 订购区之后新增「产品评价」区块（下 5.2）；零既有区块语义改动 |
| `constants/catalog.ts` | `PORTAL_BROWSE_DESC` 终值："浏览平台已上架的数据产品；对已完成有效交付的产品可提交评价。"（Q6-A） |
| 守卫 | `PortalSourceGuard.spec.ts` 扩展：api/review.ts + constants/review.ts 收口断言（评价端点仅经 api/review.ts、零 /internal/、零治理调用〔hide 除外且限 delivery.governance 语义〕、'admin' 字面量白名单更新）+ `PORTAL_BROWSE_DESC` 锚更新 |

### 5.2 界面说明书（区块内自上而下）

| 区 | 内容与状态 |
| --- | --- |
| 统计条 | "平均评分 X.X · 共 N 条评价"（R-EV1）；无评价 = "暂无评价" |
| 文字评价列表 | 每条：只读星级（el-rate disabled）+ 文字（有则显）+ "评价人 {requesterNo} · {yyyy-MM-dd HH:mm}"；本人条目尾随「修改」文字按钮；运营档每条尾随「隐藏」按钮 |
| 隐藏确认 | ElMessageBox.confirm 固定文案"确认隐藏该评价？隐藏后不再对外展示，操作将留痕。"（无原因输入，N3） |
| 提交/修改表单 | el-rate（必选，未选提交 → 1011C0005 同义前端拦截 + 服务端兜底）+ el-input textarea（可选，maxlength=500 show-word-limit，placeholder"说说你的使用体验（可选）"）+「提交评价」按钮（防抖）；本人已评价 → 表单预填回显、按钮文案「更新评价」 |
| 负向反馈 | 无资格提交被拒 → 区块内业务可读错误展示（C0006 文案"完成对该产品的有效交付后方可评价"，S2-10 演示路径）；非法评分 → "评分需为 1~5 的整数" |
| 空态/加载/错误态 | 沿门户既有 error/empty 视觉模式（不新造组件） |

- 菜单**零增量**（评价区内嵌详情页，菜单维持 10/23）；详情页既有区块（返回链接/meta-head/描述/收藏订阅/订购区）零语义改动；
- 状态感知沿 B6：无推送无轮询，评价区数据 = 提交/隐藏动作后主动刷新（fetch 一次）。

## 6. 动作矩阵与权限（普通档硬约束：零 admin 混入）

| 动作 | 普通档（requester,provider 头） | 运营档（admin 头） | 服务端把关 |
| --- | --- | --- | --- |
| 看统计/列表 | ✓ | ✓ | delivery.read |
| 提交/更新评价 | ✓（业务资格校验兜底） | ✓（同） | delivery.read + 资格 EXISTS + upsert |
| 本人回显 | ✓ | ✓ | delivery.read（data=null 未评价） |
| 隐藏评价 | ✗（按钮不渲染；直调 403） | ✓ | delivery.governance（仅 admin） |

## 7. 实施前置检查（编码段第一动作，逐项实测留痕）

1. 分支 `feat/WBS-3.7.4-产品评价系统` 存在、工作树 clean、自 `1403751` 梢创建；
2. 前端基线复跑：`npm test`（57 文件/730 例）/ `typecheck` / `lint` / `build` 四步 EXIT=0；
3. **delivery-service 测试基线实测**（`mvn test`——基线数未记录，本次实测回填任务卡）+ `checkstyle:check`；
4. contract-service 261 例复跑（零触达面核验，兜意外）+ 全仓 `mvn compile`；
5. 菜单 10/23、menuOrder 最大 23 复核（本卡零增量前提）；
6. 1011 段 C0005 起空位复核（全仓 grep 双查）；
7. `gates-config.json` ConfigSha256 `955bdbba…2ee2` 复核；
8. delivery V1~V3 迁移现状 + delivery 表 `product_id/requester_no/result` 三字段亲读复核（本设计 §2 依据）；
9. 网关 8 路由 / vite 15 键中 evaluations 键**不存在**（预期，防撞名）；
10. delivery-service `application.yml` 角色→权限映射亲读（requester/provider/admin → delivery.read；admin → delivery.governance——Q4-A 前提）；
11. `PortalSourceGuard.spec.ts` 守卫锚与 `PORTAL_BROWSE_DESC` 现值亲读（Q6-A 修正基线）；
12. `SharedMySqlContainer` 单例模式亲读（集成测试挂接方式）。

## 8. 测试锚点规划（测试先行：红相先行，编码段回填实测类名与例数）

| # | 锚 | 用例要点（Given/When/Then 映射） | 落点（规划） |
| --- | --- | --- | --- |
| T1 | B5-1 正向 | A 对 P1（有 SUCCESS 交付）提交 4 分+文字 → 200 + 留痕行（评价人/产品/评分/时点）+ 统计 4.0/1 | `ProductReviewFlowIntegrationTest`（delivery，SharedMySqlContainer 模式） |
| T2 | B5-1 负向 | score=0 与 score=6 提交 → 400 + 1011C0005（S2-9 双腿） | 同上 |
| T3 | B5-2 | A 再次提交 5 分 → 200 updated=true；总数仍 1；均值 5.0（S2-11） | 同上 |
| T4 | B5-1 负向 | C（零交付）对 P1 提交 → 403 + 1011C0006 + 业务可读文案（S2-10 防刷评） | 同上 |
| T5 | B5-3 | 多人有效评价统计 = 未隐藏均值（1 位小数 HALF_UP）+ 列表倒序全列 | 同上 |
| T6 | B5-1 留痕 / mine | R-EV3 回显本人评价；他人 reviewId 直调 mine 不存在场景 → data=null；G-EV1 目标不存在 → 404 C0007 | 同上 |
| T7 | B5-4 | 运营隐藏 → 统计/列表即时排除 + HIDE 留痕行 + 已隐藏再 hide 幂等不重复留痕 + **非 admin 403**（S2-12） | 同上 |
| T8 | B6 观察 | 零推送/轮询入口断言（代码面/依赖面） | 编码段落位（静态断言或评审自查项） |
| T9 | 迁移结构 | V4 两表 + uk_review_once + idx_product_visible 结构锚 | 迁移测试（沿 3.7.3 T12 同型） |
| TF1~TF6 | 前端 | api/review 形态与错误码透传 / 评价区渲染（统计/列表/表单/回显）/ 提交-更新-负向文案 / 隐藏按钮仅运营档 + confirm / 文案锚终值 / 守卫扩展收口 | `api/review.spec.ts` + `PortalProductDetailView.spec.ts`（扩展）+ `PortalHomeView.spec.ts`（锚更新）+ `PortalSourceGuard.spec.ts`（扩展） |

- 红相先行归档：`build-output/w374-red-backend.log` + `w374-red-frontend.log`；
- 网关路由测试：`connector-evaluations` 七门面沿 3.7.3 `GatewayConnectorRoutesIntegrationTest` 模式随例。

## 9. 剧本映射（C-7.1 V1.2 → V1.3 核对修订范围）

| 剧本步骤 | 本卡承载 | 修订内容（V1.3） |
| --- | --- | --- |
| S2-8 | 详情页评价区表单（评分 4 + 文字 → 提交成功） | 界面入口与操作路径实测更新 |
| S2-9 | 同表单提交 0 分 → 前端拦截文案 + 服务端 1011C0005（双保险演示） | 同上 |
| S2-10 | 主体 C 登录 → 同详情页提交 → 业务可读拒绝（C0006） | 同上 |
| S2-11 | 本人"更新评价"（改评分）→ 总数不变 | 同上 |
| S2-12 | 运营档登录 → 详情页评价区「隐藏」→ confirm → 统计/列表即时排除 | 同上 + 隐藏动作落点明示 |
| S2-1~7 / S2-13 | 3.7.3 已交付 | **零改动** |

业务判定一字不改；V1.3 版本行如实记述修订范围；提示 PO 批准。

## 10. 边界、复用声明与勘误预留

- **复用声明**：`delivery.read` / `delivery.governance` 权限点；`deliveryRolesHeader()`；`@RequirePermission` + common 鉴权；`SharedMySqlContainer`；el-rate / el-messagebox；JdbcTemplate；ADR-005 统一响应与 1000C0001 通用参数码；门户 error/empty 视觉模式。**零新依赖**；
- **规格外实现声明**：应为空（本设计全部条目可回溯至 B5/B6 与 Q1~Q8+D1 确认口径）；
- **勘误预留**：E1 起编号；立卡段已自勘两处（初稿 Q8 曾写"沿 @Idempotent"、初稿 §3.2 曾写"无资格者不渲染提交按钮"——分别与 upsert 最小实现、S2-10 负向演示路径冲突，已在本版更正为 Q8-A 现口径，V0.9 为更正后稿）；
- **演示前提依赖**：S2-8/10/12 依赖 C-7.1 附录 A 预置（A/D 对 P1 有效交付、C 零交付、D 违规评价一条）——交付记录沿 C-4.2 + C-5.1 剧本产出，本卡零直改库。
