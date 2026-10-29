# WBS-3.6.6 高保真设计：适配器-数据库/对象存储/API 网关（V0.9，2026-10-29）

| 字段 | 内容 |
| --- | --- |
| 版本 | **V0.9（待编排师一次确认 → V1.0 编码契约生效）**；确认后本文件即编码契约（实现与设计逐条一致，偏离 = 打回项） |
| 任务卡 | `docs/tasks/WBS-3.6.6-适配器-数据库-对象存储-API网关-2026-10-29.md` |
| 上游契约 | ADR-026（SPI）· ADR-029（源适配契约）· ADR-006 / 015（国密）· ADR-005（API 与错误码）· ADR-019 / 020（判定单点）· ADR-009（迁移，本卡零迁移） |
| 范围 | SDK 三类适配器 + 服务侧调用边界加固（DB-91）+ 留痕读面泛化（DB-90）；**零新增端点 / 迁移 / 权限点 / 依赖 / 门禁改动** |

---

## §1 模块装配契约（Q1-A：零新模块 / 零新第三方）

| 项 | 契约 |
| --- | --- |
| 适配器落位 | `connector-sdk` 新增 `adapter/database` / `adapter/objectstorage` / `adapter/apigateway` 三个包；实现 `com.ctds.connector.sdk.spi.SourceAdapter` |
| 装配方式 | `SourceAdapterRegistry` 构造器吃 `List<SourceAdapter>` 按 `type()` 自动索引（同类型重复 → 启动失败）；**唯一改动 = `SourceAdapterConfig` 追加 3 个 `@Bean`**，服务 / 控制器 / 视图零改动 |
| 依赖 | `connector-sdk/pom.xml` **零改动**（只用 JDK 标准 API + 既有 `spring-web` / `jackson-databind`）；`connector-service` 零新增依赖（`mysql-connector-j` runtime 与 `starter-jdbc` 已在） |
| JDBC 驱动 | **由运行宿主提供**：SDK 只编译 `java.sql`；`connector-service` classpath 已含 `mysql-connector-j`，`DriverManager` 可自动注册。诚实边界写入 ADR-030：连接器部署方须自带目标数据库驱动 |
| 新主机防护 | 领域端口 `SourceHostGuard` + 基础设施实现 `InetAddressSourceHostGuard`；`ConnectorSourceService.check` / `ConnectorSourceReadService.read` 在调适配器前调用 |
| 零改动面 | 网关、路由、交付域、3.6.3 / 3.6.4 / 3.6.5 端点语义、`application.yml` 权限映射、`gates-config.json`、`docs/dependencies.md` |

## §2 数据与凭据（零迁移）

| 项 | 契约 |
| --- | --- |
| 表 | **零迁移**：复用 `connector_source`（`source_type` / `endpoint` / `credential_ref` / `credential_ciphertext` / `credential_masked`）与 `connector_source_no_seq` |
| 凭据明文上限 | **256 → 1024**（预签名 URL 需要）；`credential_ciphertext VARCHAR(2048)` 不变（1024 明文经 SM4 信封 + Base64 约 1.4KB，编码段实测确认；若实测超限则按变更流程扩列，不在本卡私自改） |
| 三类凭据形态 | `DATABASE`：`用户名:密码`（首个冒号切分）；`OBJECT_STORAGE`：完整预签名 URL（`http(s)://…`）；`API_GATEWAY`：完整 `Authorization` 头值（如 `Bearer …`） |
| 掩码 | 复用 `CredentialCipher.mask()`（≤4 全掩 / >4 留末 4 位）；**对象存储不额外展示 URL 任何片段**（末 4 位为签名片段，不可逆且短期有效，可接受） |
| 变更留痕 | 复用 `source.amend`：旧 / 新 `endpoint`（脱敏）+ "凭据已变更 + 掩码"；**新凭据明文不进留痕** |
| 重复登记 | 沿用 `uk_connector_type_endpoint(connector_no, source_type, endpoint(128))` |

## §3 包结构与类清单

**SDK 新增（约 12 类）**

| 包 | 类 | 职责 |
| --- | --- | --- |
| `adapter/database` | `DatabaseSourceAdapter` | `type()`=`DATABASE`；`check` / `read` 编排；异常 → 三类失败映射 |
| | `DatabaseSourceClient` | JDBC 探针：`DriverManager.getConnection` + `setReadOnly(true)`；`SELECT 1`；`information_schema` 摘要 |
| | `JdbcUrlPolicy` | JDBC URL 解析：scheme / host / port / schema 提取 + **参数白名单**校验（静态纯函数，可单测） |
| | `DatabaseCredential` | 凭据解析（`user:password`），`toString()` 掩码 |
| `adapter/objectstorage` | `ObjectStorageSourceAdapter` | `type()`=`OBJECT_STORAGE`；预签名 URL 只读探测与摘要 |
| | `PresignedUrlClient` | JDK `HttpClient`：`GET` + `Range: bytes=0-0`；超时 3s/5s；不读 body |
| | `ObjectStorageCredential` | 预签名 URL 校验（必须 `http(s)://`，长度 ≤1024） |
| `adapter/apigateway` | `ApiGatewaySourceAdapter` | `type()`=`API_GATEWAY`；`Authorization` 原样出站；状态映射 |
| | `ApiGatewayClient` | JDK `HttpClient`：`GET`；限制读取 ≤1MB（仅计字节数，不返回内容） |
| | `HttpProbeResult`（包私有） | 状态码 / 字节数 / 内容类型 / 异常分类 |

> 三类适配器共享的超时、状态映射、URL 主机提取等横切逻辑放各自包内（**不新建 SDK 公共工具包**，避免"自造框架"；重复 3 处可接受，量小且各有协议差异）。

**服务侧新增 / 修改（6 类）**

| 类型 | 类 | 改动 |
| --- | --- | --- |
| 新增 | `domain/SourceHostGuard` | 端口：`void assertAllowed(SourceType type, String endpoint, String credentialPlain)` |
| 新增 | `infrastructure/InetAddressSourceHostGuard` | `InetAddress.getAllByName(host)` 全量判定；私网 / 回环 / 链路本地 / 解析失败 → 抛领域异常 |
| 改 | `infrastructure/SourceAdapterConfig` | +3 适配器 `@Bean` + `SourceHostGuard` `@Bean` |
| 改 | `application/ConnectorSourceService` | `check()` 在 `adapter.check(...)` 前调用 guard（解密后、调用前）；失败按 `NETWORK_UNREACHABLE` 记录 |
| 改 | `application/ConnectorSourceReadService` | `read()` 在 `adapter.read(...)` 前调用 guard；拒绝 → `1010C0010` |
| 改 | `domain/ConnectorErrorCodes` | +`1010C0010 SOURCE_ENDPOINT_NOT_ALLOWED`（400） |
| 改 | `domain/ConnectorAuditRepository` + `infrastructure/JdbcConnectorAuditRepository` | +`findLatestDetail(connectorNo, action, detailPrefix)` |
| 改 | `infrastructure/JdbcConnectorSourceRepository` | `findLastCheck()` 改为复用上述通用方法（detail 解析保留在此） |
| 改 | `interfaces/dto/ConnectorSourceViews` | 凭据 `@Size(max = 1024)`（原 256）；错误码注释同步 |

## §4 关键接口签名

```java
// SDK
public interface SourceAdapter {
    SourceType type();
    SourceCheckResult check(SourceRegistration registration);
    SourceReadResult read(SourceRegistration registration, SourceReadRequest request);
}

// 三类实现构造器统一注入凭据解析器（平台侧注入解密逻辑，SDK 永不接触密文）
public DatabaseSourceAdapter(CredentialResolver credentialResolver)
public ObjectStorageSourceAdapter(CredentialResolver credentialResolver)
public ApiGatewaySourceAdapter(CredentialResolver credentialResolver)

// 服务侧领域端口
public interface SourceHostGuard {
    /** 解析 endpoint（及对象存储凭据中的 URL）主机；命中受限网络或无法解析即抛 ConnectorBizException。 */
    void assertAllowed(SourceType type, String endpoint, String credentialPlain);
}

// 留痕通用读面（DB-90）
public interface ConnectorAuditRepository {
    Optional<String> findLatestDetail(String connectorNo, String action, String detailPrefix);
}
```

**凭据解析失败语义**：`CredentialResolver.resolve` 找不到引用 → 既有 `1010C0003`；解析出的明文形态不符（数据库缺冒号 / 对象存储非 URL）→ 适配器返回 `INVALID_CREDENTIAL` 业务结论（自检 200），**不抛 5xx**。

## §5 端点契约（零新增；既有 6 端点语义扩展）

| # | 方法 / 路径 | 权限点 | 本卡变化 |
| --- | --- | --- | --- |
| 1 | `POST /api/v1/connectors/{connectorNo}/sources` | `connector.source.manage` | 三类源登记受理（既有）；凭据上限 1024；危险 JDBC 参数 → 400 `1010C0001` |
| 2 | `PUT …/sources/{sourceNo}` | 同上 | 同上（变更） |
| 3 | `GET …/sources` | `connector.read` | 零变化（不含自检结论） |
| 4 | `GET …/sources/{sourceNo}` | `connector.read` | `lastCheck` 数据来源改为通用留痕读面，**响应字段零变化** |
| 5 | `POST …/sources/{sourceNo}/checks` | `connector.source.manage` | 三类源不再返回 `1010C0011`；新增解析级拒绝 → 200 `passed=false` + `NETWORK_UNREACHABLE` |
| 6 | `POST /api/v1/connectors/internal/{connectorNo}/sources/{sourceNo}/reads` | `connector.internal.read` | 解析级拒绝 → 400 `1010C0010`（新增唯一出站变化）；其余零变化 |

**响应字段（既有，零变化）**：`SourceView(sourceNo, connectorNo, sourceType, endpoint, credentialMasked, createdAt, updatedAt, lastCheck?)`；`SourceCheckView(sourceNo, passed, failureType?, message, checkedAt)`；`SourceReadView(sourceNo, connectorNo, contractNo, success, summary, readAt)`。

## §6 错误码表（1010 段 +1）

| 码 | 常量 | 语义 | HTTP |
| --- | --- | --- | --- |
| `1010C0010` | `SOURCE_ENDPOINT_NOT_ALLOWED` | 源地址解析到受限网络（回环 / 内网 / 链路本地）或访问形态不被允许 | 400 |
| `1010C0011` | `SOURCE_ADAPTER_UNSUPPORTED`（既有） | 该源类型的适配承载未开放——**保留为防御性兜底**（四类全开放后理论不达） | 400 |
| `1010C0001` / `1010C0003` / `1010C0004` | 既有 | 参数非法（含 JDBC 危险参数）/ 防枚举同码同文 / 状态门槛 | 400 |
| `1010S0004` | 既有 | 凭据保护组件不可用 | 503 |
| `1000S9999` | 既有 | 适配器非预期异常（中性文案） | 502 |

**不启用**：`1010S0003`（3.6.4 预留）、`1010C0010` 以外的新码。

## §7 关键流程与适配器口径

### 7.1 自检流程（在 3.6.5 编排上插入 guard）

```
鉴权 → 属主 → 连接器 REGISTERED → 源归属 → 类型解析
  → SourceEndpointPolicy.normalize(库中地址)         （既有调用期复校）
  → credentialResolver.resolve(credentialRef)         （解密，密钥不可用 → 503 1010S0004）
  → SourceHostGuard.assertAllowed(type, endpoint, plain)   ← 本卡新增
       ├─ 拒绝：适配器零调用 → 留痕 SUCCESS? 否：200 passed=false + NETWORK_UNREACHABLE
  → adapter.check(new SourceRegistration(...))
  → 200 业务结论 + 留痕（source.check / reason_code）
```

**要点**：guard 失败是**业务结论**（自检 200 + 网络不可达），不是接口错误；留痕 `reason_code=NETWORK_UNREACHABLE`。

### 7.2 读取流程

```
鉴权（内部身份）→ 连接器 REGISTERED → 源归属 → 参数校验
  → 调用期复校 → 解密 → SourceHostGuard ← 本卡新增
       └─ 拒绝：400 1010C0010 + 留痕 source.denied（适配器零调用）
  → adapter.read(...) → 200 SourceReadView(success, summary)
```

### 7.3 数据库适配（`DatabaseSourceAdapter`）

| 项 | 契约 |
| --- | --- |
| URL | `jdbc:mysql://host[:port]/schema[?params]`；host 必填、schema 必填；用 `JdbcUrlPolicy` 解析（不依赖驱动解析） |
| 参数白名单 | 允许键（大小写不敏感）：`sslMode`、`useSSL`、`requireSSL`、`characterEncoding`、`serverTimezone`、`connectTimeout`、`socketTimeout`、`allowPublicKeyRetrieval`、`useUnicode`；**白名单外一律 400 `1010C0001`**（含 `allowLoadLocalInfile` / `allowUrlInLocalInfile` / `autoDeserialize` / `allowMultiQueries` / `allowMultiQueries` 等危险参数） |
| 凭据 | `用户名:密码`（首个 `:` 切分；密码可含 `:`；缺 `:` 或用户名空 → `INVALID_CREDENTIAL`） |
| 连接 | `DriverManager.getConnection(url, user, password)`；`setReadOnly(true)`；`setAutoCommit(true)`；关闭用 try-with-resources |
| 自检 | `SELECT 1` 成功即通过；**不执行任何写语句、不执行用户 SQL** |
| 读取 | `SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA = ?`（schema 来自 URL）；`summary = "database=<schema>;tables=<n>"`；**不含表名清单、不含行数据** |
| 失败映射 | 见 §7.6 |
| 驱动缺失 | `SQLException("No suitable driver")` → **非预期异常**（502 中性，不虚报网络不可达） |

### 7.4 对象存储适配（`ObjectStorageSourceAdapter`）

| 项 | 契约 |
| --- | --- |
| 凭据 | 完整预签名 URL（`http(s)://`；长度 ≤1024）；非 URL → `INVALID_CREDENTIAL` |
| 源地址 | 对象定位（`https://…` 或 `s3://` / `oss://`）；**不参与网络访问**，仅登记标识与展示；`s3://` / `oss://` 不做 DNS 解析（bucket 非主机） |
| 自检 | 对**凭据 URL** 发 `GET` + `Range: bytes=0-0`（兼容只签 GET 的预签名），不读 body；2xx 通过 |
| 读取 | 对凭据 URL 发 `GET`（不设 Range），**只读取响应头**（`Content-Length` / `Content-Type`），body 不落内存；`summary = "contentType=<…>;size=<…>"` |
| 主机校验 | guard 对**凭据 URL 主机**解析判定（Q5-A）；源地址为 http(s) 时也校验其主机 |
| 失败映射 | 401 → 凭据无效；403 + body 含 `SignatureDoesNotMatch` / `InvalidAccessKeyId` / `ExpiredToken` / `expired` → 凭据无效；403 其它 → 权限不足；**404 → 权限不足**（对象不可见，避免泄露存在性）；连接 / 超时 / 解析失败 → 网络不可达 |
| 诚实边界 | 预签名 URL 有有效期，过期后需变更凭据（写入 ADR-030 与操作说明） |

### 7.5 API 网关适配（`ApiGatewaySourceAdapter`）

| 项 | 契约 |
| --- | --- |
| 凭据 | 完整 `Authorization` 头值（如 `Bearer …`），原样放入请求头；**不解析、不拼接、不改写** |
| 自检 | `GET endpoint`（带 Authorization），不读 body；2xx 通过 |
| 读取 | `GET endpoint`，读取 body **最多 1MB 仅用于计字节数**，随后丢弃；`summary = "status=<code>;bytes=<n>;contentType=<…>"`；**不返回任何响应内容** |
| 失败映射 | 401 → 凭据无效；403 → 权限不足；连接 / 超时 → 网络不可达；404 / 其它非 2xx → 非预期异常 502（不猜） |

### 7.6 失败映射总表（Q4-A）

| 源类型 | `NETWORK_UNREACHABLE` | `INVALID_CREDENTIAL` | `INSUFFICIENT_PERMISSION` |
| --- | --- | --- | --- |
| 数据库 | SQLState `08xxx` / 连接超时 / 未知主机 | SQLState `28xxx` / MySQL `1045` | SQLState `42000` / `1142` / `1044` / `1227` |
| 对象存储 | IOException / 超时 / 未知主机 | 401；403 + 签名 / 凭证类关键字 | 403 其它；404 |
| API 网关 | IOException / 超时 / 未知主机 | 401 | 403 |

**不可归类** → `ConnectorAdapterProbeException` → 502 `1000S9999` 中性文案（既有出站），留痕 `reason=ADAPTER_ERROR`。

### 7.7 摘要与留痕口径（行为 14）

- 摘要**永不含**：数据库行数据、表名清单、对象内容、响应体、凭据明文、预签名 URL 原文；
- 留痕沿用既有五要素（operator / connectorNo / sourceNo / contractNo / success）+ `source.*` 动作常量，**零新增动作常量**；
- 失败留痕 `reason_code` 记三类枚举名（列宽 32 已够）。

### 7.8 安全与诚实边界

1. **零密码学实现**：对象存储不做 SigV4 / OSS 签名；凭据一律 `common-crypto`；
2. **SSRF 纵深**：受理期字面量判定（3.6.5）+ 调用期解析级判定（本卡）+ 默认拒绝内网；
3. **不默认降级**：`allow-private-endpoint` 默认 false；演示放开须留痕；
4. **JDBC 攻击面收敛**：参数白名单（默认拒绝）而非黑名单；
5. **诚实边界**：数据库仅 MySQL 协议；对象存储仅预签名 URL；API 网关仅 GET 只读；端到端取数归 3.6.8。

## §8 跨域装配改动

**零**：不改网关路由、不改交付域、不改 subject / did / catalog / contract 任何模块；`application.yml` 仅沿用既有 `ctds.connector.source.allow-private-endpoint`（本卡不新增配置键）。

## §9 测试清单（测试先行；映射规格验收标准）

| 编号 | 测试类（落位） | 覆盖 | 预期例数 |
| --- | --- | --- | --- |
| U1 | `connector-sdk` `DatabaseSourceAdapterTest` | `type()` / 成功自检 / 凭据格式非法 / 三类失败（测试桩 `Driver`）/ 读取摘要无数据 / 只读无写 | 9~11 |
| U2 | `connector-sdk` `JdbcUrlPolicyTest` | host / port / schema 提取（IPv4 / IPv6 / 域名）/ 参数白名单 / 危险参数拒绝 / 无 schema | 7~9 |
| U3 | `connector-sdk` `ObjectStorageSourceAdapterTest` | 成功 / 401 / 403 签名 / 403 权限 / 404 / 超时 / 摘要无内容 / 凭据非 URL | 9~11 |
| U4 | `connector-sdk` `ApiGatewaySourceAdapterTest` | 成功 / 401 / 403 / 超时 / Authorization 原样 / 摘要无 body | 7~9 |
| T1 | `ConnectorSourceCheckApiTest`（扩展） | 四类源自检均通过（不再 `1010C0011`） | +3 |
| T2 | `ConnectorSourceRegistrationApiTest`（扩展） | 三类源凭据密文非明文 / 掩码 / 1024 边界 | +4 |
| T3 | `ConnectorSourceAmendApiTest`（扩展） | 三类源变更留痕旧新 + 新配置自检通过 | +2 |
| T4 | `ConnectorSourceAdapterFailureApiTest`（扩展） | 数据库三类失败（真实容器：错密码 / 无权限 / 不可达）+ 对象存储 / API 桩三类失败 | +6 |
| T5 | `ConnectorSourceHostGuardApiTest`（新增） | 回环 / 内网 / 解析失败：自检 → `NETWORK_UNREACHABLE`；读取 → `1010C0010`；**适配器零调用** | 5~6 |
| T6 | `ConnectorSourceReadApiTest`（扩展） | 三类源读取摘要 + 五要素留痕 + 无本体 | +3 |
| T7 | `ConnectorSourceCheckApiTest` / 详情（扩展） | DB-90：`lastCheck` 与通用留痕读面等价（同一结论、字段不变） | +2 |
| T8 | 既有断言改写 | `ConnectorSourceCheckUnavailableApiTest`（3 例）与 `ConnectorSourceNoTouchTest`（1 例）中"未开放类型 → `1010C0011`"改为"三类源可自检 / 可读取"；**逐条留痕，属能力开放的必要同步** | 4 处 |

**测试基建**：数据库用 `SharedMySqlContainer` 建独立库 + 受限用户；对象存储 / API 网关用 JDK `HttpServer` 桩（沿 `GovExchangeHttpMappingTest` 范式，随机端口）；gurad 用测试 `SourceHostGuard` 或可注入解析器（避免依赖真实 DNS）。

## §10 ADR-030 大纲（编码段落盘，七字段齐备 = 门禁 `adrFieldsCheck`）

1. **背景**：规格行为 11 三类通用源 + 3.6.5 已交付政务源与登记面；SDK 无 JDBC / 对象存储依赖；
2. **决策**：适配器落 SDK / 零新依赖 / 对象存储预签名 URL / JDBC 参数白名单与只读探针 / 三类失败映射 / 调用边界解析级主机防护（DB-91）/ 留痕读面泛化（DB-90）/ `1010C0010`；
3. **备选方案**：MinIO SDK / AK-SK 签名 / 模拟源 / 登记期解析 / 维持直读；
4. **理由**：零供应链新增、零密码学实现、可证伪、与 3.6.5 同构；
5. **业务影响说明**（业务语言，5~7 条）；
6. **影响范围**：代码 / 数据（零迁移）/ 码位（+1）/ 权限（0）/ 依赖（0）/ 部署与门禁（0）；
7. **可替换性**：适配器按类型注册可替换；对象存储升级 AK/SK = 换客户端实现 + 走依赖审批；主机防护可替换为白名单解析器。

## §11 前置检查项（编码段第一动作，逐项实测留痕）

1. `mvn -B -ntp -pl connector-sdk,services/connector-service test` 基线复跑（65 / 206 例）并落日志；
2. 全仓定位 `1010C0011` 断言（预期 4 处）并逐条记录改写方案；
3. `1010C0010` 零占用复核（java / sql / yml / docs）；
4. `credential_ciphertext VARCHAR(2048)` 对 1024 明文的密文长度实测（超限则按变更流程扩列，**不私自改**）；
5. `connector-service` classpath 确认 `mysql-connector-j` 可被 `DriverManager` 自动注册；SDK 无驱动时的异常路径实测；
6. `SourceAdapterConfig` / `SourceAdapterRegistry` 装配点确认（同类型重复启动失败的既有断言）；
7. `allow-private-endpoint` 在 `ConnectorTestSupport` 与演示配置中的现状确认；
8. `sc-minio` 预签名 URL 实际长度与有效期实测（不记录密钥）；
9. MySQL `information_schema.TABLES` 摘要查询在受限用户下的返回行为实测；
10. 门禁配置零改动核对（`gates-config.json` / `run-gates.ps1` 无 diff）。

## §12 配置项清单

| 配置键 | 现状 | 本卡 |
| --- | --- | --- |
| `ctds.connector.source.allow-private-endpoint` | 默认 `false`（3.6.5） | 沿用，**不改默认**；演示 / 测试显式放开 |
| 连接 / 读取超时 | HTTP 客户端常量（3s / 5s）；数据库由 URL 参数控制 | 新增常量放各适配器包内，不新增配置键 |

## 确认记录（V0.9 → V1.0）

| 项 | 内容 |
| --- | --- |
| 提请确认 | 2026-10-29，Q1~Q9 建议口径均为 **A**，D1 建议 **不拆分**；请**一次确认**（或逐项裁定） |
| 编排师裁定 | ⏸ **待确认** |
| 确认后动作 | ① 本文件与 lofi 转 **V1.0** + 末节回填；② 任务卡关闭条件① 达成；③ 台账确认销项；④ 确认批推送 origin；⑤ 编码段以 §11 十项前置检查为第一动作，测试先行 |
