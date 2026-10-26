# WBS-3.6.3 高保真设计：连接器注册与核验服务（V1.1，2026-10-26）

| 字段 | 内容 |
| --- | --- |
| 版本 | **V1.1（编码段勘正，2026-10-26）**——V1.0 = 2026-10-26 编排师一次确认（Q1~Q8 全采 A + D1 不拆分）的编码契约；编码段按实现就地勘正 4 处（§1 依赖去 `common-idempotency`、§1 角色补 `connector-internal`、§4 `registeredAt` → `updatedAt`、§5 列表不查 DID 状态），业务判定语义零变更；与 lofi 一并提交、一次确认（章程 2.6.3） |
| 契约来源 | lofi Q1~Q8 建议口径 A + D1 不拆分；规格 `docs/specs/C-6.1-6.3-接入连接器.md` V1.0 行为 1 / 6 / 7 / 8 + §7 Q2-A / Q4-A / Q5-A / Q8-A / Q9-A |
| 交付物 | ① 新服务 `services/connector-service`；② 迁移 `V1__create_connector_tables.sql`；③ 跨域装配两处（subject yml 1 行 / did 新增内部端点 + yml 1 行）；④ **ADR-027 连接器注册与核验服务契约**（编码段落盘）；⑤ 测试（测试先行） |

---

## §1 模块装配契约（Q1-A）

**目录**：`services/connector-service/`（与 9 个既有服务同级），`pom.xml`：

- `parent` = `com.ctds:ctds-platform:2.0.0-SNAPSHOT`，`<relativePath>../../pom.xml</relativePath>`
- `artifactId` = `connector-service`，`packaging` = jar，`description` = 连接器注册与核验服务（WBS 3.6.3）
- 依赖（全部既有坐标）：`common-errorcode` / `common-auth` / `common-pagination` / `common-logging` / `spring-boot-starter-web` / `spring-boot-starter-validation` / `spring-boot-starter-jdbc` / `flyway-core` / `flyway-mysql`(runtime) / `mysql-connector-j`(runtime) / 测试三件（`spring-boot-starter-test` / `testcontainers:junit-jupiter` / `testcontainers:mysql`）——**编码段勘正**：不引 `common-idempotency`（本卡无幂等注解使用点，申请防重由活跃态唯一索引 + `1010C0002` 天然承载；ADR-027 §4 登记）
- 插件：`spring-boot-maven-plugin`（与 delivery 同）+ `maven-checkstyle-plugin` **显式声明**；**不声明 jacoco**（沿 9 服务惯例，覆盖率由门禁脚本挂 agent）
- 根 `pom.xml`：`<modules>` 增 1 行 `<module>services/connector-service</module>`（位置在 delivery-service 之后）；**不新增 dependencyManagement 条目**（无跨模块消费需求，内部面为 HTTP）

**`src/main/resources/application.yml`**（默认 profile 排除 DataSourceAutoConfiguration）：

```yaml
server:
  port: 8088
  address: 127.0.0.1          # ADR-016 §2.7 回环边界
ctds:
  auth:
    permissions:
      applicant: connector.register,connector.read,connector.heartbeat
      admin: connector.review,connector.read,connector.internal.read
      connector-internal: connector.internal.read   # 编码段勘正：本卡即落该服务身份角色（T6 / T7 可测 200）
  connector:
    subject:
      base-url: ${CTDS_CONNECTOR_SUBJECT_BASEURL:}
      timeout: 3s
    did:
      base-url: ${CTDS_CONNECTOR_DID_BASEURL:}
      timeout: 3s
    health:
      offline-after: 5m        # 1m~1440m 启动校验（越界 fail-fast）
```

**`application-mysql.yml`**：`spring.datasource.url: ${CTDS_DB_URL:jdbc:mysql://localhost:3306/ctds_connector}`；口令仅环境变量；`spring.flyway.{enabled,locations: classpath:db/migration,out-of-order:false,baseline-on-migrate:false}`（与 delivery 同形）。

## §2 库表结构（`V1__create_connector_tables.sql`，库 `ctds_connector`）

**`connector_registration`**（注册申请 / 连接器档案，一表两阶段：待核验 → 已注册）

| 列 | 类型 | 说明 |
| --- | --- | --- |
| `id` | BIGINT AUTO_INCREMENT | 技术主键 |
| `application_no` | VARCHAR(16) NOT NULL | **CA + 6 位**申请单号（登记即签发） |
| `connector_no` | VARCHAR(16) NULL | **CN + 6 位**连接器编号（核验通过事务内签发；之前为 NULL） |
| `subject_no` | VARCHAR(32) NOT NULL | 所属主体（申请者身份，服务端取） |
| `connector_name` | VARCHAR(64) NOT NULL | 名称 |
| `active_name_norm` | VARCHAR(64) **GENERATED ALWAYS AS (IF(`status` = 'CANCELLED', NULL, LOWER(TRIM(`connector_name`)))) STORED** | 活跃态归一化名称（注销后可同名重注册，行为 10 验收 4 前瞻） |
| `connector_type` | VARCHAR(16) NOT NULL | `STANDARD`（V1.0 唯一） |
| `connector_version` | VARCHAR(16) NOT NULL | `主.次.修订` |
| `status` | VARCHAR(16) NOT NULL DEFAULT 'PENDING_REVIEW' | `PENDING_REVIEW` / `REGISTERED` / `CANCELLED`（**CANCELLED 仅预留，3.6.4 实现**） |
| `last_rejection_code` | VARCHAR(16) NULL | 最近一次核验拒绝码（`1010C0006` / `1010C0007` / `1010C0005`） |
| `last_rejection_reason` | VARCHAR(256) NULL | 最近一次核验拒绝理由（服务端常量文案） |
| `last_heartbeat_at` | DATETIME NULL | 最近心跳（Q8-A 最小健康度） |
| `created_at` / `updated_at` | DATETIME | 审计字段（`updated_at` 带 `ON UPDATE CURRENT_TIMESTAMP`） |

索引：`UNIQUE uk_application_no(application_no)` / `UNIQUE uk_connector_no(connector_no)` / **`UNIQUE uk_subject_active_name(subject_no, active_name_norm)`** / `KEY idx_status_created(status, created_at)` / `KEY idx_subject_status(subject_no, status)`。

**`connector_did_binding`**（行为 8）

| 列 | 类型 | 说明 |
| --- | --- | --- |
| `id` | BIGINT | 技术主键 |
| `connector_no` | VARCHAR(16) NOT NULL | 连接器编号 |
| `subject_no` | VARCHAR(32) NOT NULL | 所属主体 |
| `did` | VARCHAR(128) NOT NULL | 所属主体 DID 快照（**非秘密材料**） |
| `status` | VARCHAR(16) NOT NULL | `ACTIVE` / `RELEASED`（RELEASED 归 3.6.4 注销） |
| `bound_at` | DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP | 建立时间 |
| `released_at` | DATETIME NULL | 解除时间（3.6.4） |

索引：`UNIQUE uk_connector_binding(connector_no)` / `KEY idx_binding_did(did)`。**不冗余 DID 状态**（Q6-A：实时查 DID 域）。

**`connector_action_log`**（域内留痕；只插不改，无 `updated_at`）

| 列 | 类型 | 说明 |
| --- | --- | --- |
| `id` | BIGINT | 技术主键 |
| `application_no` / `connector_no` | VARCHAR(16) NULL | 对象（拒绝类动作可只有申请单号） |
| `operator_no` | VARCHAR(32) NOT NULL | 谁（申请人 / 核验人 / 属主） |
| `action` | VARCHAR(64) NOT NULL | `connector.apply` / `connector.amend` / `connector.verify.approved` / `connector.verify.rejected` / `connector.binding.read` / `connector.heartbeat` / `connector.denied` |
| `result` | VARCHAR(16) NOT NULL | `SUCCESS` / `DENIED` / `FAILURE` |
| `reason_code` | VARCHAR(16) NULL | 拒绝码 / 失败码 |
| `detail` | VARCHAR(256) NULL | 业务要素摘要（名称/类型/版本/绑定 DID/结论/旧值），**不含任何秘密材料** |
| `created_at` | DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP | 何时 |

索引：`KEY idx_log_application(application_no, created_at)` / `KEY idx_log_connector(connector_no, created_at)`。

**`connector_application_no_seq` / `connector_no_seq`**（各单行取号表，沿 `delivery_no_seq` 先例）：

```sql
CREATE TABLE connector_no_seq (
    id BIGINT NOT NULL, next_no BIGINT NOT NULL, PRIMARY KEY (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '连接器编号全局序号（1 行原子取号）';
INSERT INTO connector_no_seq (id, next_no) VALUES (1, 1);
```

取号语句（`@Transactional`）：`UPDATE connector_no_seq SET next_no = LAST_INSERT_ID(next_no + 1) WHERE id = 1` → `SELECT LAST_INSERT_ID()` → `String.format("CN%06d", n)`；申请单号同构（`CA%06d`）。

**表注释与列注释全覆盖**；跨库引用只逻辑引用不建外键（沿 ADR-009 / contract 先例）。

## §3 包结构与类清单

```
com.ctds.connector
├── ConnectorApplication.java            # @SpringBootApplication + @ConfigurationPropertiesScan
├── config/ConnectorProperties.java      # subject.base-url/timeout、did.base-url/timeout、health.offline-after（校验 1m~1440m）
├── interfaces/
│   ├── ConnectorRegistrationController.java   # 申请 / 补正 / 我的申请 / 单申请 / 核验队列 / 核验动作
│   ├── ConnectorController.java               # 已注册连接器：我的列表 / 详情 / 绑定查验 / 留痕 / 心跳
│   ├── InternalConnectorController.java       # 内部面：单连接器 + 绑定 + 实时 DID 状态
│   ├── ConnectorExceptionHandler.java         # 1010 段与本域 BizException → ApiResult 映射
│   └── dto/{ConnectorRequests.java, ConnectorViews.java}
├── application/
│   ├── ConnectorRegistrationService.java      # 申请 / 补正（要素校验 + 主体资格前置 + 防重 + 留痕）
│   ├── ConnectorVerificationService.java      # 核验三查 + 通过（签发 + 绑定，同一事务）+ 拒绝留痕
│   ├── ConnectorQueryService.java             # 我的申请 / 单申请 / 队列 / 连接器详情 / 我的连接器 / 留痕
│   ├── ConnectorBindingService.java           # 绑定查验（实时 DID 状态）+ 内部面读
│   └── ConnectorHealthService.java            # 心跳上报 + online 派生
├── domain/
│   ├── ConnectorRegistration.java, ConnectorBinding.java, ConnectorActionLog.java   # record
│   ├── ConnectorStatus.java (PENDING_REVIEW/REGISTERED/CANCELLED)、ConnectorType.java (STANDARD)、BindingStatus.java (ACTIVE/RELEASED)
│   ├── ConnectorRepository.java, ConnectorBindingRepository.java, ConnectorLogRepository.java, ConnectorSeqRepository.java
│   ├── SubjectAdmissionPort.java (ADMITTED/NOT_ADMITTED/UNAVAILABLE) + DidStatusPort.java (ACTIVE/REVOKED/PENDING_ISSUE/NONE/UNAVAILABLE)
│   ├── ConnectorErrorCodes.java, ConnectorBizException.java, VerificationCheck.java (SUBJECT_ADMISSION/DID_STATUS/COMPLETENESS)
└── infrastructure/
    ├── JdbcConnectorRepository.java, JdbcConnectorBindingRepository.java, JdbcConnectorLogRepository.java, JdbcConnectorSeqRepository.java
    ├── SubjectAdmissionClient.java, DidStatusClient.java     # JDK HttpClient，零新依赖（沿 catalog/delivery 先例）
    └── ConnectorAuditService.java + ConnectorAuditActions.java   # JSONL 审计 + DB 留痕双写（沿 delivery）
```

分层沿用既有四层（`interfaces → application → domain → infrastructure`）；domain **不依赖** common-pagination（分页在 interfaces 层用 `PageQuery`；db 查以 offset/limit 表达）。

## §4 关键接口签名（record 模型）

```java
// 请求（interfaces/dto）
record ConnectorRegistrationRequest(
    @NotBlank @Size(min = 2, max = 64) String connectorName,
    @NotBlank String connectorType,          // 仅 STANDARD
    @NotBlank @Pattern(regexp = "^\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}$") String connectorVersion) {}

record ConnectorAmendRequest(同上三字段) {}

// 应用层命令
record ConnectorRegistrationCommand(String subjectNo, String name, String type, String version) {}
record ConnectorAmendCommand(String subjectNo, String applicationNo, String name, String type, String version) {}
record VerificationCommand(String operatorNo, String applicationNo) {}

// 视图（interfaces/dto）
record RegistrationView(String applicationNo, String connectorNo, String subjectNo, String connectorName,
    String connectorType, String connectorVersion, String status, String lastRejectionCode,
    String lastRejectionReason, LocalDateTime createdAt, LocalDateTime updatedAt) {}

record ConnectorView(String connectorNo, String subjectNo, String connectorName, String connectorType,
    String connectorVersion, String status, String did, String didStatus, boolean verified,
    boolean online, LocalDateTime lastHeartbeatAt, LocalDateTime updatedAt) {}   // 编码段勘正：registeredAt → updatedAt（表无独立注册时间列）

record BindingView(String connectorNo, String subjectNo, String did, String bindingStatus,
    LocalDateTime boundAt, String didStatus, boolean verified) {}

record VerificationResultView(String applicationNo, boolean verified, String failedCheck, String reasonCode,
    String reason, String connectorNo, String status, BindingView binding) {}

record ActionLogView(String applicationNo, String connectorNo, String operatorNo, String action,
    String result, String reasonCode, String detail, LocalDateTime createdAt) {}
```

**端口（domain）**：`SubjectAdmissionPort.check(subjectNo)` → `ADMITTED / NOT_ADMITTED / UNAVAILABLE`（`1000C0003` 主体不存在 → NOT_ADMITTED，与未入驻同文防枚举）；`DidStatusPort.current(subjectNo)` → `record CurrentDid(String did, String status)`，`status ∈ ACTIVE/REVOKED/PENDING_ISSUE/NONE`，任何异常 → `UNAVAILABLE`。

## §5 端点契约表（12 个：对外 11 + 内部 1；权限点见 §6）

| # | 方法 | 路径 | 权限 | 请求 / 响应 | 关键语义 |
| --- | --- | --- | --- | --- | --- |
| 1 | POST | `/api/v1/connectors/registrations` | `connector.register` | body=三要素 → `RegistrationView` | 身份取 `AuthContext.subject()`；主体资格前置（NOT_ADMITTED → `1010C0006`；UNAVAILABLE → `1010S0001`）；同名活跃档案 → `1010C0002`；签发 `CA%06d`；留痕 `connector.apply` |
| 2 | PUT | `/api/v1/connectors/registrations/{applicationNo}` | `connector.register` | body=三要素 → `RegistrationView` | 仅属主 + 仅 `PENDING_REVIEW`；非属主/不存在 → `1010C0003` 同码同文；已注册 → `1010C0004`；留痕 `connector.amend`（detail 含旧值） |
| 3 | GET | `/api/v1/connectors/registrations/mine` | `connector.read` | `?pageNum&pageSize` → `PageResult<RegistrationView>` | 按 `AuthContext.subject()` 过滤，申请时间倒序 |
| 4 | GET | `/api/v1/connectors/registrations/{applicationNo}` | `connector.read` | → `RegistrationView` | 属主或 `admin`（`connector.review` 持有者）可读；否则 `1010C0003` 同码同文 |
| 5 | GET | `/api/v1/connectors/review/queue` | `connector.review` | `?pageNum&pageSize` → `PageResult<RegistrationView>` | 固定 `PENDING_REVIEW`，申请时间升序 |
| 6 | POST | `/api/v1/connectors/registrations/{applicationNo}/verification` | `connector.review` | → `VerificationResultView` | **核验三查**（顺序：主体资格 → DID → 信息完整）；全过 = 状态转 `REGISTERED` + 签发 `CN` + 建 ACTIVE 绑定（**同一事务**）+ 留痕 `connector.verify.approved`；任一不过 = 200 + `verified=false` + `failedCheck`，状态不变 + 留痕 `connector.verify.rejected`；非 `PENDING_REVIEW` → `1010C0004` |
| 7 | GET | `/api/v1/connectors/mine` | `connector.read` | `?pageNum&pageSize` → `PageResult<ConnectorView>` | 本主体已注册连接器**摘要**（`did` / `didStatus` 不查——**编码段勘正**：列表逐条查 DID 会形成 N 次跨域调用；实时状态由 #8 / #9 / #12 承载，列表保留 `online` 派生） |
| 8 | GET | `/api/v1/connectors/{connectorNo}` | `connector.read` | → `ConnectorView` | 属主或 `admin`；否则 `1010C0003` 同码同文；路径变量正则 `^CN\d{6}$` |
| 9 | GET | `/api/v1/connectors/{connectorNo}/binding` | `connector.read` | → `BindingView` | **实时**查 DID 状态（不缓存）：`verified = (didStatus == ACTIVE)`；DID 不可达 → `1010S0002`；留痕 `connector.binding.read`（读取留痕，沿行为 8 可追溯） |
| 10 | GET | `/api/v1/connectors/{connectorNo}/action-logs` | `connector.read` | `?pageNum&pageSize` → `PageResult<ActionLogView>` | 属主或 `admin`；读取零新增留痕（防递归） |
| 11 | POST | `/api/v1/connectors/{connectorNo}/heartbeats` | `connector.heartbeat` | 空 body → `ConnectorView` | 仅属主（否则 `1010C0003`）+ 仅 `REGISTERED`（否则 `1010C0004`）；刷新 `last_heartbeat_at` |
| 12 | GET | `/api/v1/connectors/internal/connectors/{connectorNo}` | `connector.internal.read` | → `ConnectorView` | 内部面（供 3.6.4 / 3.6.7 / 3.6.8 与 SDK 校验）：连接器 + 绑定 + 实时 DID 状态；不存在 → `1010C0003` |

> **路径顺序**：`/registrations/mine` 与 `/mine` 为字面量路径，Spring 优先于 `/{connectorNo}` 模板；`{connectorNo}` / `{applicationNo}` 一律按 `^CN\d{6}$` / `^CA\d{6}$` 校验，非法 → `1010C0001`（不把非法值带进 SQL）。

> 统一响应 `ApiResult<T>`（`code="0"` / `data`）；分页 `PageResult<T>`；参数越界 → `1000C0001`（common-pagination 口径）。

## §6 错误码表（1010 段落定，Q9-A 兑现）

| 码 | 常量 | 语义 | HTTP |
| --- | --- | --- | --- |
| `1010C0001` | `CONNECTOR_PARAM_INVALID` | 参数不合法（名称/类型/版本/编号格式/分页外） | 400 |
| `1010C0002` | `CONNECTOR_NAME_DUPLICATED` | 同主体同名连接器已存在（活跃档案） | 400 |
| `1010C0003` | `CONNECTOR_NOT_FOUND_OR_DENIED` | 未找到该记录或无权访问（**防枚举同码同文**） | 400 |
| `1010C0004` | `CONNECTOR_STATE_NOT_ALLOWED` | 当前状态不允许该操作（补正/核验/心跳的状态门槛） | 400 |
| `1010C0005` | `CONNECTOR_INFO_INCOMPLETE` | 核验第三查不通过（要素不完整） | 200（结论视图 reasonCode） |
| `1010C0006` | `CONNECTOR_SUBJECT_NOT_ADMITTED` | 主体资格不满足（未入驻/非 ADMITTED，防枚举同文） | 400（申请）/ 200 结论（核验） |
| `1010C0007` | `CONNECTOR_DID_NOT_VALID` | 主体 DID 非 ACTIVE（REVOKED / PENDING_ISSUE / NONE） | 200（结论视图 reasonCode） |
| `1010S0001` | `CONNECTOR_SUBJECT_UNAVAILABLE` | 主体服务不可用（不可达/超时/非 200/不可解析） | 503 |
| `1010S0002` | `CONNECTOR_DID_UNAVAILABLE` | DID 服务不可用 | 503 |
| `1010S0003` | `CONNECTOR_INTERNAL_ERROR` | 内部错误（DB / 事务 / 不可分类异常） | 500 |

**不设 B 型码**（Q8-A 理由：核验拒绝 = 200 业务结论）；复用 `1000C0001`（参数）/ `1000C0002`（未认证）/ `1000C0005`（无权限）/ `1000C0003`（主体不存在）。**占用留痕写 ADR-027 §2**。

## §7 状态机与关键流程

**状态机（本卡实现前两态；CANCELLED 仅建表预留）**

```
[申请] --POST /registrations--> PENDING_REVIEW
PENDING_REVIEW --PUT 补正--> PENDING_REVIEW（要素更新，留痕）
PENDING_REVIEW --POST /verification (三查全过)--> REGISTERED（签发 CN + 建 ACTIVE 绑定）
PENDING_REVIEW --POST /verification (任一查不过)--> PENDING_REVIEW（记录 last_rejection_*，留痕）
PENDING_REVIEW --注销--> CANCELLED（3.6.4，本卡不实现）
REGISTERED --注销--> CANCELLED（3.6.4，本卡不实现）
```

**核验三查（`ConnectorVerificationService`，单一事务）**

1. 读申请（`PENDING_REVIEW` 门槛，否则 `1010C0004`）；
2. **查一 主体资格**：`SubjectAdmissionPort.check(subjectNo)`；`UNAVAILABLE` → **503 `1010S0001`**（不是结论拒绝——系统不可用不得冒充业务结论）；`NOT_ADMITTED` → 结论拒绝（`failedCheck=SUBJECT_ADMISSION`, `reasonCode=1010C0006`）；
3. **查二 DID**：`DidStatusPort.current(subjectNo)`；`UNAVAILABLE` → **503 `1010S0002`**；`status != ACTIVE` → 结论拒绝（`failedCheck=DID_STATUS`, `reasonCode=1010C0007`，`reason` 区分「DID 已吊销 / 尚未生效 / 尚未签发」）；
4. **查三 信息完整**：名称/类型/版本按 §5 规则重校验（防历史脏数据）→ 不通过 `failedCheck=COMPLETENESS`, `reasonCode=1010C0005`；
5. 全过：`ConnectorSeqRepository.nextConnectorNo()`（`CN%06d`）→ 更新 `status=REGISTERED, connector_no` → 插入 `connector_did_binding(status=ACTIVE)` → 写 `connector_action_log(connector.verify.approved)` → 返回 `verified=true` + 绑定视图；
6. 结论拒绝：更新 `last_rejection_code/reason` → 写留痕（`result=DENIED`）→ 返回 `verified=false`（**HTTP 200**）。

**绑定查验（fail-closed）**

`GET /{connectorNo}/binding` → 读绑定（`ACTIVE`）→ **实时** `DidStatusPort.current(subjectNo)` → 返回 `didStatus` 与 `verified = (didStatus == ACTIVE)`；DID 不可达 → 503 `1010S0002`（**不得**用库里 DID 快照冒充有效）。

**防枚举同码同文**：`1010C0003` 文案统一为「未找到该记录或无权访问」，非属主 / 不存在 / 已注销（3.6.4 后）一律同码同文。

## §8 跨域装配改动（仅两处，零业务语义改动）

**① subject-service `application.yml` 加 1 行**（`ctds.auth.permissions` 下）：

```yaml
      connector-internal: subject.internal.read
```

（沿 `catalog-internal` / `contract-internal` / `did-internal` 同款；端点 `GET /api/v1/subject/internal/subjects/{subjectNo}/admission` 复用，**零代码改动**）

**② did 服务新增内部只读端点**（WBS-3.6.3 Q4-A）：

- 新类 `services/did/src/main/java/com/ctds/did/interfaces/InternalCurrentDidController.java`：
  - `GET /api/v1/did/internal/subjects/{subjectNo}/current` + `@RequirePermission("did.internal.read")`
  - 返回 `ApiResult<CurrentDidView>{subjectNo, did, status}`；`status ∈ ACTIVE / REVOKED / PENDING_ISSUE / NONE`
  - 语义：取该主体**最新非吊销行**（`DidRepository.findActiveOrPending`）；无则取最新已吊销行返回 `REVOKED`；完全无记录 → `{did: null, status: "NONE"}`（**不新增错误码**）
  - 路径 4 段，与既有 `GET /api/v1/did/{did}`（单段模板）不冲突（§11-6 实测）
- `services/did/src/main/resources/application.yml` 加 1 行：`connector-internal: did.internal.read`
- **ADR-017 补记**：内部面新增（最小暴露：`subjectNo/did/status` 三字段）、权限点、下游消费方 = 3.6.3

**明确不动**：网关与路由（零改动）、既有端点语义（零改动）、其它服务（零改动）、门禁配置（零改动）。

## §9 测试清单（测试先行；映射规格验收标准）

| # | 测试类 | 覆盖（规格条目 → 用例） |
| --- | --- | --- |
| T1 | `ConnectorRegistrationApiTest` | 行为 1 验收 1/2/3/4 + 行为 6 验收 1/2/3：申请受理（`PENDING_REVIEW` + `CA` 前缀）/ 未入驻拒绝 `1010C0006` / 同名重复 `1010C0002` 且计数不变 / 缺要素与非法版本 `1010C0001` / 名称超长 `1010C0001` / 主体服务不可达 503 `1010S0001` / 非属主查申请 `1010C0003`（同码同文）/ 补正成功留痕 / 已注册态补正 `1010C0004` |
| T2 | `ConnectorVerificationApiTest` | 行为 6 验收 4 + 行为 7 验收 1~4：三查全过 → `REGISTERED` + `CN` + 绑定 ACTIVE / 未入驻 → `verified=false` + `SUBJECT_ADMISSION` / DID `REVOKED` / `PENDING_ISSUE` / `NONE` → `DID_STATUS` / DID 不可达 503 `1010S0002` / 已注册再核验 `1010C0004` / **编号唯一不复用**（连续两次核验拿到不同 `CN`）/ 补正后重新核验通过（留痕链完整）|
| T3 | `ConnectorBindingApiTest` | 行为 8 验收 1/2/3：绑定可查（connector ↔ DID ↔ boundAt）/ 实时联动（建绑后把桩 DID 改成 `REVOKED` → `verified=false` + `didStatus=REVOKED`）/ DID 不可达 503 `1010S0002` |
| T4 | `ConnectorHealthApiTest` | Q8-A 最小健康度：心跳 → `online=true` + `lastHeartbeatAt` / 非属主心跳 `1010C0003` / 待核验态心跳 `1010C0004` / 超阈值（配置 1m + 回拨时间）→ `online=false` / 配置越界（0m / 2000m）启动 fail-fast |
| T5 | `ConnectorQueryApiTest` | 行为 8 验收 1 + 防枚举：我的申请分页 / 我的连接器分页 / 详情（属主 200、他人 `1010C0003`、admin 200）/ 留痕分页（含申请+核验+绑定三动作）/ 分页越界 `1000C0001` / 路径变量非法（`CN12` / `CN1234567`）`1010C0001` |
| T6 | `ConnectorInternalApiTest` | 内部面：`connector-internal` 角色 200（含 `didStatus` 实时）/ 业务角色 403 `1000C0005` / 无身份 401 `1000C0002` / 不存在 `1010C0003` |
| T7 | `InternalCurrentDidApiTest`（**did 模块内**） | 新增端点：`ACTIVE` / `REVOKED` / `PENDING_ISSUE` / 无记录 `NONE` / `connector-internal` 200 / 未持权限 403 / 无身份 401 |
| T8 | `ConnectorAuditIntegrationTest` | 留痕：申请 / 补正 / 核验通过 / 核验拒绝 / 绑定查验五类动作落 `connector_action_log` + JSONL 审计；`detail` **不含秘密材料**（断言无密钥/口令字段）|

**测试基础设施**：Testcontainers MySQL（沿 delivery 先例）；跨域客户端以 JDK `HttpServer` 桩（沿 delivery `ContractInternalClient` 测试形态）；时钟相关用例以「回拨 `last_heartbeat_at`」实现，**不引入可注入时钟抽象**（避免规格外设计）。

## §10 ADR-027 大纲（编码段落盘，七字段齐备 = 门禁 `adrFieldsCheck`）

`docs/adr/ADR-027-连接器注册与核验服务契约.md`：§1 背景（C-6.2 注册核验落地 + 上游 3.6.1/3.6.2）→ §2 决策（服务落位 8088 / 编号双轨 CA+CN / 三查数据来源与两处装配 / 绑定实时联动 / **1010 段占用表** / 健康度读时派生 / 权限点 5 个）→ §3 理由 → §4 备选（lofi Q1~Q8 的 B/C 路径与不采理由）→ §5 业务影响说明 → §6 影响范围 → §7 可替换性（可换：编号前缀/健康度阈值/内部端点形态；有意不可换：身份锚 = 主体 DID、绑定与 DID 状态实时联动、1010 段位）。

## §11 前置检查项（编码段第一动作，逐项实测留痕）

1. `services/connector-service` 目录与 `parent/relativePath` 与既有 9 服务 pom 逐项比对（父坐标 `2.0.0-SNAPSHOT`、relativePath `../../pom.xml`）；
2. **端口 8088 / 库名 `ctds_connector` / 编号前缀 `CA` `CN` 全仓零冲突**（Java + SQL + docs 检索）；
3. **MySQL 生成列 + 唯一索引 NULL 语义实测**（`IF(status='CANCELLED', NULL, LOWER(TRIM(name)))` 在 Testcontainers MySQL 8 建表 + 两条 CANCELLED 同名插入成功）；
4. `LAST_INSERT_ID` 取号并发行为沿用既有（`delivery_no_seq` 同语句实测）；
5. subject `application.yml` 现状行定位（追加 `connector-internal` 不破坏既有映射）；
6. did 新路径 `/api/v1/did/internal/subjects/{subjectNo}/current` 与既有 `GET /api/v1/did/{did}` 模板**不冲突**（Spring 匹配实测）；
7. `common` 六组件坐标与版本（根 pom `dependencyManagement`）+ common-logging 审计写法（沿 delivery `DeliveryAuditService`）；
8. 台账沿承三件确认不变（DB-64 留置 / F-W1 / 8086 停用）。

## §12 配置项清单

| 配置 | 默认 | 说明 |
| --- | --- | --- |
| `server.port` | 8088 | 回环绑定 `127.0.0.1` |
| `ctds.connector.subject.base-url` | `${CTDS_CONNECTOR_SUBJECT_BASEURL:}` | 未配置 = 资格判定不可用（服务可独立启动；相关动作 503） |
| `ctds.connector.subject.timeout` | `3s` | 读超时（连接 2s，沿既有客户端） |
| `ctds.connector.did.base-url` | `${CTDS_CONNECTOR_DID_BASEURL:}` | 未配置 = DID 判定不可用（相关动作 503） |
| `ctds.connector.did.timeout` | `3s` | 同上 |
| `ctds.connector.health.offline-after` | `5m` | 1m~1440m；越界**启动 fail-fast** |
| `CTDS_DB_URL` / `CTDS_DB_PASSWORD` | —— | mysql profile；口令仅环境变量 |

## 确认记录（V0.9 → V1.0）

| 项 | 内容 |
| --- | --- |
| 提请确认 | 2026-10-26；契约基准 = lofi Q1~Q8 全采 A + D1 不拆分 |
| **编排师裁定** | ✅ **2026-10-26 编排师会话回复"确认"** = **Q1~Q8 全采 A + D1 不拆分**；本文件正文（§1~§12）**零改动**转 V1.0 = 编码契约 |
| 确认后动作（进行中） | 编码段以 §11 前置检查八项为第一动作，测试先行（§9 清单 T1~T8 逐类落盘）；实现与本节契约逐条一致，偏差就地注记留痕 |
