# WBS-3.3.2 低保真设计：资源登记模型与服务（lofi）

| 字段 | 内容 |
| --- | --- |
| 版本 | **V1.0（已确认 = 方向定稿**——2026-09-30 20:4x 编排师确认 Q1~Q8 均采建议 A + D1 不拆分豁免；与 hifi 同批签署） |
| 日期 | 2026-09-30 |
| 任务卡 | `docs/tasks/WBS-3.3.2-资源登记模型与服务-2026-09-30.md` |
| 规格输入 | `docs/specs/C-3.1-2.3-数据目录与资源.md` V1.0 行为 1（登记）/ 行为 2（变更与注销）/ 行为 7（安全面资源侧）+ §6 边界声明 + §7 已裁决 Q1/Q3/Q8 |
| 上游交付 | 3.2.2 空间数据模型（六表载体先例）/ 3.2.3 空间生命周期服务（服务实现先例）/ 3.3.1 规格与剧本（唯一实现输入） |

## 1. 做什么 / 不做什么

**做**（= 任务卡交付物 ①~⑫）：

1. 新建 `services/catalog-service` 域级服务（端口 8084 / 库 `ctds_catalog`），目录与资源域 3.3.2~3.3.5 共用宿主；
2. 资源库 4 表迁移 + domain 模型类 + DB 约束兜底；
3. 资源登记 / 变更 / 注销 3 个写面端点 + 本人列表 / 本人详情 2 个读面端点（共 5 端点）+ 应用服务全规则落地；
4. space-service 加 1 个 internal 成员判定端点 + catalog 侧 2 个 client；
5. `CatalogErrorCodes` 1007 段码值定稿 + 部署入列 + 集成测试 + 分级落级回写。

**不做**（归下游，任务卡纪律声明同）：

- 产品表 / 封装 / 上下架 / 定价 → 3.3.5（注销"引用保护"实体检查随之）；
- 目录检索 / 订阅收藏 / 类目树 / 排序 → 3.3.4；语义标签受控词表 / 来源系统对接 → 3.3.3；
- 界面（检索门户 / 资源登记页 / 产品上架页）→ 3.3.6；
- 覆盖率达标 / 万级目录数据集 / 性能基准 → 3.3.7；
- 平台运营方治理查看端点 → 3.3.5（规格行为 7 规则 3"机制复用形态随 3.3.2~3.3.5 落定"）。

## 2. 方向要点（设计骨架）

### 2.1 服务与库

| 项 | 取值 | 理由 |
| --- | --- | --- |
| 服务模块 | `services/catalog-service` | 目录与资源域为 WBS 已规划独立域（3.3.2~3.3.7），一域一服务（沿 space-service 先例）；3.3.3/3.3.4/3.3.5 在同模块上加功能，避免域内碎服务跨库引用产品-资源关系 |
| 端口 | 8084 | 8080~8083 已占（subject/kms/did/space），顺延 |
| 库 | `ctds_catalog` | 每服务独立库惯例（沿 ctds_space） |
| 资源名 | `datasets`（对外）/ `dataset`（表与实体） | 规格 §7 Q8 裁决；术语统一"数据资源"↔Dataset，不引入第二套概念（边界声明 6） |

### 2.2 表清单（4 表；表数口径于评审循环 1 勘误为 4，含序号表）

| 表 | 承载 | 硬约束（DB 兜底） |
| --- | --- | --- |
| `dataset` | 资源主表：id / data_no（数据标识）/ space_id / owner_subject_no / name / normalized_name / type / intro / semantic_tags / declare_category / declare_level / declare_important / status / created_at / updated_at | `uk_data_no`（全平台唯一）；`uk_space_norm_name`（同空间归一化名唯一，注销行参与锁定语义） |
| `dataset_name_lock` | 注销名同空间锁定（space_id + normalized_name） | 锁定表复合 PK（注销后同空间不可复用，沿 space_name_lock 先例） |
| `dataset_action_log` | 统一留痕：actor/space_id/dataset_id/action/from_value/to_value/result/reason_code/created_at | ——（留痕结构断言探针） |
| `dataset_no_seq` | 数据标识当日序号（seq_date + seq_key 复合 PK，seq_value 原子自增、当日重置） | 复合 PK（seq_date + seq_key） |

> 无幂等表（`common/idempotency` 组件）、无产品表（3.3.5）、无标签关联表（词表归 3.3.3，本卡 JSON 字符串载体）。
> data_no 业务编号 vs id 技术主键分离（沿 subjectNo / 技术 id 先例）。

### 2.3 资格与成员判定通道（两 client，零直连对方库）

```
catalog-service                    被调方
├─ SubjectAdmissionClient          → GET /api/v1/subject/internal/subjects/{subjectNo}/admission
│  （沿 did/space 先例，subject yml 已有 space-internal 授权行，catalog 复用加 1 行）
└─ SpaceMembershipClient           → GET /api/v1/data-spaces/internal/{spaceId}/memberships/{subjectNo}
                                     【本卡新建】space-service InternalMembershipController
                                     响应最小暴露：{ spaceStatus, role | NONE }
                                     space yml 加 1 行：catalog-internal: space.internal.read
```

- 三态判定：ADMITTED / NOT_ADMITTED（不存在与未入驻同形——防枚举）/ UNAVAILABLE（被调方不可达，统一文案不冒充前两者）；
- space 端点"最小暴露"沿 InternalAdmissionController 先例：只回判定所需两字段语义，不回空间详情/成员列表（避免内部面变业务面）。

### 2.4 端点清单（5 端点）

| # | 端点 | 说明 |
| --- | --- | --- |
| W1 | `POST /api/v1/data-spaces/{spaceId}/datasets` | 登记（幂等头 X-Idempotency-Key；请求体 = 名称/类型/简介/标签/分类/级别/important） |
| W2 | `PUT /api/v1/datasets/{datasetId}` | 变更（可变更字段 = 简介/标签/分类/级别；级别只能上调收紧） |
| W3 | `POST /api/v1/datasets/{datasetId}/cancellation` | 注销（请求体 confirmCancellation=true 二次确认 API 强表达；两写事务 + name_lock） |
| R1 | `GET /api/v1/datasets/mine` | 本人资源分页列表（spaceId 可选过滤；复用 common-pagination） |
| R2 | `GET /api/v1/datasets/{datasetId}` | 本人资源详情（非本人 → 与"不存在"同形拒绝，防枚举） |

> 命名沿 ADR-005：`datasets` 挂空间路径先例、动词子资源（cancellation 沿 dissolution/freezing 先例）。

### 2.5 关键规则落点

| 规格规则 | 落点 |
| --- | --- |
| 重要数据拒收（§4.5-3 硬约束） | 登记应用服务前置校验：declare_important=true → 拒绝 + DENIED 留痕；反向探针固化（含绕过尝试） |
| 冻结拒新增 | spaceStatus ≠ ACTIVE 一律拒绝（业务码表意"空间状态不可登记"） |
| 级别只能收紧 | 变更应用服务比较新旧 level 序：下调拒绝 + 留痕 |
| 注销两写事务 | UPDATE status=DELETED（乐观门槛 WHERE status='ACTIVE'）+ INSERT name_lock，同事务；confirmCancellation 缺失 400 |
| 引用保护 | `ProductReferenceGuard` 接口本卡恒放行实现；实体检查 + 补测随 3.3.5（任务卡移交登记） |
| 越权拒绝 + DENIED 留痕 | owner 判定 = owner_subject_no == 当前主体；非本人（含空间管理员）一律拒绝 + 留痕（资源管理权不随空间角色扩展） |
| 数据标识 | `DS + yyyyMMdd + 6 位当日序号`；序号表 `dataset_no_seq(date, seq)` 原子自增（沿 subject nextDailySeq） |
| 归一化 | 沿 3.2.2 移交实现：空白集显式含 Unicode（U+3000）、控制字符去除、首尾 trim；判重前提 utf8mb4_0900_ai_ci |

## 3. 待确认问题（Q 清单全文）

| 编号 | 问题 | 建议口径 A | 备选与业务影响 |
| --- | --- | --- | --- |
| **Q1** | 服务落点 | 新建 `catalog-service` 域级服务（8084 / ctds_catalog），3.3.2~3.3.5 共用宿主 | B：`dataset-service` 只管资源、产品另建服务——产品-资源引用跨服务跨库，关系查询与事务复杂化，且域内三服务违背"一域一服务"粒度惯例 |
| **Q2** | 空间状态+成员判定通道 | space 新增 internal 端点（最小暴露 spaceStatus+role\|NONE）+ catalog client + space yml 1 行授权 | B：catalog 直连 space 库——违反微服务独立库纪律（3.2.2 卡明令）；C：复用 space 业务端点——业务端点按调用者身份过滤且带分页/权限点语义，服务间调用须伪装终端用户身份，语义脏且防枚举面失控 |
| **Q3** | 数据标识编号规则 | `DS + yyyyMMdd + 6 位当日序号`（序号表原子自增当日重置） | B：UUID——全平台唯一但不可读，演示与剧本断言不友好；C：全局连续序号（无日期段）——暴露登记量且跨日期单调增长需全局锁；A 沿 subject 已交付先例，风险最低 |
| **Q4** | 注销引用保护的本卡落地形态 | 本卡留 `ProductReferenceGuard` 接口恒放行 + 移交 3.3.5 实体兑现补测 | B：本卡建占位产品表——侵入 3.3.5 模型所有权，占位表结构与该包设计可能不一致返工；C：本卡不做任何引用保护痕迹——3.3.5 改造注销服务时无挂点，回归面大。剧本执行顺序（3.3.1 卡 §三）已内含"引用保护验证在产品存在之后"语义，A 与该顺序自洽 |
| **Q5** | 读面边界 | 本人列表 + 本人详情 2 端点（最小管理视图，支撑 C-3.1 剧本 S1/S2 验证） | B：本卡含运营方治理查看端点——规格行为 7 规则 3"机制复用形态随 3.3.2~3.3.5 落定"，归 3.3.5 与产品治理查看同源复用更省；C：不做读面——剧本"登记后可查"无法验证，管理动作成盲写 |
| **Q6** | 重要数据申报字段形态 | 分类（字符串载体）+ 级别 L1~L4 枚举 + important 布尔默认 false；true 一律拒收 | B：级别枚举含 IMPORTANT 值——重要数据与敏感级别是两个维度（重要数据可含任何级别，规范 CAT-07 分述），合并表达会误拒"高级别但非重要数据"的合法资源；A 与分级规范语义一致 |
| **Q7** | 语义标签校验深度 | 本卡载体级：必填、非空、数量上限、单标签长度上限、去重；词表成员校验归 3.3.3 | B：本卡内置小型词表——词表集合是 3.3.3 的规格未定义项，本卡自定即越界（红线 2）；C：标签完全自由不校验——超范围内容（超长/控制字符）落库无防线 |
| **Q8** | 分级落级回写 | hifi 落级表 + 分级规范 §6.1 补行（同步动作） | B：不回写——规范 §6.1 失同步，与 3.2.2 已立先例断裂 |
| **D1** | 体量 / 是否拆分 | 不拆分（~1600~2200 行，超 400 行指引豁免：单服务单目标原子交付，沿 3.2.2/3.2.3 先例；拆分产生"表无端点可验、端点无表可依"中间态） | —— |

## 4. 风险与依赖

| 风险 / 依赖 | 处置 |
| --- | --- |
| space internal 端点为本卡对既有服务的唯一改动 | 最小暴露设计 + yml 1 行授权 + space 既有集成测试回归必跑（沿 3.2.3 subject 回归 144/144 先例） |
| Testcontainers 建库/授权（3.2.2 教训：应用用户无 CREATE 权限） | 沿 SharedMySqlContainer 先例 + root 建库 GRANT（已第 4 份变体，沉淀观察项不再扩散） |
| catalog 双 client 不可达语义 | 统一 UNAVAILABLE 文案，不冒充"未入驻/非成员"（防枚举同形，行为 1 规则 1 口径延伸） |
| 上游端口/库冲突 | 8084 / ctds_catalog 已核查空闲（8080~8083 占用、ctds_space/kms 等既有） |

## 5. 剧本衔接

- C-3.1 剧本三幕（登记门槛与要素 / 变更与留痕 / 注销与引用保护）接口步骤随本卡交付实测（关闭条件④）；**S3 步骤 4 引用保护**用例本卡标记 SKIP-待 3.3.5，与剧本维护说明口径同步（修订经 PO 批准留痕——若编排师认为剧本文字需先行标注，验收时一并提出）。
- 界面入口仍占位（归 3.3.6，规格 §7 Q9 裁决不变）。
