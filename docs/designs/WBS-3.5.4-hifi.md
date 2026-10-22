# WBS-3.5.4 高保真设计：沙箱环境管理（V0.9，2026-10-22）

> 状态：**V1.0（编码契约，2026-10-22 21:25 编排师一次确认即时生效）**——Q1~Q9 全采建议 A + G1-A + D1 不拆分；V0.9 → V1.0 变更 = 版本头与确认记录更新，**正文零改动**。
> 上游：任务卡 `docs/tasks/WBS-3.5.4-沙箱环境管理-2026-10-22.md`；规格 `docs/specs/C-5.1-5.2-交付与沙箱.md` V1.0（行为 1 / 4 / 5 / 7）；剧本 `docs/manuals/C-5.2-数据沙箱-验收剧本.md` V1.0；ADR-005 / ADR-007 / ADR-013 / ADR-016 §2.7·§6 / ADR-020 §2.8。
> 范围：**在既有 `delivery-service` 内新增沙箱子域**（容器隔离环境 / 资源配额 / 生命周期 / 受控作业 / 数据面零出口），零新模块、零新 Maven 依赖。

## §1 模块装配与配置

### 1.1 包结构（`services/delivery-service` 内新增）

```
com.ctds.delivery.sandbox
├── application
│   ├── SandboxCommandService        # 申请开通 / 主动关闭
│   ├── SandboxJobService            # 受控作业执行（策略 + 使用留痕）
│   ├── SandboxQueryService          # 参与者读面 / 治理读面 / 结果元数据 / 作业历史
│   └── SandboxLifecycleService      # 到期扫描 + 启动对账
├── domain
│   ├── SandboxTask / SandboxStatus  # RUNNING / STOPPED / EXPIRED
│   ├── SandboxJob / SandboxJobStatus# RUNNING / SUCCEEDED / FAILED / TIMEOUT
│   ├── SandboxRepository / SandboxJobRepository
│   ├── SandboxRuntime               # 端口：launch / stop / isRunning / exec
│   ├── SandboxSpec / JobExecResult
│   ├── SandboxErrorCodes
│   └── SandboxBizException
├── infrastructure
│   ├── DockerCliSandboxRuntime      # 唯一 ProcessBuilder 落点
│   ├── DockerCommandBuilder         # 纯函数：argv 构造（单测锚点）
│   ├── DefaultProcessRunner         # ProcessRunner 默认实现（超时 + 有界读取）
│   ├── SandboxWorkspaceStore        # 输入副本 / 输出目录 / 结果元数据
│   ├── JdbcSandboxRepository
│   └── JdbcSandboxJobRepository
├── config
│   └── SandboxProperties            # ctds.sandbox.*
├── audit
│   ├── SandboxAuditActions
│   └── SandboxAuditService          # sandbox_action_log + common-logging JSONL
└── interfaces
    ├── SandboxController / SandboxGovernanceController
    ├── SandboxDataDenialController  # /data/** 一律 403
    └── dto/SandboxRequest.java / SandboxViews.java
```

**加性改动（不改既有语义）**：
1. `DeliveryApplication` 增 `@EnableScheduling`（当前全仓无 `@Scheduled`）。
2. `DeliveryBeanConfig` 的 `Clock` 直接复用（到期 / 测试时间可控），不新增 Bean。
3. `FileSourceRegistry` 增 `listForProduct(long productId)`（返回该产品已登记且校验通过的 `FileSource` 列表；只读）。
4. `DeliveryExceptionHandler` 的 `MAPPED_CODES` 与 `statusOf` 增沙箱码值（1009 同一段位、同一出站映射表）；既有码值零改动。
5. `application.yml` 增 `ctds.sandbox.*` 与角色权限点。
6. Flyway **V2** 迁移（§2）。

### 1.2 配置项（`SandboxProperties`，`@ConfigurationProperties(prefix = "ctds.sandbox")`）

```yaml
ctds:
  sandbox:
    workspace-root: ${CTDS_SANDBOX_WORKSPACE_ROOT:}   # 未配置 = 沙箱不可用（fail-closed）
    jobs-root: ${CTDS_SANDBOX_JOBS_ROOT:}             # 作业脚本根（只读挂载）
    quota:
      cpus: 1.0
      memory-mb: 512
      ttl: 2h
      job-timeout: 120s
      max-active-per-requester: 2
      max-active-global: 8
      max-input-files: 32
      max-input-bytes: 268435456                     # 256MiB
    runtime:
      enabled: ${CTDS_SANDBOX_RUNTIME_ENABLED:false}  # 默认关闭 = 503 fail-closed（k8s 不注入 docker socket）
      docker-cli: docker
      image: python:3.12-alpine
      command-timeout: 30s
    jobs:
      - id: dataset-profile
        display-name: 数据集画像
        command: [python3, /opt/ctds/jobs/dataset_profile.py, /work/input, /work/output/dataset-profile.md]
        timeout: 120s
      - id: input-inventory
        display-name: 输入清单
        command: [python3, /opt/ctds/jobs/input_inventory.py, /work/input, /work/output/input-inventory.md]
        timeout: 60s
    sweep:
      enabled: true
      fixed-delay-ms: 60000
```

角色权限点（`application.yml`，与既有交付域权限点并列）：

```yaml
ctds:
  auth:
    permissions:
      requester: delivery.request,delivery.read,delivery.package.download,sandbox.request,sandbox.read,sandbox.use
      provider: delivery.read,sandbox.read
      admin: delivery.governance,sandbox.governance
```

**启动校验（fail-fast，`@PostConstruct`）**：
1. `quota` 越界（cpus 0.1~4 / memory 128~4096 / ttl 1m~24h / jobTimeout 1s~600s / 并发 1~100 / 文件数 1~256 / 输入总字节 1MiB~1GiB）→ 启动失败。
2. `runtime.enabled=true` 时 `workspace-root`、`jobs-root` 必填且目录可写；未启用时允许为空（沙箱接口 503）。
3. 镜像必须**显式 tag**（含 `:` 且不以 `:latest` 结尾），否则启动失败（ADR-013）。
4. 作业清单：`id` 匹配 `[a-z0-9-]{1,64}`、唯一；`command` 1~32 元素、每元素 1~512 字符、无 NUL/换行；`command[0]` 仅允许 `python3`（V1.0 作业运行器白名单）；`timeout` 1s~600s 且 ≤ 任务 TTL。
5. 工作区根与作业根取 `toRealPath()` 保存，后续所有路径 containment 以此为准。

## §2 数据模型与迁移（Flyway V2）

> 只存业务要素与校验值；**无宿主机路径、无容器 ID、无文件内容、无密钥材料**（容器 ID 仅运行期内存与日志旁路使用，不入库）。容器名由 `sandbox_no` 确定（`ctds-sb-<sandboxNo>`），可随时重建，不需要持久化。

### 2.1 `sandbox_task`

| 列 | 类型 | 说明 |
| --- | --- | --- |
| `id` | BIGINT PK AI | |
| `sandbox_no` | VARCHAR(16) UK | `SB` + 6 位全局序号 |
| `contract_no` | VARCHAR(32) | 交付凭据（已生效合约） |
| `product_id` / `product_name` | BIGINT / VARCHAR(128) | 发起时快照 |
| `requester_no` / `provider_no` | VARCHAR(32) | 合约参与方快照 |
| `status` | VARCHAR(16) | `RUNNING` / `STOPPED` / `EXPIRED` |
| `cpus` | DECIMAL(3,2) | 配额快照 |
| `memory_mb` | INT | 配额快照 |
| `ttl_seconds` | INT | 时长配额快照 |
| `expires_at` | DATETIME | 到期时刻（服务端判定） |
| `data_file_count` | INT | 注入的数据文件数（不含名称/路径） |
| `close_reason` | VARCHAR(16) | `CLOSED` / `EXPIRED` / `RECONCILED` |
| `idempotency_key` | VARCHAR(128) | |
| `created_at` / `updated_at` | DATETIME | |

索引：`UNIQUE(sandbox_no)`、`UNIQUE(requester_no, idempotency_key)`、`KEY(contract_no, created_at)`、`KEY(requester_no, created_at)`、`KEY(provider_no, created_at)`、`KEY(status, expires_at)`。

### 2.2 `sandbox_job`

| 列 | 类型 | 说明 |
| --- | --- | --- |
| `id` | BIGINT PK AI | |
| `job_no` | VARCHAR(16) UK | `SJ` + 6 位全局序号 |
| `sandbox_no` | VARCHAR(16) | |
| `job_id` | VARCHAR(64) | 配置化作业编号 |
| `status` | VARCHAR(16) | `RUNNING` / `SUCCEEDED` / `FAILED` / `TIMEOUT` |
| `exit_code` | INT NULL | 容器内退出码 |
| `duration_ms` | BIGINT NULL | 执行耗时 |
| `purpose` / `territory` | VARCHAR(256) NULL | 策略要素（业务要素，不含原文数据） |
| `idempotency_key` | VARCHAR(128) | |
| `created_at` / `finished_at` | DATETIME | |

索引：`UNIQUE(job_no)`、`UNIQUE(sandbox_no, idempotency_key)`、`KEY(sandbox_no, created_at)`。

### 2.3 `sandbox_action_log` / 序号表

- `sandbox_action_log(id, sandbox_no NULL, job_no NULL, contract_no NULL, operator_no, action, result, reason_code, created_at)`；索引 `(sandbox_no, created_at)`、`(contract_no, created_at)`。
- `sandbox_no_seq(id, next_no)`、`sandbox_job_no_seq(id, next_no)` 各 1 行，`UPDATE ... SET next_no = next_no + 1` 后 `LAST_INSERT_ID()` 原子取号（沿 `delivery_no_seq` 先例，`@Transactional` 同连接）。

## §3 准入链路与生命周期状态机

### 3.1 申请开通（固定顺序，任一拒绝不建任务、不写半成品）

```
身份（AuthContext）→ 参数校验（contractNo / 幂等键）
→ 幂等查重（requester + key）→ 命中返回首次任务
→ [contract] 内部只读摘要（参与方 + EFFECTIVE + productId/productName，不计数）
→ 发起人 = 合约需求方（否则 1009C0003，先于取号与配额）
→ [catalog] 内部产品 productType → 形态校验（仅 DATASET/REPORT/MODEL，G1-A；否则 1009C0006）
→ 数据文件按产品取（FileSourceRegistry#listForProduct）→ 0 个 → 1009S0004（fail-closed）
→ 并发配额校验（单需求方 / 全局 → 1009C0013）
→ 取号 SB → 准备只读输入副本 + 输出目录（§5）
→ docker run（§4 安全基线）→ 容器运行
→ 事务：sandbox_task(RUNNING) + sandbox_action_log(CREATE)
→ 审计 → 返回视图
```

- **失败清理**：工作区准备 / 容器启动失败 → 停止并移除容器、删除工作区、**不写任务记录**；响应 `1009S0004`（运行时/数据未就绪）或 `1009S0005`（环境操作失败）。
- **并发同键**：DB 唯一键冲突 → 清理本次容器与工作区，按幂等键返回首次任务。
- **开通即启动**：V1.0 无独立 start / restart 端点；关闭后不可重启（重新申请为新任务）。

### 3.2 状态机

| 当前 | 动作 | 结果 | 说明 |
| --- | --- | --- | --- |
| — | 开通 | `RUNNING` | 容器已按基线启动 |
| `RUNNING` | 需求方关闭 | `STOPPED` / `CLOSED` | 停止并移除容器 |
| `RUNNING` | 到期扫描命中 | `EXPIRED` / `EXPIRED` | 停止并移除容器 |
| `RUNNING` | 启动对账发现容器不在 | `STOPPED` / `RECONCILED` | 服务崩溃 / 宿主重启后的状态纠偏 |
| `STOPPED` | 再次关闭 | `STOPPED` | 幂等返回当前视图（200） |
| `STOPPED` / `EXPIRED` | 作业 / 任何使用 | 拒绝 `1009C0011`（409） | 生命周期终止生效 |

- **到期双保险**：定时扫描（默认 60s，条件更新 `WHERE status='RUNNING' AND expires_at <= NOW()` 防并发重复）+ 每次使用前实时判定。
- **启动对账**：`RUNNING` 任务逐条 `runtime.isRunning`（上限 100 条）；不在运行 → `STOPPED/RECONCILED` + 留痕；`RUNNING` 作业 → `FAILED`（服务中断）。
- **单实例语义**：memory 幂等 + 本机扫描；多实例部署前换分布式锁 / 选主，登记演进（DB 候选）。

## §4 容器运行时适配器（Docker CLI）

### 4.1 端口

```java
public interface SandboxRuntime {
    String launch(SandboxSpec spec);                                   // 返回容器 ID（仅留内存/日志）
    void stop(String sandboxNo);                                       // 幂等
    boolean isRunning(String sandboxNo);
    JobExecResult exec(String sandboxNo, List<String> command, Duration timeout);
}
```

- `SandboxSpec(sandboxNo, inputDir, outputDir, jobsDir, image, cpus, memoryMb)`。
- 实现 `DockerCliSandboxRuntime`：先查 `runtime.enabled`，未启用 → `1009S0004`；所有路径 `toRealPath()` 校验落在 `workspace-root` / `jobs-root` 内；`sandboxNo` 必须匹配 `SB\d{6}`。
- **`ProcessBuilder` 仅出现在 `DefaultProcessRunner` 一处**；argv 数组、不经 shell、无字符串拼接。每个命令有超时（默认 30s），stdout/stderr **有界读取**（16KiB）后丢弃内容。

### 4.2 启动命令（`DockerCommandBuilder#launch`，逐项单测锚点）

```
docker run -d
  --name ctds-sb-SB000001
  --label com.ctds.sandbox=SB000001
  --network none
  --cpus 1.00 --memory 512m --memory-swap 512m
  --pids-limit 128
  --cap-drop ALL
  --security-opt no-new-privileges=true
  --read-only
  --tmpfs /tmp:rw,noexec,nosuid,size=16m
  --user 1000:1000
  -e PYTHONDONTWRITEBYTECODE=1 -e PYTHONUNBUFFERED=1
  --mount type=bind,source=<workspace>/SB000001/input,target=/work/input,readonly
  --mount type=bind,source=<workspace>/SB000001/output,target=/work/output
  --mount type=bind,source=<jobs-root>,target=/opt/ctds/jobs,readonly
  -w /work/output
  --rm
  python:3.12-alpine sleep 2147483647
```

**安全基线（缺一不可，测试逐项断言）**：无网络（`--network none`）、非 root（`--user 1000:1000`）、能力全丢（`--cap-drop ALL`）、禁止提权（`no-new-privileges`）、根文件系统只读（`--read-only` + `/tmp` tmpfs）、PID 上限、内存硬限（memory-swap = memory）、**不挂 docker socket、不用 `--privileged`、不用 host 网络 / PID**、仅三段挂载（输入只读 / 输出可写 / 作业只读）。

### 4.3 作业执行命令

```
docker exec --user 1000:1000 ctds-sb-SB000001
  timeout -s KILL <jobTimeoutSeconds> python3 /opt/ctds/jobs/<script>.py /work/input /work/output/<产物>
```

- 作业命令来自配置清单（固定 argv），**不拼接任何用户输入**；请求只给 `jobId`。
- 退出码：`124` → `TIMEOUT`；`0` → `SUCCEEDED`；其余 → `FAILED`。
- Java 侧超时 = 作业超时 + 10s（兜底销毁 `docker exec` 进程；容器内 `timeout` 负责杀进程）。
- **作业 stdout/stderr 不落库、不落审计、不落日志**（可能含数据）；只记录退出码、耗时与状态。

### 4.4 停止与探活

- `stop`：`docker stop -t 5 ctds-sb-<no>`（`--rm` 自动移除）；失败再 `docker rm -f`；两者都失败 → 记 `1009S0005` 并留痕（状态仍置关闭，容器由对账/运维兜底）。
- `isRunning`：`docker inspect -f {{.State.Running}} ctds-sb-<no>`；容器不存在 → `false`。
- Windows 路径：统一用 `--mount type=bind,source=<绝对路径>`（单个 argv，规避 `E:\` 冒号与空格解析）；编码段前置检查含一次挂载冒烟。

## §5 工作区与数据注入

```
<workspace-root>/<sandboxNo>/input/<fileId>          # 只读挂载（文件内容来自 FileSourceRegistry）
<workspace-root>/<sandboxNo>/input/manifest.tsv      # fileId、展示名、字节数（无宿主机路径）
<workspace-root>/<sandboxNo>/output/                 # 可写挂载（作业产物）
```

1. **按产品取文件**：`FileSourceRegistry#listForProduct(productId)`（加性方法，复用既有启动校验与请求时 realpath 复核）。
2. **复制而非挂源**：逐文件 `Files.copy(..., NOFOLLOW_LINKS)`；目标名 = `fileId`（安全字符集），不采用用户或展示名作路径。
3. **上限**：文件数 ≤ `max-input-files`、总字节 ≤ `max-input-bytes`、单文件沿用 `ctds.delivery.file.max-bytes`（64MiB）；超限 → `1009S0004`（依赖/数据未就绪）。
4. **containment**：所有目标路径 `normalize()` 后必须 `startsWith(realWorkspace)`；符号链接一律拒绝。
5. **POSIX 权限**：支持 POSIX 时对 `output` 设 `rwxrwxrwx`（演示 Windows Docker Desktop 默认即可写；Linux 生产由部署侧 `chown 1000:1000`，登记边界）；不支持则跳过。
6. **结果元数据**：只列 `output` 顶层普通文件，文件名 `[A-Za-z0-9._-]{1,128}`；返回 `fileName / sizeBytes / checksum(SM3) / createdAt`；>64MiB 的文件 `checksum=null`（流式摘要登记演进）。**不提供内容读取**。
7. **清理边界**：关闭/到期**不删除工作区**（3.5.5 审核与导出需要产物）；工作区清理归 3.5.5 / 运维收口（登记债务候选）。

## §6 受控作业与策略 / 计量

### 6.1 执行流程

```
身份 → 参数（sandboxNo / jobId / 幂等键）
→ 取任务；非参与方 → 1009C0010（404 同码同文）；参与方但非需求方 → 1009C0003（403）
→ 状态 / 到期校验 → 1009C0011（409）
→ 作业清单校验 → 1009C0014（400）
→ 幂等查重（sandboxNo + key）→ 命中返回首次作业
→ [contract] check（USE，purpose/territory）→ 拒绝原码透传（1008C0020 等），零计数零作业记录
→ 取号 SJ → 插入 sandbox_job(RUNNING)
→ docker exec（超时）→ 更新 SUCCEEDED / FAILED / TIMEOUT + 退出码 + 耗时
→ 审计 JOB（成功/失败）→ 返回作业视图
```

- **一次作业 = 一次数据使用**：`check` 放行即计数 1 次；`check` 通过后平台侧失败（运行时异常）→ 作业 `FAILED` + 审计 FAILURE，**不改写已发生的计数**（沿 3.5.3 Q4 诚实边界）。
- **用途 / 地域**：请求可选；合约策略要求而缺失 → 由合约域 `1008C0020` 拒绝（不自行补默认值）。
- **计量**：`sandbox_job` 行 = 3.7.5 计量事件源；交付记录与合约用量流水分工不变，不双计。
- **作业清单**：配置白名单；无用户自定义命令 / 脚本 / 路径；自由编码与代码上传登记演进（lofi §1 诚实边界）。

### 6.2 幂等

- 开通：`@Idempotent(key = "#cmd.requesterNo + ':' + #cmd.idempotencyKey")` + DB `UNIQUE(requester_no, idempotency_key)`。
- 作业：`@Idempotent(key = "#cmd.sandboxNo + ':' + #cmd.idempotencyKey")` + DB `UNIQUE(sandbox_no, idempotency_key)`；重放返回首次作业，**不重复计数**。
- 与 3.5.3 同口径：memory 结果复用为单实例语义，DB 唯一键永久兜底。

## §7 端点、权限、错误码与出站映射

### 7.1 端点（资源名 `sandboxes`，沿规格 Q8-A）

| 方法 | 路径 | 权限 | 说明 |
| --- | --- | --- | --- |
| POST | `/api/v1/sandboxes` | `sandbox.request` | 申请开通（`X-Idempotency-Key` 必填）；body `{contractNo}` |
| GET | `/api/v1/sandboxes/{sandboxNo}` | `sandbox.read` | 参与者详情（配额 / 有效期 / 状态 / 数据文件数） |
| GET | `/api/v1/sandboxes` | `sandbox.read` | 参与者列表；`contractNo` / `status` / 分页 / `orderBy` 白名单 |
| POST | `/api/v1/sandboxes/{sandboxNo}/actions/close` | `sandbox.use` | 需求方主动关闭（幂等） |
| POST | `/api/v1/sandboxes/{sandboxNo}/actions/run-job` | `sandbox.use` | 受控作业（`X-Idempotency-Key` 必填）；body `{jobId, purpose?, territory?}` |
| GET | `/api/v1/sandboxes/{sandboxNo}/jobs` | `sandbox.read` | 作业历史（分页，参与者可见） |
| GET | `/api/v1/sandboxes/{sandboxNo}/results` | `sandbox.read` | 结果物**元数据**（无内容） |
| GET | `/api/v1/sandboxes/{sandboxNo}/data/**` | `sandbox.read` | **一律 403 + 留痕**（原始数据 / 产物内容都不可直取） |
| GET | `/api/v1/sandboxes/governance` | `sandbox.governance` | 治理列表（读即留痕） |
| GET | `/api/v1/sandboxes/governance/{sandboxNo}` | `sandbox.governance` | 治理详情（读即留痕） |

- `pageSize` ≤ 100（`common-pagination`）；`orderBy` 白名单：`id` / `createdAt` / `created_at` / `sandboxNo` / `sandbox_no` / `status`；非法字段 → `1000C0001`。
- 详情 / 列表**不返回**容器 ID、镜像名、宿主机路径、输入文件名（仅 `dataFileCount`）。
- 结果物元数据字段：`fileName / sizeBytes / checksum / createdAt`（SM3 小写 hex，`common-crypto` `Sm3Service`）。

### 7.2 错误码（1009 段顺延；0001~0004 / S0001 网关，0005~0009 / S0002~0003 交付）

| 码值 | 含义 | HTTP |
| --- | --- | --- |
| `1009C0003`（复用） | 非合约需求方不可发起 / 操作 | 403 |
| `1009C0005`（复用） | 交付域请求参数不合法（含沙箱参数） | 400 |
| `1009C0006`（复用） | 形态或范围与合约约定不符（G1-A：API 形态申请沙箱） | 400 |
| `1009C0010` | 沙箱任务不存在或无权查看（同码同文防枚举） | 404 |
| `1009C0011` | 沙箱任务当前状态不允许该操作（已到期 / 已关闭） | 409 |
| `1009C0012` | 原始数据不可直接导出 | 403 |
| `1009C0013` | 沙箱任务数量已达上限 | 429 |
| `1009C0014` | 作业不在允许清单内 | 400 |
| `1009S0004` | 沙箱运行环境暂不可用（运行时关闭 / 镜像缺失 / 数据未就绪） | 503 |
| `1009S0005` | 沙箱环境操作失败（启动 / 停止 / 执行异常） | 500 |

- 合约域 1008C 结论码原码透传（`1008C0012` 404 / `1008C0013` 409 / `1008C0020` 403 / `1008C0008` 400），文案取合约域。
- S 型文案为服务端常量，不含内部细节（镜像、路径、容器、命令）。
- `DeliveryExceptionHandler.MAPPED_CODES` 与 `statusOf` 同步登记；`DeliveryExceptionHandlerTest` 的码表↔处理器一致性断言更新。

## §8 留痕与字段集

### 8.1 动作

| action | 时机 | result |
| --- | --- | --- |
| `sandbox.create` | 开通成功 | SUCCESS |
| `sandbox.close` | 主动关闭 | SUCCESS |
| `sandbox.expire` | 到期回收 | SUCCESS |
| `sandbox.reconcile` | 启动对账纠偏 | SUCCESS / FAILURE |
| `sandbox.read` | 详情 / 列表 / 作业历史 / 结果元数据 | SUCCESS |
| `sandbox.job` | 作业执行 | SUCCESS / FAILURE |
| `sandbox.governance.read` | 治理查看（读即留痕） | SUCCESS |
| `sandbox.denied` | 各类拒绝（403/404/409/429/400） | DENIED + reasonCode |
| `sandbox.data.denied` | `/data/**` 直取拒绝 | DENIED + `1009C0012` |

### 8.2 字段集（DB + 审计 JSONL）

- 允许：`sandboxNo` / `jobNo` / `contractNo` / `operatorNo` / `action` / `result` / `reasonCode` / `status` / `jobId` / `purpose` / `territory` / `dataFileCount` / 时间。
- **禁止**：文件内容与原文、宿主机路径、容器 ID、容器名、镜像名、命令 argv、stdout/stderr、密钥材料、令牌。
- 审计事件：`AuditEvent.of(operatorNo, action, "sandbox", sandboxNo == null ? contractNo : sandboxNo, outcome, detail)`。

## §9 测试清单与红相

> 测试先行：先写失败用例并归档 `build-output/w354-red-*`，经确认后实现；用例只增不减；既有 delivery 56 例必须保持全绿。

### 9.1 单元 / 集成（Fake 运行时 + 共享 MySQL 容器，CI 主路径不依赖 Docker）

| 组 | 例数 | 锚点 |
| --- | --- | --- |
| 准入 | 12 | 生效/非生效/终止（1008C0013）、不存在/非参与方（1008C0012 同码同文）、提供方发起（1009C0003）、非需求方（1009C0003）、API 形态（1009C0006）、参数非法（1009C0005）、幂等重放 / 并发同键、无数据文件（1009S0004）、合约服务不可用（1009S0002）、单需求方 / 全局并发上限（1009C0013） |
| 生命周期 | 10 | 关闭 / 重复关闭、关闭后作业 1009C0011、到期扫描（条件更新、容器停止、留痕）、到期后作业 1009C0011、对账（容器缺失 → STOPPED/RECONCILED）、运行时关闭 → 503、启动失败清理（无记录、无工作区残留）、非需求方关闭 1009C0003、非参与方关闭 1009C0010 |
| 作业与策略 | 10 | 作业成功（状态 / 耗时 / 产物 / 使用留痕 / check 计数 +1）、未知作业 1009C0014（零计数零记录）、用途越界 1008C0020（零计数零记录）、合约不可用 1009S0002（无作业记录）、超时 TIMEOUT、非零退出 FAILED、幂等重放（不重复计数）、跨沙箱 1009C0010、结果元数据 SM3 与文件一致、结果列表过滤符号链接 / 非法名 |
| 数据面 | 4 | 输入路径 403（1009C0012 + 留痕）、输出路径 403、路径穿越 / URL 编码穿越不放行、元数据无内容字段 |
| 运行时 argv | 6 | 安全基线逐项（network none / cap-drop ALL / no-new-privileges / read-only / tmpfs / user / memory-swap / pids / 三段挂载只读可写）、无 privileged / socket / host 网络、容器名与 sandboxNo 正则、exec 超时包装且无 shell、镜像显式 tag、stop / inspect argv |
| 留痕与无本体 | 4 | 审计 JSONL 字段集（无路径 / 容器 / 镜像 / 命令）、DB 行字段集、响应字段集、治理读留痕 |
| 配置校验 | 3 | 配额越界启动失败、镜像 `latest` 启动失败、作业清单非法启动失败 |

**真 Docker 用例（默认跳过）**：`@EnabledIfEnvironmentVariable(named = "CTDS_SANDBOX_E2E", matches = "1")`，4 例（起容器 / 写输入只读 / 执行作业 / 网络不可达），走查段启用并留痕。

### 9.2 字段集断言

- 记录 / 审计 / 日志 / 响应四处取证：无输入文件内容、无 `workspace-root` 路径、无 `ctds-sb-`、无 `python:3.12`、无 argv。

## §10 编码段前置检查（八项，逐项实测留痕）

1. **仓库**：分支 `feat/WBS-3.5.4-沙箱环境管理`；`main == origin/main == d25e781`；工作区干净；基线提交 = 立卡批。
2. **Docker**：`docker info --format '{{.OSType}}'` = `linux`；`docker image inspect python:3.12-alpine` 存在；挂载冒烟（`--mount type=bind,source=<repo>/build-output/sandbox-smoke,target=/w,readonly` + `ls /w`）通过；**当前 daemon 未运行（2026-10-22 实测），须先拉起 Docker Desktop**。
3. **模块基线**：delivery **56**（1 跳过）/ contract **244** / catalog **182** / gateway **35** / 前端 **440**；checkstyle 0；实际值以本项实测为准。
4. **数据库**：MySQL 容器在线；`ctds_delivery` 存在；Flyway V1 已应用；V2 待本卡首建。
5. **门禁**：`gates-config.json` **V1.12 / ConfigSha256 `955bdbba…2ee2`** 逐字一致；本卡预计零改动。
6. **设计与审批**：lofi / hifi **V1.0** 确认记录在案（2026-10-22 21:25）；**G1-A 已由编排师确认**，规格澄清补丁待 **PO 批准**（变更流程）后落地，编码以 G1-A 口径为准。
7. **演示数据**：数据集合约（C-4.2 产出 / 演示库已有 CO000026 等）+ 对应产品已登记数据文件（`ctds.delivery.files`）；`workspace-root` / `jobs-root` 可写；演示 TTL 缩短档位（如 5 分钟）可配置。
8. **依赖面**：零新 Maven 依赖；`python:3.12-alpine` 已在 `docs/dependencies.md` 容器基础镜像节登记并获批（Q3-A）；SAST 基线实测（关注 `ProcessBuilder` 规则命中）。

## §11 真链走查判定表（API 级，Docker 环境）

| 组 | 腿数 | 判定点 |
| --- | --- | --- |
| A 准入 | 11 | 未认证 `1000C0002` / 权限层 `1000C0005` / 终止·完结 `1008C0013` / 非参与方与不存在 `1008C0012` 同码同文逐字 / 提供方发起 `1009C0003` / 非需求方 `1009C0003` / API 形态 `1009C0006` / 参数非法 `1009C0005` / 幂等重放同号零新增 / 并发上限 `1009C0013` / 合约服务不可用 `1009S0002` |
| B 开通与环境 | 7 | 容器按安全基线启动（`docker inspect` 逐项）、输入只读（容器内写失败）、输出可写、配额与有效期可查、提供方只读、治理查看 + 留痕、非参与方 404 |
| C 作业与策略 | 7 | 作业成功 + 产物 + 使用留痕 + 合约计数 +1；未知作业 `1009C0014`；用途越界 `1008C0020` 零计数零记录；超时 `TIMEOUT`；非零退出 `FAILED`；幂等重放不重复计数；跨沙箱 `1009C0010` |
| D 数据面 | 4 | `/data/input/**` 与 `/data/output/**` 均 403 + 留痕；穿越编码不放行；结果元数据无内容 |
| E 生命周期 | 5 | 主动关闭 → STOPPED 且容器消失；关闭后作业 `1009C0011`；短 TTL 到期 → EXPIRED 且容器消失；到期后作业 `1009C0011`；重启服务对账一致 |
| F 隔离 | 4 | 容器内 `ping` / `wget` 失败（无网络）；无 docker socket；看不到其他沙箱工作区；只读输入不可写 |
| G 无本体 | 4 | DB / 审计 JSONL / app.log / 响应四处取证零命中（内容、路径、容器、镜像、命令） |

**对账**：作业数 ↔ 合约 `check` 计数增量 ↔ ALLOWED 流水；拒绝零增量；到期/关闭不产生计数。

## §12 边界、风险与演进

| 项 | 说明 / 处置 |
| --- | --- |
| Docker daemon 未运行 | 2026-10-22 实测；编码段前置检查第一动作 = 拉起 Docker Desktop；CI 主路径不依赖 Docker |
| `ProcessBuilder` SAST 命中 | 先按“argv 数组 + 配置白名单 + 无 shell”写法降风险；若仍命中，按证据化豁免流程报编排师，**不擅自改门禁配置** |
| Windows 挂载路径 / 权限 | 统一 `--mount type=bind`；前置检查含挂载冒烟；Linux 生产需 `chown 1000:1000`（登记部署边界） |
| 非 root uid 写输出 | 演示 Windows Docker Desktop 默认可写；Linux 由部署侧保证；代码在 POSIX 下尽力设权限 |
| 并发配额竞态 | count → insert 非严格串行，极端并发可能短暂超限；单实例演示可接受，登记观察项（多实例换锁 + 选主） |
| 多实例扫描 | 定时回收为单实例语义；多实例需选主/分布式锁（common-lock 可承接），登记演进 |
| 产物磁盘增长 | 关闭/到期不删工作区（3.5.5 需要）；清理归 3.5.5 / 运维，登记债务候选 |
| 生产 K8s 编排 | 本卡仅单机 Docker；k8s 不注入 socket、运行时默认关闭（503）；K8s Job 编排归部署演进（登记） |
| 自由编码 / 代码上传 | V1.0 仅受控作业清单；自由开发工作台归 3.5.6 / 后续（诚实边界，剧本不承诺） |
| 镜像拉取 | 演示前置 `docker pull python:3.12-alpine`；网络受限时按 `dependencies.md` 拉取通道留痕（不入库） |
| 容器残留 | `--rm` + 停止兜底 + 启动对账；宿主重启后由对账纠偏 |
| 时间一致性 | 到期判定以服务端 `Clock` 为准；容器内不承担配额判定 |

## §13 复用声明 / 加性改动 / 规格外实现声明

**复用（只读）**：`common-auth`（权限点 / AuthContext）、`common-errorcode`、`common-pagination`、`common-idempotency`、`common-logging`（AuditRecorder）、`common-crypto`（SM3）；delivery-service 既有 `ContractInternalClient` / `CatalogInternalClient` / `FileSourceRegistry` / `DeliveryExceptionHandler` / `DeliveryBeanConfig(Clock)`；测试支撑 `StubDependencyServer` / `SharedMySqlContainer`。

**加性改动（不改既有语义）**：`FileSourceRegistry#listForProduct`；`DeliveryExceptionHandler` 码表与 HTTP 映射新增项；`DeliveryApplication` 增 `@EnableScheduling`；`application.yml` 配置与权限点；Flyway V2；k8s delivery 部署增沙箱环境变量（运行时默认关闭）；ADR-024；`dependencies.md` 容器基础镜像 1 行（Q3-A）。

**规格外实现声明**：**空**。受控作业是规格行为 5“沙箱内使用”的**接口承载**（规格 §4-1 明确数据模型与端点归 3.5.2~3.5.5），不新增业务行为；G1（产品形态）按变更流程处理。

---

## 确认记录（V0.9 → V1.0）

> **✅ 已签署（2026-10-22 21:25，编排师会话回复“全部按建议”）**：Q1~Q9 全采建议 A + G1-A + D1 不拆分；本文与 `docs/designs/WBS-3.5.4-lofi.md` 同批转 **V1.0（编码契约即时生效）**。编码段新会话冷启动，第一动作 = §10 八项前置检查逐项实测留痕，测试先行。**G1-A 的规格澄清补丁（仅 DATASET/REPORT/MODEL）待 PO 批准后由规格变更流程落地；本卡实施以 G1-A 口径为准，不擅自改规格。**
