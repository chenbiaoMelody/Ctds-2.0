# WBS-3.6.4 高保真设计：连接器生命周期管理（V0.9，2026-10-26）

| 字段 | 内容 |
| --- | --- |
| 版本 | **V0.9（⏳ 待编排师一次确认）**——确认后转 V1.0 = 编码契约；与 lofi 一并提交、一次确认（章程 2.6.3） |
| 契约来源 | lofi Q1~Q7 建议口径 A + D1 不拆分；规格 `docs/specs/C-6.1-6.3-接入连接器.md` V1.0 行为 9 / 行为 10 + 行为 8 规则 1 后半 + §7 Q5-A |
| 交付物 | ① `services/connector-service` 增量：迁移 `V2__create_connector_lifecycle_tables.sql`（3 表 + 1 列）+ 9 端点（对外 6 + 内部 3）；② **ADR-028 连接器生命周期管理契约**（编码段落盘）；③ 测试（测试先行） |

---

## §1 模块装配契约（Q1-A：零新模块 / 零新依赖）

**不新建模块、不改 `pom.xml`、不改根 `pom.xml` 的 `<modules>`**：全部能力在既有 `services/connector-service`（端口 8088 / 库 `ctds_connector`）内增量落地。

**`src/main/resources/application.yml` 仅改 `ctds.auth.permissions` 三行**（追加权限点，既有权限点与角色零变更）：

```yaml
ctds:
  auth:
    permissions:
      applicant: connector.register,connector.read,connector.heartbeat,connector.upgrade,connector.cancel
      admin: connector.review,connector.read,connector.internal.read,connector.upgrade.confirm,connector.cancel
      connector-internal: connector.internal.read,connector.internal.write
```

**零新增配置项**（无新 `@ConfigurationProperties` 字段；不引阈值 / 开关）。

## §2 库表结构（`V2__create_connector_lifecycle_tables.sql`）

> 3.6.3 的前瞻预留直接兑现：`connector_registration.status` 的 `CANCELLED` 取值、`connector_did_binding.status` 的 `RELEASED` 取值、`released_at` 列、活跃态生成列 `active_name_norm`（`CANCELLED → NULL`）**均不改**（Q3 前瞻已在 3.6.3 落地）。本迁移仅 **+1 列 + 3 新表**。

**`connector_version_change`**（升级登记 + 版本历史链，Q3-A / Q4-A）

| 列 | 类型 | 说明 |
| --- | --- | --- |
| `id` | BIGINT NOT NULL AUTO_INCREMENT | 技术主键 |
| `upgrade_no` | VARCHAR(16) NOT NULL | **UC + 6 位**升级单号（登记即签发） |
| `connector_no` | VARCHAR(16) NOT NULL | 连接器编号（`CN` + 6 位） |
| `subject_no` | VARCHAR(32) NOT NULL | 所属主体快照（提交时属主） |
| `from_version` | VARCHAR(16) NOT NULL | 变更前版本（= 提交时的当前版本） |
| `to_version` | VARCHAR(16) NOT NULL | 目标版本（`主.次.修订`） |
| `compatibility` | VARCHAR(16) NOT NULL | `COMPATIBLE` / `INCOMPATIBLE` |
| `status` | VARCHAR(24) NOT NULL | `PENDING_CONFIRM`（待运营方显式确认）/ `APPLIED`（已生效）/ `VOIDED`（连接器注销时作废） |
| `pending_connector_no` | VARCHAR(16) **GENERATED ALWAYS AS (IF(`status` = 'PENDING_CONFIRM', `connector_no`, NULL)) STORED** | 待确认唯一键（**同连接器至多 1 条待确认**；`APPLIED` / `VOIDED` 后置 NULL 自动释放槽位） |
| `requested_by` | VARCHAR(32) NOT NULL | 提交人（属主主体编号） |
| `confirmed_by` | VARCHAR(32) NULL | 显式确认人（运营方；兼容升级与未确认为 NULL） |
| `applied_at` | DATETIME NULL | 生效时间（`APPLIED` 时写入） |
| `created_at` | DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP | 提交时间 |

索引：`UNIQUE uk_upgrade_no(upgrade_no)` / **`UNIQUE uk_pending_connector(pending_connector_no)`** / `KEY idx_version_connector(connector_no, created_at)` / `KEY idx_version_status(status, created_at)`。
**版本历史链读取口径**：该表按 `connector_no` **时间倒序全量**；链起点为最早一条的 `from_version`，无任何变更行时链 = 当前版本单点（不改 3.6.3 数据模型、不双写）。

**`connector_job_occupancy`**（在途交付作业占用台账，Q2-A）

| 列 | 类型 | 说明 |
| --- | --- | --- |
| `id` | BIGINT NOT NULL AUTO_INCREMENT | 技术主键 |
| `connector_no` | VARCHAR(16) NOT NULL | 连接器编号 |
| `job_ref` | VARCHAR(64) NOT NULL | 作业引用（使用方提供，业务可读字符串，**不含秘密材料**） |
| `status` | VARCHAR(16) NOT NULL | `OCCUPY`（在途）/ `RELEASED`（已释放） |
| `occupied_at` | DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP | 最近登记时间 |
| `released_at` | DATETIME NULL | 释放时间 |

索引：`UNIQUE uk_connector_job(connector_no, job_ref)` / `KEY idx_occupancy_status(connector_no, status)`。
注销前置检查语句：`SELECT COUNT(*) FROM connector_job_occupancy WHERE connector_no = ? AND status = 'OCCUPY'`。

**`connector_upgrade_no_seq`**（单行取号表，沿 `connector_no_seq` 先例）：

```sql
CREATE TABLE connector_upgrade_no_seq (
    id BIGINT NOT NULL, next_no BIGINT NOT NULL, PRIMARY KEY (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '连接器升级单号全局序号（1 行原子取号）';
INSERT INTO connector_upgrade_no_seq (id, next_no) VALUES (1, 1);
```

取号：`UPDATE connector_upgrade_no_seq SET next_no = LAST_INSERT_ID(next_no + 1) WHERE id = 1` → `SELECT LAST_INSERT_ID()` → `String.format("UC%06d", n)`。

**既有表 +1 列**：

```sql
ALTER TABLE connector_registration
    ADD COLUMN cancelled_at DATETIME NULL COMMENT '注销时间（终态时间；WBS-3.6.4）' AFTER last_heartbeat_at;
```

**表注释与列注释全覆盖**（沿 ADR-009 与 3.6.3 评审后口径）；跨库引用只逻辑引用不建外键。

## §3 包结构与类清单

```
com.ctds.connector
├── interfaces/
│   ├── ConnectorUpgradeController.java          # 新：#1 升级登记 / #2 版本链 / #3 待确认队列 / #4 显式确认
│   ├── ConnectorLifecycleController.java        # 新：#5 注销 / #6 生命周期聚合读
│   ├── InternalConnectorOccupancyController.java# 新：#7 占用登记 / #8 占用释放 / #9 占用清单（内部面）
│   └── dto/ConnectorLifecycleViews.java         # 新：UpgradeRequest / OccupancyRequest / VersionChangeView
│                                                #     / OccupancyView / LifecycleView
├── application/
│   ├── ConnectorUpgradeService.java             # 新：升级登记（兼容/不兼容分支）/ 待确认队列 / 显式确认 / 版本链
│   ├── ConnectorCancellationService.java        # 新：注销前置检查（属主或运营方 / 状态 / 在途占用）+ 终态事务
│   ├── ConnectorLifecycleQueryService.java      # 新：生命周期聚合读（档案 + 版本链 + 待确认 + 绑定 + 占用）
│   └── ConnectorOccupancyService.java           # 新：内部占用登记 / 释放 / 清单（幂等）
├── domain/
│   ├── ConnectorVersionChange.java              # 新：record（11 字段）
│   ├── VersionChangeStatus.java                 # 新：PENDING_CONFIRM / APPLIED / VOIDED
│   ├── Compatibility.java                       # 新：COMPATIBLE / INCOMPATIBLE
│   ├── ConnectorJobOccupancy.java               # 新：record
│   ├── OccupancyStatus.java                     # 新：OCCUPY / RELEASED
│   ├── ConnectorVersionChangeRepository.java    # 新端口：insert / apply / confirm / void / find / count
│   ├── ConnectorOccupancyRepository.java        # 新端口：upsertOccupy / release / find / countInProgress / list
│   ├── ConnectorErrorCodes.java                 # 改：+1010C0008 / +1010C0009 与两文案
│   ├── ConnectorRegistration.java               # 改：+1 字段 cancelledAt
│   └── ConnectorRepository.java                 # 改：+updateVersion(connectorNo, from, to) / +cancel(connectorNo, now)
├── audit/
│   └── ConnectorAuditActions.java               # 改：+6 动作常量（见 §7）
└── infrastructure/
    ├── JdbcConnectorVersionChangeRepository.java# 新：手写 SQL（沿 3.6.3 仓储形态）
    ├── JdbcConnectorOccupancyRepository.java    # 新：ON DUPLICATE KEY UPDATE 幂等 upsert
    ├── JdbcConnectorRepository.java             # 改：读映射 +cancelled_at；+updateVersion / +cancel（带状态与版本乐观门槛）
    └── （SubjectAdmissionClient / DidStatusClient 零改动——本卡零出站跨域调用）
```

**改 3.6.3 既有文件清点（5 个，均为增量，零端点语义改动）**：`ConnectorErrorCodes`（+2 码 +2 文案）、`ConnectorRegistration`（+1 字段）、`ConnectorRepository` / `JdbcConnectorRepository`（+2 方法 + 读映射）、`ConnectorAuditActions`（+6 常量）；另 `application.yml` +3 行权限映射、新增迁移 1 个。**`interfaces/` 下 3.6.3 的 3 个控制器与 12 端点零改动**。

## §4 关键接口签名（record 模型，`interfaces/dto/ConnectorLifecycleViews.java`）

```java
// 请求
record UpgradeRequest(
    @NotBlank @Pattern(regexp = "^\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}$") String toVersion,
    @NotBlank String compatibility) {}          // 仅 COMPATIBLE / INCOMPATIBLE

record OccupancyRequest(@NotBlank @Size(min = 1, max = 64) String jobRef) {}

// 视图
record VersionChangeView(String upgradeNo, String connectorNo, String subjectNo, String fromVersion,
    String toVersion, String compatibility, String status, String requestedBy, String confirmedBy,
    LocalDateTime appliedAt, LocalDateTime createdAt) {}

record OccupancyView(String connectorNo, String jobRef, String status,
    LocalDateTime occupiedAt, LocalDateTime releasedAt) {}

record LifecycleView(String connectorNo, String subjectNo, String connectorName, String connectorType,
    String connectorVersion, String status, boolean online, LocalDateTime lastHeartbeatAt,
    LocalDateTime cancelledAt, String bindingStatus, String did, LocalDateTime boundAt,
    LocalDateTime releasedAt, int inProgressJobs, boolean pendingUpgrade,
    List<VersionChangeView> versionHistory, LocalDateTime updatedAt) {}
```

应用层命令：`UpgradeCommand(connectorNo, subjectNo, toVersion, compatibility)`、`CancellationCommand(connectorNo, callerNo, operator)`；`LifecycleView` 的 `online` **复用 3.6.3 的 `ConnectorQueryService.online(registration)` 口径**（同一派生函数，不复制判定逻辑）。

## §5 端点契约表（9 个：对外 6 + 内部 3）

| # | 方法 | 路径 | 权限 | 请求 / 响应 | 关键语义 |
| --- | --- | --- | --- | --- | --- |
| 1 | POST | `/api/v1/connectors/{connectorNo}/upgrades` | `connector.upgrade` | body=`{toVersion, compatibility}` → `VersionChangeView` | 身份取 `AuthContext.subject()` 并作属主校验（非属主 → `1010C0003` 同码同文）；仅 `REGISTERED`（否则 `1010C0004`）；`toVersion` 格式 → `1010C0001`；`toVersion` = 当前版本 → `1010C0009`；已有 `PENDING_CONFIRM` → `1010C0004`；**`COMPATIBLE`** = 同事务「签发 `UC` + 写 `APPLIED` + 改当前版本 + 留痕 `connector.upgrade.applied`」；**`INCOMPATIBLE`** = 写 `PENDING_CONFIRM`（**当前版本不动**）+ 留痕 `connector.upgrade.requested` |
| 2 | GET | `/api/v1/connectors/{connectorNo}/upgrades` | `connector.read` | `?pageNum&pageSize` → `PageResult<VersionChangeView>` | 属主或 `admin`（否则 `1010C0003`）；**时间倒序**（版本链）；含 `PENDING_CONFIRM` / `APPLIED` / `VOIDED` 全部行 |
| 3 | GET | `/api/v1/connectors/upgrades/pending` | `connector.upgrade.confirm` | `?pageNum&pageSize` → `PageResult<VersionChangeView>` | 运营方待确认队列：固定 `PENDING_CONFIRM`，提交时间**升序**（先到先处置） |
| 4 | POST | `/api/v1/connectors/{connectorNo}/upgrades/{upgradeNo}/confirmation` | `connector.upgrade.confirm` | → `VersionChangeView` | 显式确认：记录须存在且 `connectorNo` 与路径一致（否则 `1010C0003`）、`upgradeNo` 格式 `^UC\d{6}$`（否则 `1010C0001`）、状态 = `PENDING_CONFIRM`（否则 `1010C0004`）；同事务「改当前版本（门槛 `REGISTERED` 且 `connector_version = from_version`）+ 行转 `APPLIED` + `confirmed_by`/`applied_at` + 留痕 `connector.upgrade.confirmed`」 |
| 5 | POST | `/api/v1/connectors/{connectorNo}/cancellation` | `connector.cancel` | 空 body → `LifecycleView` | 属主或 `admin`（否则 `1010C0003`）；状态 ∈ `PENDING_REVIEW`（撤回申请）/ `REGISTERED`（否则 `1010C0004`）；**在途占用 > 0 → `1010C0008`**（拒绝留痕 `connector.cancelled` / `DENIED`）；同事务「`status=CANCELLED` + `cancelled_at` + 绑定 `ACTIVE→RELEASED` + `released_at` + 待确认升级行转 `VOIDED` + 留痕 `connector.cancelled` / `SUCCESS`」 |
| 6 | GET | `/api/v1/connectors/{connectorNo}/lifecycle` | `connector.read` | → `LifecycleView` | 属主或 `admin`（否则 `1010C0003`）；聚合读：档案 + 在线派生 + 版本链（全量倒序，低频操作）+ 待确认标记 + **绑定状态（含 `RELEASED` 与 `releasedAt`，供行为 10-3"绑定记录保留"）+ 在途占用数 + 注销时间**；**零跨域调用**（不查 DID 域） |
| 7 | POST | `/api/v1/connectors/internal/{connectorNo}/occupancies` | `connector.internal.write` | body=`{jobRef}` → `OccupancyView` | 内部面（使用方登记在途作业）：连接器不存在 → `1010C0003`；非 `REGISTERED` → `1010C0004`；同 `connectorNo + jobRef` **幂等**（`ON DUPLICATE KEY UPDATE` 复位为 `OCCUPY`）；留痕 `connector.occupancy.register` |
| 8 | DELETE | `/api/v1/connectors/internal/{connectorNo}/occupancies/{jobRef}` | `connector.internal.write` | → `OccupancyView` | 释放：记录不存在 → `1010C0003`；已 `RELEASED` → **幂等返回既有**（200）；否则 `RELEASED` + `released_at` + 留痕 `connector.occupancy.release` |
| 9 | GET | `/api/v1/connectors/internal/{connectorNo}/occupancies` | `connector.internal.read` | → `List<OccupancyView>` | 内部只读清单（在途 + 已释放），供 3.6.8 演练与运维排查；连接器不存在 → `1010C0003` |

> **路径不冲突实测要求**（hifi §11-6）：`/api/v1/connectors/upgrades/pending` 为字面量两段路径（既有 `/{connectorNo}` 单段模板不匹配）；`/internal/{connectorNo}/occupancies` 与 3.6.3 的 `/internal/connectors/{connectorNo}` 首段字面量不同（`connectors` vs `CN`）。`{connectorNo}` / `{upgradeNo}` 一律正则校验（`^CN\d{6}$` / `^UC\d{6}$`），非法 → `1010C0001`（不把非法值带进 SQL）。
>
> 统一响应 `ApiResult<T>`（`code="0"` / `data`）；分页 `PageResult<T>`；分页越界 → `1000C0001`（common-pagination 既有口径）。

## §6 错误码表（1010 段增量，Q6-A）

| 码 | 常量 | 语义 | HTTP |
| --- | --- | --- | --- |
| `1010C0008` | `JOB_IN_PROGRESS` | **存在进行中的交付作业，暂不能注销**（提示先完结或终止） | 400 |
| `1010C0009` | `VERSION_UNCHANGED` | **目标版本与当前版本相同**（无意义变更） | 400 |

**复用（不新增）**：`1010C0001` 参数不合法（版本格式 / 兼容性取值 / 编号格式 / `jobRef` 长度 / Bean Validation）/ `1010C0003` 未找到或无权访问（防枚举同码同文：非属主、连接器不存在、升级记录不存在或与路径不匹配）/ `1010C0004` 当前状态不允许（升级非注册态、重复待确认、确认非待确认、注销已注销或非可注销态、版本乐观门槛失效）；`1000C0001`（分页越界）/ `1000C0002`（未认证）/ `1000C0005`（无权限）。
**无新 `1010S` 码**：本卡零出站跨域调用（不调 subject / did / delivery），`1010S0003` 维持**段位预留不启用**。**1010 段在用码 9 → 11**（`1010C0001~0009` + `1010S0001~0002`）；占用留痕写 **ADR-028 §2**。

## §7 状态机与关键流程

**连接器生命周期状态机（本卡新增 2 条边，无新状态、无中间态——规格 §7 Q5-A）**

```
[申请] --POST /registrations--> PENDING_REVIEW
PENDING_REVIEW --注销--> CANCELLED（= 撤回申请：编号未签发、无绑定；本卡实现）
PENDING_REVIEW --核验全过--> REGISTERED
REGISTERED --升级(兼容)--> REGISTERED（版本原子替换，留痕）
REGISTERED --升级(不兼容)--> REGISTERED + 变更行 PENDING_CONFIRM（当前版本不动）
PENDING_CONFIRM --运营方显式确认--> REGISTERED（版本替换 + 变更行 APPLIED）
PENDING_CONFIRM --连接器注销--> VOIDED（作废，不生效）
REGISTERED --注销--> CANCELLED（绑定 → RELEASED + released_at；本卡实现）
```

**升级登记（`ConnectorUpgradeService.request`，事务）**

1. 读档案（不存在 → `1010C0003`）；属主校验（`registration.subjectNo() != caller` → `1010C0003`）；
2. 状态门槛 `REGISTERED`（否则 `1010C0004`）；
3. 目标版本格式（`1010C0001`）→ 与当前版本相同（`1010C0009`）→ 兼容性取值（`1010C0001`）；
4. 应用层预检待确认（`1010C0004`）+ **DB 唯一索引兜底**（`DuplicateKeyException` → `1010C0004`，并发两条不兼容升级只成功一条）；
5. 兼容：取号 `UC%06d` → insert（`APPLIED` / `applied_at`）→ `UPDATE connector_registration SET connector_version = ? WHERE connector_no = ? AND status = 'REGISTERED' AND connector_version = ?`（**乐观门槛**，0 行 → `1010C0004`）→ 留痕 `connector.upgrade.applied`（detail = `from=…;to=…;compatibility=COMPATIBLE`）；
6. 不兼容：取号 → insert（`PENDING_CONFIRM`，`applied_at` NULL）→ 留痕 `connector.upgrade.requested`。

**显式确认（`confirm`，事务）**

1. 读变更行（`1010C0003`）→ 与路径 `connectorNo` 一致（否则 `1010C0003`）→ 状态 `PENDING_CONFIRM`（否则 `1010C0004`）；
2. `UPDATE connector_registration SET connector_version = to_version WHERE connector_no = ? AND status = 'REGISTERED' AND connector_version = from_version`（0 行 → `1010C0004`：连接器已注销或版本已被并发变更）；
3. `UPDATE connector_version_change SET status = 'APPLIED', confirmed_by = ?, applied_at = NOW() WHERE upgrade_no = ? AND status = 'PENDING_CONFIRM'`（0 行 → `1010C0004`）；
4. 留痕 `connector.upgrade.confirmed`（detail 含 `confirmedBy` 与 `from=…;to=…`）。

**注销（`ConnectorCancellationService.cancel`，事务）**

1. 读档案（不存在 → `1010C0003`）；属主或 `admin`（否则 `1010C0003`）；
2. 状态 ∈ {`PENDING_REVIEW`, `REGISTERED`}（否则 `1010C0004`）；
3. **前置检查**：`countInProgress(connectorNo) > 0` → 拒绝留痕（`connector.cancelled` / `DENIED` / `1010C0008`）→ `1010C0008`（状态不变）；
4. 同事务：
   - `UPDATE connector_registration SET status='CANCELLED', cancelled_at=NOW() WHERE connector_no=? AND status IN ('PENDING_REVIEW','REGISTERED')`（0 行 → `1010C0004`）；
   - `UPDATE connector_did_binding SET status='RELEASED', released_at=NOW() WHERE connector_no=? AND status='ACTIVE'`（**记录保留**，无 ACTIVE 绑定则 0 行，正常）；
   - `UPDATE connector_version_change SET status='VOIDED' WHERE connector_no=? AND status='PENDING_CONFIRM'`（作废待确认升级）；
   - 留痕 `connector.cancelled`（`SUCCESS`，detail 含 `fromStatus=…;inProgress=0`）。
5. 返回 `LifecycleView`（含 `cancelledAt` / 绑定 `RELEASED` / `releasedAt`）。

**注销后拒绝语义（零改 3.6.3 代码，立卡实测）**

| 动作 | 结果 | 来源 |
| --- | --- | --- |
| 详情 `GET /{connectorNo}` | **200**（历史可查） | 3.6.3 `ConnectorQueryService.detail` 无状态门槛 |
| 动作留痕 `GET /{connectorNo}/action-logs` | **200** | 同上（`actionLogs`） |
| 心跳 `POST /{connectorNo}/heartbeats` | `1010C0004` | 3.6.3 `heartbeat` 仅 `REGISTERED` |
| 绑定查验 `GET /{connectorNo}/binding` | `1010C0003` | 3.6.3 只读 `ACTIVE` 绑定，注销后 0 行 |
| 内部面 `GET /internal/connectors/{connectorNo}` | `1010C0003` | 3.6.3 `internal` 仅 `REGISTERED` |
| 升级登记 / 重复注销 / 显式确认 | `1010C0004` | 本卡状态门槛（本卡实现） |
| 占用登记 | `1010C0004` | 本卡状态门槛（本卡实现） |
| 生命周期聚合读 / 版本链 | **200** | 本卡读面（无状态门槛） |

**留痕动作常量新增（`ConnectorAuditActions`，6 个）**：`connector.upgrade.requested` / `connector.upgrade.applied` / `connector.upgrade.confirmed` / `connector.cancelled` / `connector.occupancy.register` / `connector.occupancy.release`（拒绝类沿既有 `connector.denied` 或同动作 `result=DENIED`）。

## §8 跨域装配改动

**零**。本卡不新增任何服务间调用，**不改 subject-service / did / catalog / contract / delivery 的代码与配置**（与 3.6.3 的两处装配不同）。在途作业由**使用方主动登记**到本服务内部面（Q2-A），接入方归 3.6.8。
**明确不动**：网关与路由（零改动）、既有 12 端点语义（零改动）、门禁配置（零改动）、其它域（零改动）。

## §9 测试清单（测试先行；映射规格验收标准）

| # | 测试类 | 覆盖（规格条目 → 用例） |
| --- | --- | --- |
| T1 | `ConnectorUpgradeApiTest` | 行为 9 验收 1/2/3：兼容登记 → 版本原子替换 + `APPLIED` + `applied_at` + 留痕含 `from/to` + 状态仍 `REGISTERED` / 不兼容登记 → `PENDING_CONFIRM` 且**当前版本不变**（详情读到旧版本、心跳正常）/ 重复提交待确认 → `1010C0004` / 目标版本 = 当前版本 → `1010C0009` / 版本格式非法与兼容性非法 → `1010C0001` / 非属主 → `1010C0003` / 待核验态 → `1010C0004` / 已注销 → `1010C0004` / 编号格式非法 → `1010C0001` |
| T2 | `ConnectorUpgradeQueryApiTest` | 行为 9 验收 2/4 + Q4：显式确认 → 版本替换 + `confirmed_by` + `applied_at` + 队列移除 / 确认非待确认记录 → `1010C0004` / `upgradeNo` 与路径 `connectorNo` 不匹配 → `1010C0003` / 确认时版本已被并发变更（先做兼容升级再确认旧记录）→ `1010C0004` / 版本链倒序全链（兼容 + 不兼容两行，字段齐备）/ 待确认队列（`admin` 200、业务角色 403 `1000C0005`）/ 非属主读版本链 → `1010C0003` / 分页越界 → `1000C0001` |
| T3 | `ConnectorCancellationApiTest` | 行为 10 验收 1/2/3 + 行为 8 规则 1 后半：无在途 → 成功（`CANCELLED` + `cancelled_at` + 绑定 `RELEASED` + `released_at` + 留痕）/ 有在途 → `1010C0008` 且状态不变 + 拒绝留痕 / 非属主非运营方 → `1010C0003` / `admin` 可注销他主体连接器 / 待核验态注销成功（撤回）/ 重复注销 → `1010C0004` / 注销时待确认升级 → `VOIDED` / **注销后四类业务动作各拒绝一次**（心跳 `1010C0004`、升级 `1010C0004`、绑定查验 `1010C0003`、内部面 `1010C0003`）+ 读面两个 200（详情、留痕） |
| T4 | `ConnectorLifecycleApiTest` | 行为 10 验收 4：生命周期聚合读——注册态（版本链、`inProgressJobs=0`、绑定 `ACTIVE`、`cancelledAt` 空）/ 注销后（`status=CANCELLED`、`cancelledAt` 非空、`bindingStatus=RELEASED`、`releasedAt` 非空、版本链保留）/ 有在途时 `inProgressJobs` 如实 / 非属主 → `1010C0003`、`admin` 200 / 编号非法 → `1010C0001`、不存在 → `1010C0003` / **注销后同名重注册**（`PENDING_REVIEW` 受理 → 核验通过签发新 `CN` ≠ 旧 `CN`；依赖 3.6.3 活跃态生成列） |
| T5 | `ConnectorOccupancyApiTest` | Q2-A 占用口径：登记 → 清单 `inProgress=1` / 重复登记**幂等**（仍 1）/ 释放 → 0 / 重复释放幂等 200 / 释放不存在 `jobRef` → `1010C0003` / `jobRef` 空或超 64 字符 → `1010C0001` / 待核验与已注销态登记 → `1010C0004` / 连接器不存在 → `1010C0003` / 业务角色调内部写 → 403 `1000C0005`、无身份 → 401 `1000C0002` / `connector-internal` 调内部清单 → 200 |
| T6 | `ConnectorLifecycleAuditTest` | 留痕：6 类新动作各落 `connector_action_log` 一行（升级提交 / 升级生效 / 显式确认 / 注销 / 占用登记 / 占用释放）+ 注销拒绝留痕（`DENIED`）+ 升级非属主拒绝（`connector.denied`）；`detail` **不含秘密材料**（断言无 password / secret / token / key / credential / 密钥 / 口令 等词）；JSONL 审计以**捕获式 `AuditRecorder`** 断言事件要素（沿 3.6.3 T8 形态，不依赖异步文件设施） |

**测试基础设施**：复用 3.6.3 的 `ConnectorTestSupport` / `SharedMySqlContainer` / `ConnectorStubServer`（**本卡零新测试基建、零新桩**——无出站调用）；用例总数基线 = 3.6.3 的 **52 例只增不减**（新增约 30~35 例）。

## §10 ADR-028 大纲（编码段落盘，七字段齐备 = 门禁 `adrFieldsCheck`）

`docs/adr/ADR-028-连接器生命周期管理契约.md`：§1 背景（C-6.2 升级 / 版本兼容 / 注销落地 + 上游 3.6.3 已交付的建表前瞻）→ §2 决策（**1010 段占用表：+C0008 / +C0009，在用 11 码 + 1 预留段位**；4 个权限点；**单表承版本链 + 生成列待确认唯一**；**在途作业 = 连接器域占用台账**〔含接入方归 3.6.8 的诚实声明〕；注销两态来源 + 终态事务四步）→ §3 理由 → §4 备选（lofi Q1~Q7 的 B / C 路径与不采理由）→ §5 业务影响说明（含"V1.0 期交付链未接入 → 在途计数恒 0"的诚实口径）→ §6 影响范围（connector-service 5 个既有文件 + 1 迁移 + yml 3 行，其它域零改动）→ §7 可替换性（可换：编号前缀 / 占用登记形态 / 待确认队列分页；有意不可换：不兼容须显式确认、编号终身不复用、绑定记录保留、1010 段位）。

## §11 前置检查项（编码段第一动作，逐项实测留痕）

1. `connector-service` 现状复核：`V1__create_connector_tables.sql`（5 表）与 `ConnectorStatus` / `BindingStatus` / 生成列 `active_name_norm` 是否与 lofi §2 描述一致；
2. **迁移版本号 `V2` 未占用**（`db/migration` 当前仅 `V1`）；
3. **升级单号前缀 `UC` 全仓零冲突**（Java + SQL + docs 检索）；
4. **生成列 + 唯一索引 NULL 语义实测**（`IF(status='PENDING_CONFIRM', connector_no, NULL)` 在 Testcontainers MySQL 8：同连接器插入第二条 `PENDING_CONFIRM` 失败、转为 `APPLIED`/`VOIDED` 后可再插入）；
5. `ON DUPLICATE KEY UPDATE` 幂等 upsert 行为实测（同 `(connector_no, job_ref)` 重复登记不增行、计数不变）；
6. **注销后拒绝语义四项实测**（详情 200 / 心跳 `1010C0004` / 绑定查验 `1010C0003` / 内部面 `1010C0003`）——确认零改 3.6.3 代码即可满足行为 10-2/10-3；
7. 新路径与既有 12 端点**无匹配冲突**（Spring 匹配实测：`/upgrades/pending`、`/internal/{connectorNo}/occupancies`）；
8. 台账沿承三件确认不变（DB-64 留置 / F-W1 / 8086 停用）+ **DB-73 待裁**（3.6.3 遗留，归 3.6.8 建议不变）。

## §12 配置项清单

| 配置 | 默认 | 说明 |
| --- | --- | --- |
| `ctds.auth.permissions.applicant` | 追加 `connector.upgrade,connector.cancel` | 属主可登记升级、可注销 |
| `ctds.auth.permissions.admin` | 追加 `connector.upgrade.confirm,connector.cancel` | 运营方可显式确认不兼容升级、可注销 |
| `ctds.auth.permissions.connector-internal` | 追加 `connector.internal.write` | 使用方（交付链 / SDK，接入归 3.6.8）登记与释放在途作业 |

**零新增业务配置项**（无新阈值 / 开关 / 外部地址）。

## 确认记录（V0.9 → V1.0）

| 项 | 内容 |
| --- | --- |
| 提请确认 | 2026-10-26；契约基准 = lofi Q1~Q7 全采 A + D1 不拆分 |
| 编排师裁定 | ⏳ **待确认**（立卡段 V0.9） |
| 确认后动作 | 本文件正文（§1~§12）转 V1.0 = 编码契约；编码段以 §11 前置检查八项为第一动作，测试先行（§9 T1~T6 逐类落盘） |
