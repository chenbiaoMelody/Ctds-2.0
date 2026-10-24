# WBS-3.5.5 高保真设计：沙箱结果审核与导出（V0.9，2026-10-24）

> 状态：**V1.0（编码契约，2026-10-24 12:23 编排师一次确认即时生效）**——Q1~Q8 全采建议 A + G2-A + D1 不拆分；V0.9 → V1.0 变更 = 版本头与确认记录更新，**正文零改动**。
> 上游：任务卡 `docs/tasks/WBS-3.5.5-沙箱结果审核与导出-2026-10-24.md`；规格 `docs/specs/C-5.1-5.2-交付与沙箱.md` **V1.1**（行为 6 / 行为 7 / 行为 5 规则 1）；剧本 `docs/manuals/C-5.2-数据沙箱-验收剧本.md` **V1.1**（S2-5~8 / S3-1~5、8、11）；ADR-024 / ADR-023 / ADR-020 §2.8 / ADR-016 §2.7·§6 / ADR-005 / ADR-007。
> 范围：**在既有 `delivery-service` 沙箱子域内新增结果物审核与导出面**（提交审核 / 治理审核 / 审批后导出 / 记录与读面），零新模块、零新 Maven 依赖、零新容器镜像、零新消费方。

## §1 模块装配与配置

### 1.1 包结构（`services/delivery-service` 既有分层包内新增，`Sandbox*` 前缀划界沿 3.5.4 口径）

```
com.ctds.delivery（既有包）
├── application
│   ├── SandboxReviewService        # 提交审核 + 治理审核（状态机唯一写者）
│   ├── SandboxExportService        # 审批后导出（策略 → 快照复核 → 加密包 → 交付记录）
│   └── （查询面方法加性并入 SandboxQueryService：结果状态字段 / 导出记录列表 / 审核留痕列表）
├── domain
│   ├── SandboxResult / SandboxResultStatus          # PENDING / APPROVED / REJECTED
│   ├── SandboxResultReview                          # 一轮审核的不可变记录
│   ├── SandboxResultRepository / SandboxResultReviewRepository
│   └── （SandboxErrorCodes 加性新增 1009C0015 / 1009C0016）
├── infrastructure
│   ├── JdbcSandboxResultRepository
│   └── JdbcSandboxResultReviewRepository
├── audit
│   └── （SandboxAuditActions 加性新增 3 个动作；SandboxAuditService 复用）
└── interfaces
    ├── SandboxResultController     # 提交审核 / 导出 / 导出记录列表（参与者面）
    └── （SandboxGovernanceController 加性新增 2 个方法：审核动作 + 审核留痕列表）
```

**加性改动（不改既有语义）**：
1. `SandboxErrorCodes` 增 `1009C0015` / `1009C0016`；`DeliveryExceptionHandler.MAPPED_CODES` 与 `statusOf` 同步登记（既有码值零改动）。
2. `SandboxGovernanceController` 增 2 个方法（治理审核写动作 + 审核留痕列表）。
3. `SandboxQueryService` 增结果状态读面方法（结果列表 Join `sandbox_result` 补状态字段）与导出记录 / 审核留痕列表方法。
4. `SandboxViews.ResultView` 加性增 `status / conclusion / rejectReason / reviewRound`（可空——未提交的结果物为 null）。
5. `application.yml`：`admin` 权限点追加 `sandbox.review`（ requester / provider 权限点零改动）。
6. Flyway **V3** 迁移（§2）。

### 1.2 配置（零新配置节）

- 导出包加密与上限**复用 3.5.3 交付文件配置**：`ctds.delivery.file.key-ref`（KMS 密钥引用）、`ctds.delivery.file.max-bytes`（64MiB，提交审核时即前置校验）；`FilePackageStore` 存储根复用 `ctds.delivery.storage-root`。
- 审核理由长度上限 = 常量 256（不进配置，无运行期调参需求）。
- `SandboxProperties` 零改动（审核导出无运行时 / 配额面）。

## §2 数据模型与迁移（Flyway V3）

> 只存业务要素与校验值；**无文件内容、无宿主机路径、无容器 / 镜像 / 命令、无密钥材料**。

### 2.1 `sandbox_result`（结果物登记：一任务一文件名一行）

| 列 | 类型 | 说明 |
| --- | --- | --- |
| `id` | BIGINT PK AI | |
| `sandbox_no` | VARCHAR(16) | |
| `file_name` | VARCHAR(128) | 结果物标识（工作区 output 顶层文件名，`[A-Za-z0-9._-]{1,128}`） |
| `status` | VARCHAR(16) | `PENDING` / `APPROVED` / `REJECTED` |
| `review_round` | INT | 审核轮次（首次提交 = 1，重新提交 +1） |
| `size_bytes` | BIGINT | 提交时快照 |
| `checksum` | VARCHAR(64) | 提交时快照（SM3 小写 hex；提交时文件 >64MiB 已被拒，故恒非空） |
| `submitted_by` | VARCHAR(32) | 需求方主体号 |
| `submitted_at` | DATETIME | |
| `reviewed_by` | VARCHAR(32) NULL | 最近一轮审核人（运营方） |
| `reviewed_at` | DATETIME NULL | |
| `reject_reason` | VARCHAR(256) NULL | 最近一轮驳回理由 |
| `idempotency_key` | VARCHAR(128) | 最近一次提交的幂等键 |
| `created_at` / `updated_at` | DATETIME | |

索引：`UNIQUE(sandbox_no, file_name)`、`KEY(sandbox_no, status)`。

### 2.2 `sandbox_result_review`（审核留痕：一轮一行，不可变）

| 列 | 类型 | 说明 |
| --- | --- | --- |
| `id` | BIGINT PK AI | |
| `sandbox_no` / `file_name` | VARCHAR(16) / VARCHAR(128) | |
| `review_round` | INT | 对应提交轮次 |
| `conclusion` | VARCHAR(16) | `APPROVED` / `REJECTED` |
| `reason` | VARCHAR(256) NULL | 驳回必填 / 通过可空 |
| `reviewed_by` | VARCHAR(32) | 运营方主体号 |
| `created_at` | DATETIME | 审核时刻 |

索引：`KEY(sandbox_no, file_name, review_round)`。

### 2.3 既有表加性变更

1. `delivery` 表**加列**：`sandbox_no VARCHAR(16) NULL`、`result_name VARCHAR(128) NULL`（仅 `SANDBOX_EXPORT` 行非空）；`KEY(sandbox_no)`；`delivery_type` 取值域新增 `SANDBOX_EXPORT`（列类型不变，`DeliveryType` 枚举加性新增）。既有行两列恒 NULL，语义零影响。
2. `sandbox_action_log` **加列**：`result_name VARCHAR(128) NULL`（结果物维度动作与拒绝留痕填写；既有行恒 NULL）。

## §3 结果物状态机与提交审核

### 3.1 状态机

| 当前 | 动作 | 结果 | 说明 |
| --- | --- | --- | --- |
| （未登记） | 需求方提交审核 | `PENDING`（round=1，快照落库） | 文件须存在于工作区 output 且 ≤64MiB |
| `PENDING` | 再次提交 | 拒绝 `1009C0016`（409） | 审核中不可重复提交（幂等重放除外） |
| `PENDING` | 运营方审核通过 | `APPROVED` | 写 `sandbox_result_review`；结论不可改 |
| `PENDING` | 运营方审核驳回 | `REJECTED`（理由必填） | 同上 |
| `REJECTED` | 需求方重新提交 | `PENDING`（round+1，快照刷新） | **G2 口径（待 PO 批准）** |
| `APPROVED` | 再次提交 | 拒绝 `1009C0016`（409） | 已通过无需再审 |
| `APPROVED` | 导出 | 状态不变 | 可多次导出（每次一次计数、一条交付记录） |
| `REJECTED` / `PENDING` /（未登记） | 导出 | 拒绝 `1009C0016`（409） | 唯一出口规则 |
| 任意 | 任务到期 / 关闭后提交 / 审核 / 导出 | 拒绝 `1009C0011`（409） | 生命周期门槛（Q4-A） |

### 3.2 提交审核链路（固定顺序）

```
身份（sandbox.use）→ 参数（sandboxNo / fileName / X-Idempotency-Key）
→ 任务可见性（参与方判定）→ 非参与方 1009C0010（404 同码同文，任务级）
→ 需求方判定 → 1009C0003（403）
→ 任务 RUNNING → 1009C0011（409）
→ 幂等查重（内存 @Idempotent，key = sandboxNo:fileName:idempotencyKey）→ 命中返回当前登记
→ 文件存在性（listResults 含该 fileName）→ 不存在 1009C0015（404）
→ 大小 ≤ ctds.delivery.file.max-bytes → 1009C0008
→ 状态门槛（未登记或 REJECTED 可提交；PENDING/APPROVED → 1009C0016）
→ 快照（sizeBytes + SM3）→ UPSERT sandbox_result（REJECTED 重提交 = round+1）
→ sandbox_action_log（sandbox.result.submit）+ 审计 JSONL
→ 返回登记视图
```

- **快照来源**：`SandboxWorkspaceStore.listResults` 的元数据（SM3 由 `common-crypto` 计算，禁止另行实现）。
- **拒绝留痕**：全链拒绝均落 `sandbox_action_log`（`sandbox.denied` + reasonCode + result_name）+ 审计，沿 3.5.4 双留痕口径。

## §4 治理审核（运营方唯一写面）

```
身份（sandbox.review，仅 admin）→ 参数（sandboxNo / fileName / {conclusion, reason}）
→ 任务可见性（治理档：任何任务可审，读取即留痕口径）
→ 任务 RUNNING → 1009C0011（409）
→ 登记存在且 PENDING → 否则 1009C0015（未登记）/ 1009C0016（已定论，结论不可改）
→ conclusion ∈ {APPROVE, REJECT}；REJECT 时 reason 必填（1~256 字）→ 否则 1009C0005（400）
→ 事务：INSERT sandbox_result_review + UPDATE sandbox_result（APPROVED/REJECTED + 审核人 / 时间 / 理由）
→ sandbox_action_log（sandbox.review）+ 审计 JSONL
→ 返回审核后登记视图
```

- **结论不可修改**：已定论（APPROVED / REJECTED）再审 → `1009C0016`；纠错路径 = 需求方重新提交触发新轮次（G2）。
- **审核历史**：`sandbox_result_review` 按 round 追加，永不 UPDATE / DELETE。
- 审核留痕列表（治理读面）：`GET /api/v1/sandboxes/governance/{sandboxNo}/reviews`，按文件 + 轮次返回，**读即留痕**（沿 `sandbox.governance.read` 先例）。

## §5 审批后导出（唯一内容出口）

```
身份（sandbox.use）→ 参数（sandboxNo / fileName / X-Idempotency-Key / {purpose?, territory?}）
→ 任务可见性 → 非参与方 1009C0010（404 同码同文）
→ 需求方判定 → 1009C0003（403）
→ 任务 RUNNING → 1009C0011（409）
→ 幂等查重（key = requester:idempotencyKey，落 delivery 表唯一键兜底）→ 命中返回首次导出记录
→ 登记存在且 APPROVED → 未登记/待审核/驳回 → 1009C0016（409）
→ [contract] check（USE，purpose / territory）→ 拒绝原码透传（1008C0020 等），零计数零记录零包
→ 快照复核：listResults 当前 sizeBytes/SM3 vs 登记快照 → 不一致 1009C0016（提示重新提交审核）
→ 读工作区文件 → 大小复核 ≤ max-bytes → 1009C0008
→ SM4 加密（common-crypto，key-ref 复用 ctds.delivery.file.key-ref）→ packageId = <deliveryNo>.sm4 → FilePackageStore 存储
→ 取号 DV（delivery_no_seq 复用）→ INSERT delivery（type=SANDBOX_EXPORT，sandbox_no / result_name /
   displayName=fileName、fileSize、packageId、checksum=快照 SM3、keyRef、requester / provider / 合约与产品快照）
→ 失败清理：包生成 / 插入失败 → 删除包、不写记录（DuplicateKeyException → 删包并按幂等键返回首次）
→ sandbox_action_log（sandbox.export）+ 审计 JSONL
→ 返回导出记录视图（deliveryNo / fileName / sizeBytes / checksum / createdAt）
```

- **一次导出 = 一次数据使用**：`check` 放行即计数 1；拒绝零计数零导出记录；`check` 通过后平台侧失败（加密 / 存储）→ 不写导出记录 + 审计 FAILURE，**不改写已发生的计数**（沿 3.5.3 Q4 诚实边界）。
- **导出物领取**：复用 3.5.3 交付包下载端点 `GET /api/v1/deliveries/{deliveryNo}/package`（`delivery.package.download`，仅合约需求方，密文 + `X-Ctds-Sm3` 头）——本卡**零新下载代码**。
- **用途 / 地域**：请求可选；合约策略要求而缺失 → 由合约域 `1008C0020` 拒绝（不自行补默认值，沿 3.5.4 口径）。
- **计量与存证**：导出行即交付事件（`delivery` 表），3.7.5 / 3.8.3 读单一交付事件源；不与 `sandbox_job` 双计。

## §6 端点、权限、错误码与出站映射

### 6.1 端点（资源命名沿规格 Q8-A）

| 方法 | 路径 | 权限 | 说明 |
| --- | --- | --- | --- |
| POST | `/api/v1/sandboxes/{sandboxNo}/results/{fileName}/actions/submit-review` | `sandbox.use` | 需求方提交审核（`X-Idempotency-Key` 必填） |
| POST | `/api/v1/sandboxes/{sandboxNo}/results/{fileName}/actions/export` | `sandbox.use` | 需求方导出（`X-Idempotency-Key` 必填；body `{purpose?, territory?}`） |
| GET | `/api/v1/sandboxes/{sandboxNo}/results` | `sandbox.read` | 结果物元数据 + 状态（**既有端点加性扩字段**） |
| GET | `/api/v1/sandboxes/{sandboxNo}/exports` | `sandbox.read` | 导出记录列表（参与者；分页） |
| POST | `/api/v1/sandboxes/governance/{sandboxNo}/results/{fileName}/actions/review` | `sandbox.review` | 治理审核（body `{conclusion: APPROVE\|REJECT, reason?}`） |
| GET | `/api/v1/sandboxes/governance/{sandboxNo}/reviews` | `sandbox.governance` | 审核留痕列表（**读即留痕**） |
| GET | `/api/v1/deliveries/{deliveryNo}/package` | `delivery.package.download` | **复用 3.5.3 端点**领取加密导出包（零新代码） |

- `fileName` 路径变量须匹配 `[A-Za-z0-9._-]{1,128}`，否则 `1009C0005`（400）。
- 导出记录列表 / 审核留痕列表 `pageSize` ≤ 100；详情与列表**不返回**工作区路径、内容、容器、镜像。

### 6.2 错误码（1009 段顺延；0010~0014 / S0004~0005 为 3.5.4 既有）

| 码值 | 含义 | HTTP |
| --- | --- | --- |
| `1009C0015` | 结果物不存在或无权查看（同码同文防枚举，结果物级） | 404 |
| `1009C0016` | 结果物当前状态不允许该操作（未审核 / 审核中 / 已驳回 / 快照不一致 / 重复审核 / 已通过再提交） | 409 |

复用（零改动）：`1009C0003`（非需求方）/ `1009C0005`（参数）/ `1009C0008`（超上限）/ `1009C0009`（仅需求方可领取包）/ `1009C0010`（任务级防枚举）/ `1009C0011`（生命周期）/ `1009S0002`（依赖不可用）/ `1009S0003`（包操作失败）；合约域 `1008C` 原码透传。S 型文案为服务端常量，不含内部细节。

## §7 留痕与字段集

### 7.1 动作（`SandboxAuditActions` 加性新增）

| action | 时机 | result |
| --- | --- | --- |
| `sandbox.result.submit` | 提交 / 重新提交成功 | SUCCESS |
| `sandbox.review` | 审核通过 / 驳回 | SUCCESS（结论与理由在 `sandbox_result_review`） |
| `sandbox.export` | 导出成功 / 平台侧失败 | SUCCESS / FAILURE |
| `sandbox.governance.read`（复用） | 审核留痕列表 / 治理读面 | SUCCESS |
| `sandbox.denied`（复用） | 各类拒绝（含 0015 / 0016 / 0011 / 1008C 透传） | DENIED + reasonCode + result_name |

### 7.2 字段集（DB + 审计 JSONL）

- 允许：`sandboxNo` / `fileName`（结果物标识）/ `deliveryNo` / `contractNo` / `operatorNo` / `action` / `result` / `reasonCode` / `status` / `conclusion` / `reason`（审核理由，业务文本）/ `reviewRound` / `sizeBytes` / `checksum` / 时间。
- **禁止**：文件内容与原文、宿主机路径、容器 ID / 容器名、镜像名、命令 argv、密钥材料 / 密钥值（`keyRef` 为引用标识可留痕，密钥本体不可）、令牌。
- 导出包 = 唯一带内容物，**仅密文**离开服务进程（下载端点）。

## §8 测试清单与红相

> 测试先行：先写失败用例并归档 `build-output/w355-red-*`，经确认后实现；用例只增不减；既有 delivery **135** 例必须保持全绿（`ResultView` 加字段的既有断言按"只增字段"口径同步扩断言集，判定不变）。

| 组 | 例数 | 锚点 |
| --- | --- | --- |
| 提交审核 | 10 | 成功（登记 / 快照 / 留痕 / 状态 PENDING）、未登记文件 0015、非法文件名 0005、超上限 0008、非需求方 0003、非参与方 0010 同码同文、PENDING 再提交 0016、REJECTED 重提交 round+1、APPROVED 再提交 0016、幂等重放（同键返回首次登记、零新增留痕）（评审修复批补） |
| 治理审核 | 9 | 通过（状态 + review 行 + 留痕）、驳回（理由必填入 review）、驳回无理由 0005、已定论再审 0016、未登记 0015、非 admin 权限层 1000C0005、审核留痕列表（两条结论理由齐备 + 读即留痕）、任务到期后审核 0011、审核记录字段集（无内容 / 路径） |
| 导出 | 13 | 成功（加密包落盘 + SM3 头 + delivery SANDBOX_EXPORT 行 + check 计数恰 +1 + 留痕）、未提交导出 0016、驳回导出 0016（理由可查）、待审核导出 0016、策略越界 1008C0020 零计数零记录零包、快照不一致（覆盖文件后）0016、到期 / 关闭任务导出 0011、非需求方导出 0003、非参与方 0010 同码同文、幂等重放（同键返回首次、计数不重复）、唯一键兜底（预插同键行触发 DuplicateKey：返回首次记录 + 包删除，评审修复批补）、下载端点（需求方可取 + 提供方 1009C0009）、包生成失败不写记录不残留 |
| 读面与防枚举 | 6 | 结果列表含状态 / 结论 / 理由 / 轮次（未提交为 null）、导出记录列表（参与者）、非参与方结果 / 导出 / 审核列表三面 404 同码同文、提供方只读（可查不可提交 / 导出）、驳回理由需求方可查、治理读留痕 |
| 留痕与无本体 | 4 | 审计 JSONL 字段集（无路径 / 内容 / 容器 / 命令 / 密钥值）、DB 行字段集、响应字段集、`keyRef` 为引用非密钥断言 |
| 配置与迁移 | 3 | Flyway V3 迁移（含 delivery / action_log 加列 + `uk_sandbox_result` 唯一键与列宽锚，既有行 NULL）、权限点装载（admin 含 sandbox.review）、导出参数校验（conclusion 枚举 / reason 长度） |

**真 Docker 端到端（默认跳过）**：`@EnabledIfEnvironmentVariable(named = "CTDS_SANDBOX_E2E", matches = "1")`，1~2 例（真容器跑作业产出结果物 → 提交 → 审核 → 导出 → 下载解密复核 SM3），走查段启用。

### 字段集断言

记录 / 审计 / 日志 / 响应四处取证：无工作区路径、无密钥值、无文件内容、无容器 / 镜像 / 命令。

## §9 复用声明 / 加性改动 / 规格外实现声明

**复用（只读，零改动）**：`common-auth` / `common-errorcode` / `common-pagination` / `common-idempotency` / `common-logging` / **`common-crypto`（`Sm4Service` / `Sm3Service`）**；delivery-service 既有 `SandboxWorkspaceStore.listResults`、`SandboxQueryService`（参与者判定 / 同码同文先例）、`ContractInternalClient`（`summarize` / `check` 原码透传）、`FilePackageStore`、`DeliveryQueryService.download`（交付包领取端点）、`DeliveryBeanConfig(Clock)`、`delivery_no_seq` 取号、`SandboxAuditService`；测试支撑 `StubDependencyServer` / `SharedMySqlContainer` / `FakeSandboxRuntime`。

**加性改动（不改既有语义）**：`SandboxErrorCodes` + `DeliveryExceptionHandler` 码表 2 项；`SandboxGovernanceController` +2 方法；`SandboxQueryService` +3 读面方法；`SandboxViews.ResultView` +4 可空字段（既有断言按"只增字段"扩集）；`SandboxAuditActions` +3 动作；`application.yml` admin 权限点 +1；Flyway V3（2 新表 + 2 表加列）；ADR-025（编码段产出）。

**规格外实现声明**：**空**。提交审核 / 治理审核 / 导出 / 记录读面均为规格行为 6 / 行为 7 的接口承载（规格 §4-1 明确模型与端点归 3.5.2~3.5.5）；G2（驳回后重新提交）为规格未定义项，走变更流程留痕，不私自补需求。

## §10 编码段前置检查（八项，逐项实测留痕）

1. **仓库**：分支 `feat/WBS-3.5.5-沙箱结果审核与导出`；基线 = 立卡批（承接 3.5.4 收口批 `488c7eb`）；`main == origin/main == 1ed5b4f`；工作区干净。
2. **模块基线**：delivery **135**（6 跳过）/ contract 244 / catalog 182 / gateway 35 / 前端 440；checkstyle 0；实际值以本项实测为准。
3. **数据库**：MySQL 容器在线；`ctds_delivery` 存在；Flyway V1~V2 已应用；V3 待本卡首建。
4. **门禁**：`gates-config.json` V1.12 / ConfigSha256 `955bdbba…2ee2` 逐字一致；本卡预计零改动。
5. **设计与审批**：lofi / hifi **V1.0** 确认记录在案；**G2 经 PO 批准或明确登记**；编码以确认口径为准。
6. **演示与走查数据**：数据集合约（演示库已有 CO000026 等）+ 一个 **RUNNING** 沙箱任务（TTL 内可完成提交 → 审核 → 导出）+ 任务 output 下已有结果物（跑一次受控作业产出）；无则按 3.5.4 演示链先开通。
7. **依赖面**：零新 Maven 依赖、零新容器镜像、零新 ADR-016 §6 消费方；导出链无 `ProcessBuilder` 新增面。
8. **契约**：ADR-025 骨架（编码段落盘，adrFieldsCheck 五字段齐备：理由 / 备选 / 业务影响 / 影响范围 / 可替换性）。

## §11 真链走查判定表（API 级，Docker 环境 + RUNNING 任务）

| 组 | 腿数 | 判定点 |
| --- | --- | --- |
| A 提交审核 | 5 | 提交成功状态待审核（S2-5）；未登记 0015；非需求方 0003；到期任务 0011；幂等重放零新增 |
| B 治理审核 | 6 | 审核通过转已通过可导出（S2-6）；驳回含理由（S2-7）；重复审核 0016；非 admin 权限层 1000C0005；审核留痕列表两条齐备（S2-8）；留痕不含本体 |
| C 导出 | 8 | 审核通过导出放行 + 导出记录 + 计数 +1（S3-1）；未审核拒（S3-2）；驳回拒且理由可查（S3-3）；绕过界面直调同拒（S3-4）；策略越界拒零计数（S3-5）；快照不一致拒；到期 / 关闭拒（S3-6）；幂等重放不重复计数 |
| D 领取与读面 | 5 | 加密包下载 + SM3 头可核；提供方领取 1009C0009；结果列表含状态结论；导出记录列表；非参与方三面同码同文（S3-8） |
| E 无本体 | 3 | DB / 审计 JSONL / app.log / 响应四处取证零命中（内容 / 路径 / 容器 / 镜像 / 命令 / 密钥值）（S3-11） |

**对账**：导出数 ↔ `delivery` 表（SANDBOX_EXPORT）行数 ↔ 合约 `check` 计数增量；拒绝零增量；审核动作零计数。

## §12 边界、风险与演进

| 项 | 说明 / 处置 |
| --- | --- |
| 结果物 >64MiB | 提交审核即拒（1009C0008）；导出复核双保险；大文件流式加密登记演进 |
| 快照 SM3 为 null 的文件 | 提交前置校验（>64MiB 即拒）后登记行 checksum 恒非空 |
| 工作区清理 | 本卡不做（ADR-024 §6.2 口径延续）；任务关闭 / 到期后工作区与导出包保留，清理归运维收口（登记债务延续） |
| L4 双人复核 | 分级规范 L4"人工复核节点"演示期为单审核人（`sandbox.review`）；"待生效"诚实口径登记 |
| 导出包保留期 / 重复领取 | V1.0 不限次领取（密文 + 需求方限定）；到期清理登记演进 |
| 审核排队 / 通知 | V1.0 无通知机制（列表拉取）；登记演进 |
| 3.5.6 界面联动 | 剧本界面入口仍为占位，待 3.5.6 交付后一次性核对（业务判定一字不改） |

---

## 确认记录（V0.9 → V1.0）

> **✅ 已签署（2026-10-24 12:23，编排师会话回复"全部按建议"）**：Q1~Q8 全采建议 A + G2-A + D1 不拆分；本文与 `docs/designs/WBS-3.5.5-lofi.md` 同批转 **V1.0（编码契约即时生效）**。编码段归新会话冷启动（测试先行，第一动作 = §10 八项前置检查逐项实测留痕，红相归档 `build-output/w355-red-*`）。**G2-A 已于 2026-10-24 由 PO 批准，规格转 V1.2（§7 G2 决策行，零规则与验收标准文字变更）；C-5.2 剧本仅维护说明第 7 条留痕。**
>
> **勘正（2026-10-24 评审修复批，契约语义零变更）**：① 物理表名笔误 `deliveries` → `delivery`（§2.3/§5/§8/§11，URL 路径 `/api/v1/deliveries/**` 不变）；② §8 提交审核组例数 8 → 10（原列 9 锚，评审修复批 +1 幂等重放）、导出组 12 → 13（+1 唯一键兜底）。依据 = 4 视角评审①③指摘（F1.2/F1.3/F3.6），全文见评审报告与开发日志。
