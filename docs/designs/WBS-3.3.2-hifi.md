# WBS-3.3.2 高保真设计：资源登记模型与服务（hifi）

| 字段 | 内容 |
| --- | --- |
| 版本 | V0.9（草案，随立卡提交；编排师确认后转 V1.0 = 编码契约，实现须逐条一致） |
| 日期 | 2026-09-30 |
| 任务卡 | `docs/tasks/WBS-3.3.2-资源登记模型与服务-2026-09-30.md` |
| lofi | `docs/designs/WBS-3.3.2-lofi.md`（Q1~Q8 + D1 全文） |
| 规格输入 | `docs/specs/C-3.1-2.3-数据目录与资源.md` V1.0 行为 1/2/7 + §6 边界声明 + §7 Q1/Q3/Q8 裁决 |

## 1. 端点契约表（5 端点 + 1 内部端点）

### 1.1 catalog-service 对外端点（端口 8084，统一前缀 `/api/v1`）

| # | 方法与路径 | 权限点 | 请求 | 响应 200 | 主要错误码 |
| --- | --- | --- | --- | --- | --- |
| W1 | `POST /data-spaces/{spaceId}/datasets` | `dataset.register` | 头 `X-Idempotency-Key`（必填）；体：`name`*(1~128)、`type`*枚举、`intro`*(1~512)、`tags`*(1~10 项、每项 1~32)、`declareCategory`*(1~64)、`declareLevel`*枚举(L1~L4)、`declareImportant`*布尔默认 false | `DatasetView`：id、dataNo、spaceId、name、type、intro、tags、declareCategory、declareLevel、declareImportant、status、createdAt | 1007C0001 同名 / 1007C0002 空间状态不可登记 / 1007C0003 重要数据拒收 / 1007C0008 名称校验失败 / 1007C0006 非成员 |
| W2 | `PUT /datasets/{datasetId}` | `dataset.update` | 体：`intro`、`tags`、`declareCategory`、`declareLevel`（均可选但至少一项；declareLevel 仅允许上调） | `DatasetView`（更新后） | 1007C0005 不存在或无权 / 1007C0004 级别只能收紧 / 1007C0007 已注销 / 1007C0006 非本人 |
| W3 | `POST /datasets/{datasetId}/cancellation` | `dataset.cancel` | 体：`confirmCancellation`*布尔必填 true | `CancellationView`：datasetId、dataNo、status、cancelled | 1007C0005 / 1007C0006 / 1007C0007；confirmCancellation ≠ true → 400 通用参数码（沿 confirmDissolve 先例） |
| R1 | `GET /datasets/mine` | `dataset.read` | `spaceId` 可选、`pageNum`/`pageSize`（复用 common-pagination 规范） | `PageResult<DatasetView>`（仅本人资源，createdAt 倒序） | —— |
| R2 | `GET /datasets/{datasetId}` | `dataset.read` | 路径 id | `DatasetView` | 1007C0005 不存在或无权（非本人与不存在同形） |

> `DatasetView` 全部字段均为目录元数据，**不含数据本体**（边界声明/行为 7 规则 5）；tags 以 `List<String>` 出、JSON 数组入。
> 权限点 4 个（`dataset.register/update/cancel/read`）登记入 catalog `application.yml` 角色映射（演示期：提供方主体角色持有四者；admin 角色持有 read——沿 space 双轨先例，角色映射明细随 yml 落盘）。
> 请求体校验失败一律 400 + 通用参数错误码（逐字段 message，沿既有校验先例）；1007 段只承载业务规则拒绝。

### 1.2 space-service 新增内部端点（本卡唯一既有服务改动）

| 方法与路径 | 权限点 | 请求 | 响应 200 | 说明 |
| --- | --- | --- | --- | --- |
| `GET /api/v1/data-spaces/internal/{spaceId}/memberships/{subjectNo}` | `space.internal.read` | 路径两参数 | `MembershipView`：`spaceStatus`（CREATED/ACTIVE/FROZEN/DISSOLVED）、`role`（OWNER/ADMIN/MEMBER/NONE——非成员或成员行终态 = NONE） | 空间不存在 → spaceStatus=NONE 同形表达（防枚举，沿 ADR-016 口径）；最小暴露，不回空间名称/详情 |

> space `application.yml` 角色映射加 1 行：`catalog-internal: space.internal.read`（沿 did-internal/space-internal 先例）；ADR-016 §6 衔接契约补记该端点。
> subject `application.yml` 角色映射加 1 行：`catalog-internal: subject.internal.read`。

## 2. 错误码表（CatalogErrorCodes，1007 段定稿）

| 码值 | 常量 | 场景 | 对应规格规则 |
| --- | --- | --- | --- |
| 1007C0001 | DATASET_NAME_DUPLICATED | 同空间归一化后重名（含注销名锁定命中） | 行为 1 规则 5 / 行为 2 规则 3 |
| 1007C0002 | DATASET_SPACE_STATE_FORBIDDEN | 空间未启用/冻结/解散不可登记 | 行为 1 规则 2 |
| 1007C0003 | DATASET_IMPORTANT_REJECTED | 申报为重要数据，拒收登记 | 行为 1 规则 4（§4.5-3 硬约束） |
| 1007C0004 | DATASET_LEVEL_TIGHTEN_ONLY | 分类级别变更只能收紧（就高） | 行为 2 规则 1 |
| 1007C0005 | DATASET_NOT_FOUND_OR_NO_ACCESS | 资源不存在或无权访问（防枚举同形） | 行为 7 规则 2 |
| 1007C0006 | DATASET_FORBIDDEN | 非登记主体无权操作（越权拒绝） | 行为 2 规则 5 / 行为 7 规则 1 |
| 1007C0007 | DATASET_ALREADY_DELETED | 资源已注销，终态不可再操作 | 行为 2 规则 4 |
| 1007C0008 | DATASET_NAME_INVALID | 名称为空/超长/含控制字符 | 行为 1 规则 5 |

> 码值常量类 + 中文 message；九位错误码体系沿 `common-errorcode`（ADR-005）；不与其他段交叉。

## 3. 表结构与约束（迁移 `V1__create_catalog_tables.sql`，库 `ctds_catalog`，MySQL 8）

### 3.1 `dataset`（资源主表）

| 列 | 类型 | 约束 | 说明 |
| --- | --- | --- | --- |
| id | BIGINT AUTO_INCREMENT | PK | 技术主键 |
| data_no | VARCHAR(32) | NOT NULL, UNIQUE `uk_data_no` | 数据标识：DS+yyyyMMdd+6 位序号 |
| space_id | BIGINT | NOT NULL | 归属空间（逻辑引用，不建外键） |
| owner_subject_no | VARCHAR(32) | NOT NULL | 登记主体（逻辑引用 subject） |
| name | VARCHAR(128) | NOT NULL | 原始名称 |
| normalized_name | VARCHAR(128) | NOT NULL | 归一化名（trim+去控制字符+折叠空白） |
| type | VARCHAR(16) | NOT NULL | DATASET/API/REPORT/MODEL |
| intro | VARCHAR(512) | NOT NULL | 简介 |
| semantic_tags | VARCHAR(512) | NOT NULL | JSON 数组字符串（载体级，词表归 3.3.3） |
| declare_category | VARCHAR(64) | NOT NULL | 分类申报（字符串载体，类目树归 3.3.4） |
| declare_level | VARCHAR(8) | NOT NULL | L1/L2/L3/L4 |
| declare_important | TINYINT(1) | NOT NULL DEFAULT 0 | 重要数据申报（true=已拒收，恒 0 落库） |
| status | VARCHAR(16) | NOT NULL DEFAULT 'ACTIVE' | ACTIVE/DELETED（终态） |
| created_at / updated_at | DATETIME | NOT NULL | 公共字段 |

- 唯一索引：`uk_space_norm_name(space_id, normalized_name)`——活跃与注销行共同参与（注销后同空间同名由 name_lock 继续锁定，双保险沿 3.2.2 口径）；
- 二级索引：`idx_owner(owner_subject_no, status)`、`idx_space_status(space_id, status)`；
- 引擎/字符集/排序规则：InnoDB / utf8mb4 / utf8mb4_0900_ai_ci（判重前提，沿 3.2.2 移交②）。

### 3.2 `dataset_name_lock`（注销名同空间锁定）

| 列 | 类型 | 约束 |
| --- | --- | --- |
| space_id | BIGINT | NOT NULL，复合 PK 之一 |
| normalized_name | VARCHAR(128) | NOT NULL，复合 PK 之一 |
| dataset_id | BIGINT | NOT NULL（溯源） |
| locked_at | DATETIME | NOT NULL |

### 3.3 `dataset_action_log`（统一留痕）

| 列 | 类型 | 约束 |
| --- | --- | --- |
| id | BIGINT AUTO_INCREMENT | PK |
| actor_subject_no | VARCHAR(32) | NOT NULL（DENIED 时 = 越权者） |
| space_id / dataset_id | BIGINT | NOT NULL |
| action | VARCHAR(32) | NOT NULL：REGISTER/UPDATE/CANCEL + DENIED_ 前缀变体 |
| from_value / to_value | VARCHAR(1024) | 可空（UPDATE 时 from→to；沿 space V2 放宽先例） |
| result | VARCHAR(16) | NOT NULL：SUCCESS/DENIED（沿 space ActionResult 语义） |
| reason_code | VARCHAR(16) | 可空（DENIED 时落错误码尾号） |
| created_at | DATETIME | NOT NULL |

### 3.4 `dataset_no_seq`（数据标识序号）

| 列 | 类型 | 约束 |
| --- | --- | --- |
| seq_date | DATE | PK 之一 |
| seq_key | VARCHAR(8) | PK 之一（恒 'DATASET'，预留扩展） |
| seq_value | INT | NOT NULL（原子自增，当日 1 起） |

> 取号在同一登记事务内完成：`INSERT ... ON DUPLICATE KEY UPDATE seq_value = seq_value + 1`（沿 subject nextDailySeq 先例）。

## 4. 状态机与事务

### 4.1 资源状态机

```
ACTIVE ──cancellation(confirmCancellation=true, 两写事务)──▶ DELETED（终态，无出边）
```

- 变更（W2）：仅 ACTIVE 可变更；乐观门槛 `UPDATE ... WHERE id=? AND status='ACTIVE'`，0 行 → 1007C0007；
- 注销（W3）：仅 ACTIVE 可注销；**两写事务** = status→DELETED + INSERT name_lock；name_lock PK 冲突（理论不可达：同空间归一化名唯一索引已挡）→ 事务回滚转 500 通用码（沿 3.2.3 防御性分支先例）；
- DELETED 后再变更/注销 → 1007C0007（终态自环显式门槛，沿 3.2.3 S1 修复先例）。

### 4.2 登记事务顺序（W1）

1. 参数校验（400 通用码）→ 2. 身份与权限点 → 3. SubjectAdmissionClient 三态判定（NOT_ADMITTED/UNAVAILABLE → 统一文案同形）→ 4. SpaceMembershipClient 判定（spaceStatus ≠ ACTIVE → 1007C0002；role = NONE → 1007C0006 权限拒绝留痕）→ 5. declareImportant=true → **1007C0003 拒收 + DENIED 留痕（反向探针固化）** → 6. 归一化 + 同空间判重（1007C0001/1007C0008）→ 7. 取号 + INSERT dataset（SUCCESS 留痕）→ 8. 返回视图。
- 幂等：`@Idempotent(key = "REGISTER:" + spaceId + ":" + ownerNo + ":" + normalizedName)`（重复提交返回首次结果，沿 3.2.3 SpEL 先例）。

### 4.3 变更规则（W2）

- 仅登记主体本人（owner_subject_no == actor），非本人 → 1007C0006 + DENIED 留痕（**含空间管理员**，资源管理权不随空间角色扩展）；
- declareLevel 变更：`newLevel.ordinal() < oldLevel.ordinal()` → 1007C0004 + DENIED 留痕（只能收紧就高）；
- 留痕 from_value=旧申报/简介摘要，to_value=新值（VARCHAR(1024) 容纳）。

### 4.4 引用保护挂点（Q4-A）

```java
public interface ProductReferenceGuard {
    /** 是否存在未注销产品引用；本卡实现恒 false，3.3.5 产品表落地后替换为实体检查。 */
    boolean hasActiveProductReferences(long datasetId);
}
```

- W3 注销前置检查：`hasActiveProductReferences=true` → 拒绝（错误码随 3.3.5 定）——本卡实现恒 false，行为已接线、实体随 3.3.5；
- 任务卡 + 本设计双登记移交义务；C-3.1 剧本 S3 步骤 4 用例随 3.3.5 补测。

## 5. client 契约

| client | 目标 | 超时/失败语义 |
| --- | --- | --- |
| `SubjectAdmissionClient`（catalog 侧新建，沿 did/space 先例） | subject 内部端点 | connectTimeout 2s / readTimeout 3s；任何异常/非 2xx → UNAVAILABLE 统一文案（不冒充未入驻）；响应 200 但 status 非 ADMITTED → NOT_ADMITTED |
| `SpaceMembershipClient`（catalog 侧新建） | space 内部端点 | 同上；spaceStatus=NONE 且 role=NONE → 按"空间不存在"同形处理（登记判 1007C0002，读面判 1007C0005） |

## 6. 测试计划（Testcontainers MySQL 8 实跑，沿 SharedMySqlContainer 先例 + root 建库 GRANT）

| 组 | 用例 | 对应规格验收标准 |
| --- | --- | --- |
| T1 登记正向 | 要素齐备登记成功、data_no 格式（DS+8 位日期+6 位序号）、留痕四要素 | 行为 1 GWT-1 |
| T2 资格防枚举 | 未入驻/不存在主体同形文案；client 不可达=UNAVAILABLE 不冒充 | 行为 1 GWT-2 |
| T3 空间门槛 | 非成员权限拒绝+留痕；CREATED/FROZEN/DISSOLVED 三态拒绝（冻结拒新增） | 行为 1 GWT-3/4 |
| T4 重要数据拒收 | important=true 拒收+留痕；**反向探针**：绕过尝试（缺省/null/大小写/"true"/1）必被拒 | 行为 1 GWT-5 |
| T5 名称与归一化 | 同空间归一化同名拒（含 U+3000 全角空格绕过）；跨空间同名允许；空/超长/控制字符拒 | 行为 1 GWT-6 |
| T6 幂等 | 同键重复提交资源数不变、返回首次结果 | 行为 1 GWT-7 |
| T7 变更 | 本人变更成功留痕 from→to；级别上调过/下调拒+留痕 | 行为 2 GWT-1/2 |
| T8 注销 | confirmCancellation 缺失 400；两写落库断言；名称锁定（注销后同空间同名拒） | 行为 2 GWT-3 |
| T9 终态与越权 | DELETED 再变更/注销拒；空间管理员/其他成员越权矩阵逐动作拒+DENIED 留痕 | 行为 2 GWT-5、行为 7 |
| T10 读面防枚举 | 非本人详情=与不存在同形；mine 列表仅本人；分页字段齐备 | 行为 7 GWT-2/3 |
| T11 迁移探针 | 3 表列齐/注释/唯一索引（uk_data_no/uk_space_norm_name/name_lock PK）反向探针 | 设计契约 |
| T12 client 单测 | 双 client 超时/非 2xx/畸形响应 → UNAVAILABLE 兜底（沿 3.2.3 client 测试先例） | 设计契约 |
| T13 枚举封闭 | DatasetType/DeclareLevel/状态枚举值域封闭性（沿 SpaceDomainEnumsTest 先例） | 设计契约 |
| T14 space 回归 | space-service 既有测试全量回归（本卡改动了 space） | 既有保护 |

> 核心覆盖目标：服务包测试先行，覆盖率达标归 3.3.7；本卡测试数预估 60+（沿 3.2.3 同档）。

## 7. 数据分级落级（Q8-A 执行）

| 数据 | CAT | 级别 | 理由 |
| --- | --- | --- | --- |
| dataset 主表（资源登记元数据、语义标签、分类分级标签） | CAT-03 | L2 | 目录元数据，不涉本体/个人信息（沿 space 主表 CAT-01 L2 先例） |
| dataset_action_log（留痕） | CAT-06 | L3 | 审计明细，含操作者与动作轨迹（沿审计 L3 先例） |

- 回写：分级规范 §6.1 补 2 行（ctds_catalog.dataset / ctds_catalog.dataset_action_log）；迁移头注同步标注。

## 8. 部署清单（deploy/k8s 追加，沿 3.2.3 先例）

catalog-service：deployment / service / kustomization 入列；数据库 `ctds_catalog` 建库脚本登记（部署文档同步行，沿 ctds_space 先例）；演示期仅本机可达（ADR-016 §2.7）不加网络策略变更。

## 9. 模块骨架

```
services/catalog-service
├─ pom.xml（parent ctds 2.0.0-SNAPSHOT，relativePath ../../pom.xml；依赖：web/validation/jdbc/common×4，无新依赖族）
└─ src/main/java/com/ctds/catalog/
   ├─ CatalogApplication.java
   ├─ interfaces/DatasetController.java（W1/W2/W3/R1/R2 + DatasetView/请求 DTO）
   ├─ application/DatasetCommandService.java（登记/变更/注销编排）+ DatasetQueryService.java
   ├─ domain/Dataset.java + DatasetRepository.java + DatasetType/DeclareLevel/DatasetStatus 枚举
   │        + ProductReferenceGuard.java（Q4-A 挂点）+ actionlog 端口
   └─ infrastructure/JdbcDatasetRepository.java + DatasetActionLogRepository.java
            + SubjectAdmissionClient.java + SpaceMembershipClient.java + CatalogErrorCodes.java
```

- 根 `pom.xml` 追加 `<module>services/catalog-service</module>`；
- space-service 追加 `interfaces/InternalMembershipController.java`（+ MembershipView）。

## 10. 勘误与登记备忘（供评审与下游）

1. 语义标签/分类申报 = 字符串载体级校验，词表与类目树归 3.3.3/3.3.4（Q7-A），届时如改关联表结构走变更流程；
2. 引用保护挂点移交 3.3.5（Q4-A）：该包须替换 `ProductReferenceGuard` 实现 + 补测剧本 S3 步骤 4；
3. deploy 数据库建库脚本与部署文档的同步动作归本卡交付⑧；
4. 若 3.3.5 产品表需引用 dataset（必然），`uk_space_norm_name` 含注销行的语义对产品可见性无影响（产品侧另有状态机）。
