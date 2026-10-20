# WBS-3.5.3 高保真设计：加密文件传输服务（V0.9，2026-10-18）

> 状态：**V1.0（编码契约，已确认生效）**——编排师 2026-10-18 一次确认 **Q1~Q9 全采建议 A + G1-A + D1 不拆分**（确认记录落本文件末节）；V0.9 → V1.0 变更 = 版本头与确认状态更新，**正文零改动**。
> 输入：lofi V0.9 + 任务卡 + 立卡前素材盘点（结论见任务卡 §一 表）。
> 口径纪律：本文件只从规格 C-5.1-5.2 V1.0（行为 1 / 行为 3 / 行为 7）+ ADR-006 / ADR-015 / ADR-005 / ADR-007 / ADR-016 §6 / ADR-020 §2.8 / ADR-022 + WBS 行 290 派生；**未在设计中补需求**；规格未定项登记为未定义项或演进项。**G1 剧本缺口不在本文件私自修复**（走变更流程）。

## §1 模块与装配（Q1-A）

1. **模块**：`services/delivery-service`（本地 `pom.xml`，parent `ctds-platform` + `<relativePath>../../pom.xml</relativePath>`，沿既有服务骨架）；**Servlet**（Spring Web）；**端口 8087**；**MySQL `ctds_delivery`**；Flyway 迁移；默认 profile 排除 `DataSourceAutoConfiguration`（沿 catalog/contract 先例），`mysql` profile 启用数据源。
2. **依赖（零新第三方）**：`common-errorcode` / `common-auth` / `common-idempotency` / `common-pagination` / `common-logging` / `common-crypto`；`spring-boot-starter-web` / `-validation` / `-jdbc`；`flyway-core` / `flyway-mysql` / `mysql-connector-j`；测试 `spring-boot-starter-test` / `testcontainers:junit-jupiter` / `testcontainers:mysql`（版本走父 BOM）。**JDK HttpClient** 做服务间调用（沿 contract 的 `CatalogProductClient` 先例）。**不引入对象存储客户端、不引入新密码学库**。
3. **配置（`application.yml`，口令/密钥仅环境变量）**：
   - `server.port: 8087`、`server.address: 127.0.0.1`（演示期回环，ADR-016 §2.7）。
   - `ctds.auth.permissions`：`requester: delivery.request,delivery.read,delivery.package.download`；`provider: delivery.read`；`admin: delivery.governance`。
   - `ctds.delivery.contract.base-url: ${CTDS_DELIVERY_CONTRACT_BASEURL:}`、`catalog.base-url: ${CTDS_DELIVERY_CATALOG_BASEURL:}`、`contract.timeout: 3s`、`catalog.timeout: 3s`（未配置 = 依赖不可用，交付 fail-closed）。
   - `ctds.delivery.source-root: ${CTDS_DELIVERY_SOURCE_ROOT:}`、`storage-root: ${CTDS_DELIVERY_STORAGE_ROOT:}`、`file.key-ref: ${CTDS_DELIVERY_FILE_KEY_REF:delivery-file}`、`file.max-bytes: 67108864`、`files: []`（清单；见 §5）。
   - `ctds.crypto.local.key-file: ${CTDS_SM4_KEY_FILE:}`（KeyProvider；KMS 经 `ctds.crypto.kms.base-url` 覆盖）。
   - `ctds.idempotency.mode: memory`、`ctds.lock.mode: memory`（单实例演示；多实例演进）。
4. **装配**：`DeliveryApplication`；`DeliveryProperties`（`@ConfigurationProperties(prefix="ctds.delivery")`）；`FileSourceRegistry`（启动校验）；`Clock` Bean（审计/测试可控）；`AuditRecorder` 由 common-logging 自动装配；`TraceIdFilter` 由 common-errorcode 自动装配。

## §2 交付记录模型与编号（Q2-A / Q8-A）

### 2.1 表结构（`V1__create_delivery_tables.sql`）

```sql
CREATE TABLE delivery (
  id                BIGINT        NOT NULL AUTO_INCREMENT,
  delivery_no       VARCHAR(16)   NOT NULL COMMENT 'DL+6位全局序号',
  contract_no       VARCHAR(32)   NOT NULL,
  product_id        BIGINT        NOT NULL,
  product_name      VARCHAR(128)  NOT NULL COMMENT '发起时快照',
  delivery_type     VARCHAR(8)    NOT NULL COMMENT 'API/FILE',
  requester_no      VARCHAR(32)   NOT NULL COMMENT '合约需求方快照',
  provider_no       VARCHAR(32)   NOT NULL COMMENT '合约提供方快照',
  file_id           VARCHAR(64)   NULL COMMENT '文件清单精确键（FILE）',
  file_name         VARCHAR(256)  NULL COMMENT '展示名快照（FILE）',
  file_size         BIGINT        NULL COMMENT '明文字节数（FILE）',
  package_id        VARCHAR(64)   NULL COMMENT '加密包标识（FILE）',
  checksum          VARCHAR(64)   NULL COMMENT 'SM3 小写 hex（明文，FILE）',
  key_ref           VARCHAR(64)   NULL COMMENT '加密密钥编号（非密钥材料，FILE）',
  result            VARCHAR(16)   NOT NULL COMMENT 'SUCCESS',
  idempotency_key   VARCHAR(128)  NOT NULL,
  created_at        DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_delivery_no (delivery_no),
  UNIQUE KEY uk_idem (requester_no, idempotency_key),
  KEY idx_contract (contract_no, created_at),
  KEY idx_requester (requester_no, created_at),
  KEY idx_provider (provider_no, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE delivery_action_log (
  id           BIGINT       NOT NULL AUTO_INCREMENT,
  delivery_no  VARCHAR(16)  NULL,
  contract_no  VARCHAR(32)  NULL,
  operator_no  VARCHAR(32)  NOT NULL,
  action       VARCHAR(64)  NOT NULL,
  result       VARCHAR(16)  NOT NULL,
  reason_code  VARCHAR(16)  NULL,
  created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_log_delivery (delivery_no, created_at),
  KEY idx_log_contract (contract_no, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE delivery_no_seq (
  id       BIGINT NOT NULL,
  next_no  BIGINT NOT NULL,
  PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
INSERT INTO delivery_no_seq (id, next_no) VALUES (1, 1);
```

- **记录只存业务要素与校验值**：无文件本体、无原文、无路径、无密钥材料；`key_ref` 仅为密钥编号（非秘密）。
- **API 记录**：`file_*`/`package_id`/`checksum`/`key_ref` 为 NULL；**FILE 记录**：全部填写。
- `result` 本卡只写 `SUCCESS`（同步交付；拒绝不落 `delivery` 表，拒绝留痕走 `delivery_action_log` + 审计）。
- 编号：`DL` + 6 位全局序号（`delivery_no_seq` 原子取号，沿 contract `contract_no_seq` 先例）；耗尽（>999999）→ `1009S0003`（内部失败，登记演进）。

### 2.2 读面视图

`DeliveryView`：`deliveryNo / contractNo / productId / productName / deliveryType / requesterNo / providerNo / fileId / fileName / fileSize / packageId / checksum / result / createdAt`。**不含路径、keyRef、本体**。

## §3 交付准入链路（固定顺序，Q3/Q4/Q7）

```
身份（common-auth X-Ctds-Subject / roles）
 → 幂等查重（DB: requester_no + X-Idempotency-Key）
 → [contract] 内部只读合约摘要 GET /api/v1/contracts/internal/contract-summaries/{contractNo}?requesterNo=
       ├─ 非参与方/不存在 → 1008C0012（404 同码同文）
       ├─ 非 EFFECTIVE → 1008C0013（409）
       └─ 通过 → productId / productName / applicantNo / providerNo / status / effectiveAt
 → 发起人 = applicantNo ？（否则 1009C0003 / 403，**先于计数**）
 → [catalog] 内部产品 GET /api/v1/catalog/internal/data-products/{productId} → productType
 → 形态/范围校验（API→API；FILE→DATASET/REPORT/MODEL；fileId→productId）
 → （FILE）[contract] 既有内部判定 POST /api/v1/contracts/internal/usage-checks（USE，purpose/territory）
       ├─ 合约 C 型码原码透传（1008C0008/0012/0013/0020）
       └─ 通过 = 放行并计数 1 次
 → （FILE）读取源文件（≤64MiB）→ SM3 → SM4/GCM 加密 → 暂存 → 原子落盘
 → 取 deliveryNo → 写 delivery + action_log（事务）
 → 审计 delivery.create → 返回 DeliveryView
```

- **API 交付发起不调用 `check`**：真正调用由 3.5.2 网关在转发前判定并计数；本卡只落交付记录，避免双计。
- **FILE 交付调用 `check` 一次**：文件交付 = 一次使用；拒绝零计数；计数与包生成失败的关系见 §5.4。
- **先发起人、后计数**：用只读摘要前置校验，规避 3.5.2 ADR-022 §3 已登记的“提供方消耗需求方配额”观察项（文件通道不继承该缺陷）。
- **失败不落交付记录**：任何拒绝/依赖不可用/加密失败均不写 `delivery`；拒绝留痕写 `delivery_action_log` + 审计（不含本体）。
- **产品下架不阻断既有合约**：只读摘要只看合约生效；产品状态不构成交付门槛（沿 C-4.1 行为 6 规则 3）。

## §4 跨域集成契约（Q7-A）

### 4.1 contract：新增内部只读合约摘要端点（最小加性）

| 项 | 契约 |
| --- | --- |
| 路径 | `GET /api/v1/contracts/internal/contract-summaries/{contractNo}?requesterNo={requesterNo}` |
| 权限 | `@RequirePermission("contract.internal.read")`；`contract-service/application.yml` 增 1 行 `delivery-internal: contract.internal.read` |
| 处理 | ① `ContractVisibilityGuard#requireParticipant`（不存在/非参与方 → `1008C0012` 同码同文 + `DENIED_ACCESS` 留痕）② `status != EFFECTIVE` → `1008C0013`（同码同文案）③ 返回摘要；**不递增用量计数、不写 usage_log** |
| 响应 | `ApiResult<{contractNo, productId, productName, applicantNo, providerNo, status, effectiveAt}>`（`applicantNo` = `requesterSubjectNo`；不含条款原文与本体） |
| 出站 | 沿 `ContractExceptionHandler` 既有映射（0012→404 / 0013→409 / 0008→400） |
| 登记 | **ADR-016 §6 消费方第 8 位 = delivery-service**；ADR-023 固化；**ADR-022 零改动**（现有内部判定响应不动） |

### 4.2 catalog：内部产品视图增 `productType`（加性）

1. `InternalProductController.InternalProductView` 增字段 `productType`（`ProviderProductRow.productType()`；四形态 `API/DATASET/REPORT/MODEL`）；既有 6 字段与消费者（contract）不受影响（Jackson 忽略新增字段）。
2. `catalog-service/application.yml` 增 1 行 `delivery-internal: catalog.internal.read`。
3. 交付服务读取 `productType`；产品不存在（`1007C0011`）/未知形态 → `1009S0002`（fail-closed，不冒充形态判定）。

### 4.3 服务间调用与超时（JDK HttpClient）

| 客户端 | 请求 | 身份 | 失败口径 |
| --- | --- | --- | --- |
| `ContractInternalClient.summary` | GET 摘要 | `X-Ctds-Subject: delivery-service` / `X-Ctds-Roles: delivery-internal` | 1008C 原码透传；其余（不可达/超时/非 2xx/解析失败）→ `1009S0002` |
| `ContractInternalClient.check` | POST `/api/v1/contracts/internal/usage-checks` | 同上 | 同上 |
| `CatalogInternalClient.product` | GET 内部产品 | 同上 | `1007C0011`/其余 → `1009S0002` |

连接超时 2s、读超时 3s（沿 contract 客户端先例，配置可调）；**服务间调用不落用户令牌**（沿 ADR-022 §2.8 口径）。

### 4.4 ADR-016 §6 消费方登记

在第 7 位（gateway）之后增 **第 8 位 = delivery-service**：消费 contract 内部只读摘要 + 内部判定 + catalog 内部产品；注明用途与权限点。格式沿既有登记惯例（编号、消费方、端点、权限、用途）。

## §5 文件源与加密交付包（Q3/Q4/Q5/Q6）

### 5.1 文件源清单（配置化，无上传端点）

```yaml
ctds:
  delivery:
    source-root: ${CTDS_DELIVERY_SOURCE_ROOT:}
    storage-root: ${CTDS_DELIVERY_STORAGE_ROOT:}
    file:
      key-ref: ${CTDS_DELIVERY_FILE_KEY_REF:delivery-file}
      max-bytes: 67108864
    files:
      - file-id: sample-dataset
        product-id: 13
        path: sample-dataset.csv
        display-name: 演示数据集.csv
```

- **启动校验（fail-fast）**：配置了 files 但 `source-root` 为空 → 启动失败；fileId 匹配 `[A-Za-z0-9._-]{1,64}` 且唯一；productId > 0；path 为相对路径、无 `..`、真实路径在 `source-root` 内；`display-name` 非空、≤255、无控制字符；文件存在、为普通文件（**拒绝符号链接**）、大小 >0 且 ≤`max-bytes`。任一不满足 → 启动失败（静默跳过 = 文件交付不可信）。
- **未配置（files 空 / source-root 空）**：服务可启动；FILE 交付 → `1009S0002`（503，fail-closed）；API 交付不受影响。
- **请求时**：fileId 不在清单 → `1009C0006`（400，超出合约约定范围）；清单项 productId ≠ 合约 productId → `1009C0006`；文件丢失/不可读 → `1009S0002`；实际大小 > max → `1009C0008`（400）。
- **fileId 仅精确键**：用户不能提供路径；实际路径只来自清单，目录穿越风险结构性消除。

### 5.2 国密加密与完整性

1. 读取源文件字节（先 `Files.size` 校验 ≤64MiB，再 `readAllBytes`；≤64MiB 内存可控）。
2. `Sm3Service.digestHex(bytes)` → 64 位小写 hex（业务校验值）。
3. `Sm4Service.encrypt(bytes, keyRef)` → SM4/GCM 信封（v1/v2 由 KeyProvider 决定；ADR-006 §3.2/§3.4）。
4. `packageId = deliveryNo + ".sm4"`；写 `storage-root` 临时文件 → `ATOMIC_MOVE` 到 `packageId`；失败 → `1009S0003`，删除临时文件。
5. DB 写 `delivery`（含 checksum/packageId/keyRef）；DB 失败 → 删除包，抛错。
6. **明文/密钥零落盘**：源文件是平台源（清单内）；加密包是唯一交付物；DB/审计/日志无明文、无密钥、无路径；`keyRef` 仅编号。
7. **≤64MiB 硬上限**：ADR-006 `Inputs.MAX_INPUT_BYTES`；超出 → `1009C0008`（400），**不读入、不加密**；流式分块登记演进（ADR-006 §4 风险③）。
8. **密钥**：单一配置 keyRef（默认 `delivery-file`）；演示用独立 `delivery-demo.keys`（gitignored）；`ctds.crypto.kms.base-url` 配置时由 KMS 覆盖；**不新建 KMS 密钥、不读取/打印密钥材料**。

### 5.3 领取（Q5-A）

- `GET /api/v1/deliveries/{deliveryNo}/package`：仅**合约需求方**（记录 `requester_no` = 调用者）；返回**加密包**（`application/octet-stream`，`Content-Disposition` 用展示名，`X-Ctds-Sm3` = 明文 SM3，`Content-Length`）；不返回明文。
- 提供方/运营方/非参与方：`1009C0009`（403）或 `1009C0007`（404，非参与方）——见 §7 防枚举。
- 包文件缺失/不可读 → `1009S0003`（500/503）。
- **接收方密钥分发不在 V1.0**：加密包需接收方持有 keyRef 对应密钥才能解密；本卡不做密钥分发/封装（登记 3.6.x/3.9.1/后续包）；剧本 S2 只校验“包 + 校验值 + 记录”。

### 5.4 策略计数与包生成失败的关系（诚实边界）

- `check` 先于读文件/加密：拒绝请求**不触达文件内容**（沿“判定在取数之前”）；放行即计数 1 次。
- 若 `check` 放行后加密/存储失败：已计数 1 次但无交付记录/包 → **平台侧失败**（非用户越权），如实审计 `delivery.create` FAILURE + `1009S0003`；登记为已知边界（低频；如后续要求“失败退次数”，走变更流程，不在本卡自造补偿）。
- 计数与交付记录的**分工**：合约 usage_log = 策略计数；`delivery` 记录 = 交付事件源（3.7.5 文件交付计量以 `delivery` 为准），**不双计**；3.7.5 对接时按此口径核对。

## §6 幂等与并发（Q8-A）

1. **请求头**：`X-Idempotency-Key`（POST `/api/v1/deliveries` 必填；非空、`[A-Za-z0-9._-]{1,128}`；缺失/非法 → `1009C0005`）。
2. **短期**：应用服务方法 `@Idempotent(key = "#cmd.requesterNo + ':' + #cmd.idempotencyKey")`（memory 结果复用；演示/单实例；TTL 默认 600s）。
3. **长期**：`delivery` 唯一键 `(requester_no, idempotency_key)`；进入业务前先查已存在记录，命中直接返回首次视图（不重新生成包）；并发插入撞唯一键 → 捕获并回读首次记录返回。
4. **语义**：同一幂等键 + 不同请求体 → 返回首次结果（规格行为 1 规则 5 / 行为 3 规则 5）；不做“键复用冲突”新错误码。
5. **多实例**：`@Idempotent` memory 为单实例语义；多实例部署前换 Redis/DB 分布式语义（登记演进；DB 唯一键已提供永久兜底，但并发窗口内需应用层处理）。

## §7 API 端点、权限与错误码（Q8-A）

### 7.1 端点

| 方法/路径 | 权限 | 说明 |
| --- | --- | --- |
| `POST /api/v1/deliveries` | `delivery.request` | 发起 API 或 FILE 交付；头 `X-Idempotency-Key`；体 `{contractNo, deliveryType, fileId?, purpose?, territory?}`；返回 `DeliveryView` |
| `GET /api/v1/deliveries/{deliveryNo}` | `delivery.read` | 参与者（需求方/提供方）详情；非参与方/不存在 → 404 + `1009C0007` 同码同文 |
| `GET /api/v1/deliveries?contractNo=&deliveryType=&pageNum=&pageSize=&orderBy=` | `delivery.read` | 参与者列表（按调用者过滤；`pageSize` ≤100） |
| `GET /api/v1/deliveries/{deliveryNo}/package` | `delivery.package.download` | 仅需求方；返回加密包字节 + `X-Ctds-Sm3` |
| `GET /api/v1/deliveries/governance?contractNo=&deliveryNo=&pageNum=&pageSize=` | `delivery.governance` | 治理列表（admin）；审计 `delivery.governance.read` |
| `GET /api/v1/deliveries/governance/{deliveryNo}` | `delivery.governance` | 治理详情；审计 |

- **统一响应**：`ApiResult`（沿 common）；分页 `PageResult`；traceId 由 common-errorcode `TraceIdFilter` 注入。
- **请求校验**：`contractNo` 非空、≤32、`[A-Za-z0-9._-]+`；`deliveryType ∈ {API, FILE}`；FILE 必须 `fileId`；API 不允许 `fileId`；`purpose`/`territory` 可空、≤256 字符；非法 → `1009C0005`（400）。
- **列表防枚举**：按 `requester_no = caller OR provider_no = caller` 过滤；非参与方得到空列表（不泄露记录存在性）；详情/下载按记录属主判定，非参与方/不存在统一 `1009C0007`（404 同码同文）。
- **治理读面**：admin 不过滤参与者；返回记录不含本体；每次读产生 `delivery.governance.read` 审计。

### 7.2 错误码（1009 段顺延）

| 码值 | 含义 | HTTP |
| --- | --- | --- |
| `1009C0003` | 非合约需求方不可发起交付（复用网关同码同义） | 403 |
| `1009C0005` | 交付请求参数非法（合约编号/类型/幂等键/fileId 等） | 400 |
| `1009C0006` | 交付形态或范围与合约约定不符（形态不符/文件不在产品范围） | 400 |
| `1009C0007` | 交付记录不存在或无权查看（同码同文防枚举） | 404 |
| `1009C0008` | 文件超出交付上限（64MiB） | 400 |
| `1009C0009` | 仅合约需求方可领取交付包 | 403 |
| `1009S0002` | 交付依赖不可用（合约/目录/文件源未配置或丢失） | 503 |
| `1009S0003` | 交付包生成或读取失败 | 500 |

**复用/透传**：未认证 `1000C0002`（401）、无权限 `1000C0005`（403）由 common-auth 承载；分页参数 `1000C0001`（common-pagination）；合约 C 型码原码透传：`1008C0008`→400 / `1008C0012`→404 / `1008C0013`→409 / `1008C0020`→403；**禁止改写合约域结论码**。`1009C0003` 与网关同值同义（模块各自常量；一致性由用例锚定文案）。

### 7.3 审计动作（`common-logging` `AuditRecorder`）

| 动作 | 触发 | 字段（最小必要） |
| --- | --- | --- |
| `delivery.create` | 交付记录创建成功/失败 | deliveryNo / contractNo / deliveryType / result / reasonCode |
| `delivery.read` | 参与者详情/列表 | deliveryNo（列表省略）/ contractNo / result |
| `delivery.governance.read` | 治理读面 | deliveryNo / contractNo / operator |
| `delivery.package.download` | 加密包下载 | deliveryNo / result / reasonCode |
| `delivery.denied` | 任何拒绝 | contractNo / reasonCode / deliveryType |

**禁止**：文件本体、原文、路径、密钥材料、令牌。审计落 `logs/audit-*.jsonl`（异步有界，沿 common-logging）。

## §8 contract / catalog 加性改动清单

| 文件 | 改动 |
| --- | --- |
| `services/contract-service/src/main/java/.../interfaces/InternalContractSummaryController.java` | 新增（GET 摘要；权限 `contract.internal.read`） |
| `services/contract-service/src/main/java/.../application/InternalContractSummaryService.java` | 新增（可见性 + 生效门槛 + 摘要；**零计数**） |
| `services/contract-service/src/main/resources/application.yml` | 增 `delivery-internal: contract.internal.read` 1 行 |
| `services/contract-service/src/main/java/.../interfaces/dto/InternalContractSummaryViews.java` | 新增摘要视图 |
| `services/catalog-service/src/main/java/.../interfaces/InternalProductController.java` | `InternalProductView` 增 `productType`（7 字段） |
| `services/catalog-service/src/main/resources/application.yml` | 增 `delivery-internal: catalog.internal.read` 1 行 |
| `docs/adr/ADR-016-主体服务契约.md` §6 | 消费方第 8 位登记 |
| `docs/adr/ADR-023-文件交付契约.md` | 新建（7 必含字段；见 §12） |

**不改**：ADR-022 / 网关 / 既有端点语义 / 既有错误码码值 / 门禁配置 / ADR-001。

## §9 测试清单（测试先行，红相即时归档）

> 归档目录 `build-output/w353-red-*`；断言双向齐备，禁止空洞断言与仅 happy-path（AGENTS §4）。

| # | 组 | 用例要点 | 双向/边界 |
| --- | --- | --- | --- |
| T1 | 准入与形态 | 生效 API 合约 + API 交付 → 记录；生效数据集合约 + 文件 → 包；未生效/终止/完结 → 1008C0013；非参与方 → 1008C0012 同码同文；提供方发起 → 1009C0003；API 合约 + 文件 → 1009C0006；数据集合约 + API → 1009C0006 | 正/反 |
| T2 | 文件交付与国密 | 生成包 + SM3 可核；包可下载（需求方）；下载头 SM3 与记录一致；篡改包 → GCM 拒绝（组件级夹具）；超 64MiB → 1009C0008；keyRef 不可用 → 1009S0003；包文件缺失 → 1009S0003 | 正/反/边界 |
| T3 | 策略与计数 | 合规文件交付 → `check` 放行 + 计数 +1；策略越界 → 1008C0020 原码 + **零计数** + 无包/无记录；`check` 不可用 → 1009S0002（fail-closed）；API 交付发起**不计数** | 正/反 |
| T4 | 访问与防枚举 | 需求方/提供方看记录；非参与方 404 同码同文；非需求方下载 403；治理查看 + 审计；列表按调用者过滤 | 正/反 |
| T5 | 幂等 | 同键重放返回首次结果、记录不增、包不重生成；并发同键 → 仅 1 条；同键不同体 → 首次结果；缺/非法键 → 1009C0005 | 正/反/并发 |
| T6 | 文件源安全 | fileId 未登记 → 1009C0006；productId 不符 → 1009C0006；路径 `..`/绝对路径/符号链接 → 启动失败（配置级用例）；源文件丢失 → 1009S0002 | 边界 |
| T7 | 跨域加性 | contract 摘要：权限正反 / 参与方 / 非参与方 / 非生效 / productId / **计数不变**；catalog `productType` 四形态 / 不存在 | 正/反 |
| T8 | 记录与审计不含本体 | DB 字段集、审计 JSONL、响应体断言无文件本体/原文/路径/密钥；文件名/校验值可见 | 字段集 |
| T9 | 列表与分页 | pageSize 100 边界；pageSize 101 → 1000C0001；orderBy 白名单映射；空列表 | 边界 |

**测试替身**：contract/catalog 用 JDK `com.sun.net.httpserver.HttpServer` 本地 stub（零新依赖），断言请求计数（拒绝/fail-closed 时合约 `check` 零调用、文件零读取）；MySQL 用 Testcontainers（`@Testcontainers(disabledWithoutDocker = true)`，沿既有先例登记 Docker 缺席跳过）。

## §10 前置检查项（编码会话第一动作，逐项实测留痕）

1. `git status` 干净；`main == origin/main == b80bb17`（确认段后 main 不变）；当前分支 = `feat/WBS-3.5.3-加密文件传输服务`。
2. contract 基线复跑 **240 例**（0F/0E/0S + checkstyle 0）；catalog 基线 **178 例**；gateway **32 例**（防基线漂移）。
3. 前端基线复跑 **440 例**（本卡零前端，仍须基线证据）。
4. 端口 **8087** 空闲实测；MySQL 容器可用（Testcontainers / 演示库）；`ctds_delivery` 可创建。
5. `gates-config.json` V1.12 / ConfigSha256 `955bdbba…2ee2` 记录（零改动自证）。
6. 确认记录在案（lofi/hifi V1.0 签署）；G1 剧本修订状态确认（PO 批准/登记）。
7. 演示准备：数据集形态已生效合约 + 对应 productId；合成 CSV；独立 `delivery-demo.keys`（不打印）；源根/存储根/服务 base-url 环境变量。
8. 零新第三方依赖核对（`docs/dependencies.md` 无新增；common-* 均为既有内部坐标）。

## §11 交付门禁与真链走查判定表

- **本地**：`mvn -B -ntp -pl services/delivery-service -am test checkstyle:check` 全绿；contract/catalog 模块测试全绿（用例只增不减）；全仓 `run-gates.ps1` **单发 GREEN**（沿单发干净复跑先例，不申请特批、不改配置）。
- **真链走查（方式 A 代执行，API 级；判定表全 PASS）**：

| 组 | 判定面 |
| --- | --- |
| 准入 | 未生效/终止/完结 1008C0013；非参与方 1008C0012 同码同文；提供方发起 1009C0003 |
| 形态 | API 合约 + API 交付 → 记录；数据集合约 + 文件 → 包；形态不符 1009C0006 |
| 文件交付 | 包生成 + SM3 可核；需求方下载；提供方/运营方不可下载；>64MiB 1009C0008 |
| 策略 | 合规文件交付计数 +1；越界 1008C0020 + 零计数 + 无包；check 不可用 1009S0002 |
| 记录与治理 | 需求方/提供方可见；治理查看 + 审计；非参与方 404 同码同文 |
| 幂等 | 同键重放不增记录/不重生成包；缺键 1009C0005 |
| 无本体 | 记录/审计/日志/响应字段集不含本体与原文 |

- **剧本执行口径**：S1/S2/S3 按 C-5.1 剧本（界面入口待 3.5.6；本卡 API 级代执行）；**S2 按 G1 批准后的凭证/要素执行**；不放宽任何判定。

## §12 未定义项 / 演进项 / 跟踪项（本卡不实施）

| 项 | 承接 |
| --- | --- |
| 接收方密钥分发 / SM2 封装 / 解密能力 | 3.6.x 连接器 / 3.9.1 真实令牌 / 后续包（V1.0 诚实边界） |
| 提供方文件上传 / 对象存储接入 / 文件生命周期 | 3.6.x / 后续包（本卡为配置化清单） |
| >64MiB 流式分块加密 | ADR-006 演进 / 4.3.3 国密合规验证前评估（本卡硬上限 64MiB） |
| 每交付独立密钥 / KMS 密钥轮换编排 | 后续 KMS/交付演进（本卡单一 keyRef） |
| 分布式幂等（多实例） | 后续部署演进（DB 唯一键兜底，应用层 memory 语义） |
| 3.7.5 计量采集 / 3.7.6 计费对账 | 消费 `delivery` 事件源；文件交付不双计（§5.4） |
| 3.8.3 交付环节存证上链 | 后续存证包（本卡只落本地记录/审计） |
| 交付界面（订单/文件/校验值/治理） | 3.5.6 |
| 前端代理与部署单入口切换 | 3.5.6 / 3.5.7 前统一 |
| 独立 API Key（规格 §6-5 未定义项） | 后续包（本卡凭据 = 合约编号 + 主体身份） |
| ADR-023 落地 | 编码段随卡创建（7 必含字段；记录模型/包格式/准入链/访问边界/演进） |

---

## 确认记录（V0.9 → V1.0）

> **✅ 已签署（2026-10-18 20:4x，编排师会话回复“全部按建议”）**：**Q1~Q9 全采建议 A**；**G1 采 A**（S2 改用数据集形态已生效合约 + 补 purpose/territory；剧本修订提交 PO 审批，本卡不擅自改）；**D1 不拆分**。**编码契约（本文件 §1~§12）即时生效**——编码会话按 **§10 前置检查项八项**逐项实测留痕开工，测试先行（§9 九组用例，红相归档 `build-output/w353-red-*`），走查按 **§11 判定表**执行；本文件与 lofi 同批转 V1.0。
