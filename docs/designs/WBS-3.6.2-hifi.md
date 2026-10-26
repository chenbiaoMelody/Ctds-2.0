# WBS-3.6.2 高保真设计：连接器 SDK 骨架（V1.1，2026-10-26）

> 状态：**V1.1（修复批勘正，2026-10-26）**——V1.0 经编排师一次确认（**Q1~Q7 全采建议口径 A + D1 不拆分**，确认记录落本文件末节）后进入编码；评审循环 1 发现"路径级复核已过、字段级契约未对齐平台真实 View"等 P1，修复批按平台源码逐项勘正本文件 §2 / §3 / §4 / §5 / §7（**业务判定语义零变更**：六门面 / fail-closed 三态 / 凭据引用制 / 零 1010 码值）。
> 输入：lofi V0.9 + 任务卡 + 立卡前素材盘点（一路只读 Explore 子智能体端点盘点 + 定点核验，结论见任务卡 §四 立卡会话行）。
> 口径纪律：本文件只从规格 C-6.1~6.3 V1.0（行为 1~5 / 11~14 框架承载面）+ ADR-002 / 005 / 022 + WBS 行 301 派生；**未在设计中补需求**；一切"规格未定项"登记为演进项。

## §1 模块装配契约（Q1-A）

1. **模块**：`connector-sdk/`（仓库一级目录，ADR-002 §3-1 规划位）；`pom.xml`：`groupId=com.ctds`，`artifactId=connector-sdk`，`version=2.0.0-SNAPSHOT`，`packaging=jar`（默认省略）；parent = 根 `ctds-platform`，**`<relativePath>../pom.xml</relativePath>`（一级目录，区别于 services/* 的 `../../`）**。
2. **根 pom 两处挂载**：`<modules>` 增 `<module>connector-sdk</module>`（置于 std-adapter 之后、services 之前，按"库—服务"分层序）；`<dependencyManagement>` 增 `com.ctds:connector-sdk : ${project.version}`（供 3.6.5 / 3.6.6 / 3.6.8 消费）。
3. **依赖（零新第三方）**：`spring-web`（RestClient，版本 Boot BOM 管理）；`jackson-databind`（版本 Boot BOM；RestClient JSON 转换）；test：`spring-boot-starter-test`（含 MockRestServiceServer / AssertJ / JUnit5）。**`docs/dependencies.md` 无新登记项**（无新坐标、无新版本决策）。
4. **构建插件**：`build/plugins` 显式声明 `maven-checkstyle-plugin`（无版本，走根 pluginManagement；沿 9 个服务模块惯例）；**不声明 spring-boot-maven-plugin**（库不 repackage，沿 common/crypto 先例）。
5. **无库、无迁移、无 Flyway、无 MyBatis、无 Spring 自动装配**（SDK 是纯客户端库，零 `@Configuration`/autoconfig 文件——参与方以 builder 手工构建）。

## §2 包结构与类清单（骨架 = 对外契约，签名冻结口径见 ADR-026）

```
com.ctds.connector.sdk
├─ ConnectorSdk                 入口门面（builder 构建；聚合六能力）
├─ ConnectorContext             身份锚（record：subjectNo / did / connectorNo〔可空占位，3.6.3 签发后注入〕）
├─ TokenProvider                令牌接口 { String token() }
├─ StaticTokenProvider          静态令牌默认实现（演示期；空令牌构造即 CONFIG）
├─ PlatformClient               平台调用唯一通道（包级私有：Bearer / 超时 / 统一响应解析 / 异常转换）
├─ PlatformResponse<T>          平台统一响应同构解析（record：code / message / data / traceId）
├─ ConnectorException           连接器异常（platformCode / platformMessage 可空 + SdkErrorType）
├─ SdkErrorType                 SDK 自身错三分类（UNREACHABLE / TIMEOUT / CONFIG；无码值）
├─ Credentials                  包级私有：凭据头三件组装 + 幂等键 / 文件标识本地校验（网关字符集口径）
├─ Pages                        包级私有：分页参数本地校验（pageNum 1~10000 / pageSize 1~100）
├─ capability/                  六门面接口（对外契约）
│   ├─ IdentityCapability       查本主体登记详情 / 查 DID 状态
│   ├─ ResourceCapability       登记数据集 / 查我的数据集（分页）
│   ├─ ProductCapability        发布产品 / 查我的产品（分页）
│   ├─ ContractCapability       创建磋商 / 查我的合约（分页）/ 查合约详情
│   ├─ DeliveryCapability       API 型交付（网关交付面）/ 文件型交付（幂等键必填）/ 查交付详情
│   └─ UsageControlCapability   执行使用（凭据 + 消费判定，三态）/ 查用量汇总
├─ Default*                    六默认实现（包级私有，薄封装 PlatformClient，零业务判定）
├─ model/                       命令与视图 record（15 个，视图字段 = 平台响应 data 子集 + PageResult 分页信封）
└─ spi/                         适配器 SPI（零实现类，归 3.6.5 / 3.6.6）
    ├─ SourceAdapter            { SourceType type() / SourceCheckResult check(SourceRegistration) / SourceReadResult read(SourceRegistration, SourceReadRequest) }
    ├─ SourceType               四枚举：GOV_EXCHANGE / DATABASE / OBJECT_STORAGE / API_GATEWAY
    ├─ SourceRegistration       源接入登记（sourceType / endpoint / **credentialRef 凭据引用** / ownerConnectorNo——模型无明文凭据字段；toString 掩码）
    ├─ SourceCheckResult        自检结果（passed / failureType：NETWORK_UNREACHABLE / INVALID_CREDENTIAL / INSUFFICIENT_PERMISSION）
    └─ SourceReadRequest / SourceReadResult
```

> **修复批勘正（§2）**：`capability/impl/` 子包并入 `com.ctds.connector.sdk` 包（包级私有实现 = 最小对外 API 面；ADR-026 §2-2 留痕）；新增包级私有 `Credentials` / `Pages`；`model/` 由 ~10 个增至 15 个（新增 `PageResult` / `ProductAccessRequest` / `FileDeliveryRequest`，删除 `DeliveryRequest`）。

## §3 关键接口签名（Java 17，record 模型；全文以编码段落地为准，本节为契约基准）

```java
// 入口（builder 模式，主流写法）
ConnectorSdk sdk = ConnectorSdk.builder()
    .baseUrl("http://127.0.0.1:8086")          // 必填，缺失 → 构建 fail-fast（CONFIG）
    .tokenProvider(new StaticTokenProvider(token)) // 必填
    .context(new ConnectorContext(subjectNo, did, null)) // connectorNo 占位可空
    .build();                                   // connectTimeout 默认 2s / readTimeout 默认 10s，可覆盖

// 六门面（每方法即规格框架承载面；全部抛 ConnectorException，受检与否 = 运行时异常）
SubjectRegistrationView querySubject(String subjectNo);   // 行为 3：查验本主体
DidStatusView         resolveDid(String did);             // 行为 3：DID 状态（可解析、状态有效）
DatasetView           registerDataset(String spaceId, RegisterDatasetCommand cmd); // 行为 2 资源腿
PageResult<DatasetView> listMyDatasets(int pageNum, int pageSize);   // 平台分页信封（1~10000 / 1~100）
ProductView           publishProduct(CreateProductCommand cmd);       // 行为 2 产品腿
PageResult<ProductView> listMyProducts(int pageNum, int pageSize);
ContractView          createNegotiation(CreateContractCommand cmd);   // 行为 2 合约腿
PageResult<ContractView> listMyContracts(int pageNum, int pageSize);
ContractView          getContract(String contractNo);
ProductView           requestApiDelivery(ProductAccessRequest req);   // 行为 4：API 型经网关交付面
DeliveryView          requestFileDelivery(FileDeliveryRequest req);   // 行为 4：文件型（幂等键必填）
DeliveryView          getDelivery(String deliveryNo);
UsageResult           executeUsage(UsageRequest req);         // 行为 5：三态（放行/拒绝透传/不可达 fail-closed）
UsageSummaryView      getUsageSummary(String contractNo);
```

> **修复批勘正（§3）**：① 三个 `/mine` 查询改为**平台分页信封** `PageResult<T>` + 分页参数（平台真实返回 `{list,total,pageNum,pageSize,totalPages}`，原 `List` 绑定必失败）；② 交付门面拆三条腿——API 型 / 文件型 / 详情（原单一 `requestDelivery(DeliveryRequest)` 的请求体与平台交付域 DTO 不符）；③ **幂等键不再自动生成**（平台幂等 = 发起人 + 键，自动生成会破坏重试语义）——改由调用方必填并在本地按平台字符集校验；④ 命令模型对齐平台真实 DTO：`CreateContractCommand(productId, templateNo, templateVersionNo, clauseValues)`（原 purpose/territory 字段平台无）。

## §4 调用链与 fail-closed 语义（Q5-A，规格行为 5 硬约束落点）

```
门面方法
 → PlatformClient.get/post(Function<UriBuilder,URI>, ...)：组装 Bearer 头 + 路径（pathSegment 编码）+ 载荷；
   交付/使用腿另附凭据头三件 X-Ctds-Contract-No / Purpose / Territory（网关交付面判定输入，
   purpose/territory 按 RFC 3986 UTF-8 百分号编码——与网关 UriUtils.decode 配对；空值不带头）；
   文件型交付另附 X-Idempotency-Key（调用方必填，原值发送）
 → 三态归一：
   ① 2xx + 平台统一响应 code=成功 且 data 非空 → 解析 data 返回视图
   ② 非 2xx（403/404/409/400/429…）或 code≠成功 → ConnectorException(platformCode, platformMessage)
      ——原码原文透传、不重试、不触源（判定拒绝在平台侧先行，行为 14 规则 1）
   ③ IO / 超时 / 响应不可解析 / 成功响应缺 data → ConnectorException(UNREACHABLE / TIMEOUT / CONFIG)
      ——绝无"成功"返回路径
 SDK 全库无"跳过判定 / 强制放行 / 忽略错误继续"入参与分支（评审核对项）
```

## §5 端点映射表（六门面 → 平台既有端点；立卡盘点实测，编码段逐项复核）

| 门面方法 | 平台端点 | 附带头 |
| --- | --- | --- |
| querySubject | GET `/api/v1/subject/registrations/{subjectNo}` | Bearer |
| resolveDid | GET `/api/v1/did/{did}` | Bearer |
| registerDataset | POST `/api/v1/data-spaces/{spaceId}/datasets` | Bearer |
| listMyDatasets | GET `/api/v1/datasets/mine?pageNum=&pageSize=` | Bearer |
| publishProduct | POST `/api/v1/data-products` | Bearer |
| listMyProducts | GET `/api/v1/data-products/mine?pageNum=&pageSize=` | Bearer |
| createNegotiation | POST `/api/v1/contracts` | Bearer |
| listMyContracts | GET `/api/v1/contracts/mine?pageNum=&pageSize=` | Bearer |
| getContract | GET `/api/v1/contracts/{contractNo}` | Bearer |
| requestApiDelivery | **GET `/api/v1/data-products/{productId}`**（网关交付面；`PolicyExecutionGlobalFilter` 转发前判定，403 `1008C0020` 真实可发生） | Bearer + 三凭据头 |
| requestFileDelivery | POST `/api/v1/deliveries`（deliveryType=FILE；判定由交付域 check 一次——**SDK 禁直调 usage-checks**） | Bearer + `X-Idempotency-Key`（**必填**）+ 三凭据头 |
| getDelivery | GET `/api/v1/deliveries/{deliveryNo}` | Bearer |
| executeUsage | **GET `/api/v1/data-products/{productId}`**（同 requestApiDelivery；消费判定结果） | Bearer + 三凭据头（X-Ctds-Contract-No / Purpose / Territory） |
| getUsageSummary | GET `/api/v1/contracts/{contractNo}/usage-summary` | Bearer |

> **诚实边界（修复批勘正，登记 ADR-026 §4-1）**：V1.0 网关**唯一静态路由 = `/api/v1/data-products/**`**（3.5.2 Q4-A），且交付链强制 `X-Ctds-Contract-No`（缺失即 400）——**14 方法**中**仅交付 / 使用两腿（`requestApiDelivery` / `executeUsage`）经网关可用**；其余 **12 方法**经网关不可达（身份 / 资源 / 合约四读腿与写腿无路由 → 404；`data-products/mine` 命中路由但缺凭据头 → 400；文件型交付与 `getDelivery`、用量汇总无路由 → 404），端到端受理验证归 3.6.8（网关扩路由随 3.6.7 / 3.6.8 与部署期统一设计，**本卡不改网关配置**）。

## §6 配置项

| 配置 | 必填 | 默认 | 语义 |
| --- | --- | --- | --- |
| baseUrl | 是 | ——（缺失 fail-fast） | 平台入口（演示期 = 网关 8086） |
| tokenProvider | 是 | —— | Bearer 令牌来源（静态默认实现；刷新型归 3.9.1 后演进） |
| context | 是 | —— | 身份锚元数据（connectorNo 可空占位） |
| connectTimeout | 否 | 2s | 连接超时（超时 → TIMEOUT） |
| readTimeout | 否 | 10s | 读超时（超时 → TIMEOUT） |

## §7 测试清单（测试先行；mock 平台响应，零运行态依赖）

| # | 用例 | 断言要点 |
| --- | --- | --- |
| T1 | builder 缺 baseUrl / tokenProvider / context | 构建 fail-fast（CONFIG，不静默降级） |
| T2~T7 | 六门面各 ≥1 正向 | MockRestServiceServer 断言：路径 / Bearer 头 / 载荷字段 / 视图解析（对照 §5 映射表；**响应体字段与枚举名逐项取自平台真实 View / DTO**） |
| T8 | 文件型交付幂等键 | **必填**（缺失 / 非法 → CONFIG 且不发起调用）+ 原值头发送（不编码；平台按 `[A-Za-z0-9._-]{1,128}` 校验） |
| T9 | **executeUsage 放行**（行为 5 标准 1） | 2xx → 成功结果（双向用例之一） |
| T10 | **executeUsage 越界拒绝**（行为 5 标准 2） | 网关交付面 mock 403 + `1008C0020` → ConnectorException 原码原文 + **调用计数 = 1（不重试）**（双向用例之二） |
| T11 | **executeUsage 不可达**（行为 5 规则 2） | mock 网络错误 → UNREACHABLE，断言绝无成功返回（fail-closed）；**另覆盖判定服务不可达**：网关 503 + `1009S0001` → 原码原文透传（绝不放行） |
| T12 | executeUsage 超时 | mock `SocketTimeoutException` → TIMEOUT |
| T13 | 平台非 2xx 统一响应透传 | 404 / 409（合约状态门槛）原码原文到达 |
| T14 | SPI 契约 | SourceType 四枚举齐；SourceCheckResult 三类失败原因枚举齐 |
| T15 | **凭据引用制**（行为 12 规则 2） | SourceRegistration 字段集反射断言——无任何明文凭据字段 + toString 掩码断言 |
| T16 | ConnectorContext 占位 | connectorNo=null 容忍 + 构建成功 + 超时可覆盖 |
| T17 | 策略要素凭据头编码 | purpose/territory **RFC 3986 UTF-8 百分号编码**断言（如 `%E6%95%B0%E6%8D%AE%E5%88%86%E6%9E%90`）；空值不带头 |
| T18 | 成功响应缺 data | code=0 但 data 缺失 → UNREACHABLE（fail-closed，绝不放行） |
| T19 | 拒绝响应非统一结构 | 403 + text/plain → 中性分类 UNREACHABLE（不吞异常、不伪造码值） |
| T20 | 令牌缺失 | `TokenProvider` 返回空 → CONFIG 且不发起调用 |
| T21 | 分页参数越界 | pageNum<1 / pageSize>100 → CONFIG 且不发起调用 |

## §8 ADR-026 大纲（编码段落盘）

门面与端点映射 / 身份锚与令牌口径（Q2-A）/ 判定消费与 fail-closed 三态（Q5-A）/ SPI 与凭据引用制（Q4-A）/ 错误透传不占码值（Q6-A）/ 诚实边界与演进（网关扩路由、CHG-C-1.2-V1.1 真实联调另卡、分仓分发触发条件〔ADR-002 §3-2〕、3.6.5 / 3.6.6 / 3.6.8 消费面）/ ADR-016 §6 消费方补记评估。

## §9 前置检查项（编码段第一动作，逐项实测留痕）

① Boot 3.5.16 下 `spring-web` RestClient 可用性与版本核验；② MockRestServiceServer 对 RestClient 的绑定 API 核验（`MockRestServiceServer.bindTo(restClient)`）；③ 根 pom 挂载两处（modules + dependencyManagement）改动 = 仅增行；④ checkstyle 对新模块可解析（`mvn -B -ntp checkstyle:check` 单模块跑通，注意 `-Dmaven.multiModuleProjectDirectory` 指仓库根惯例）；⑤ §5 端点映射表逐行对照六域 Controller 源码复核（路径 / 请求字段 / 响应结构）；⑥ 全仓门禁基线 GREEN 复跑（沿 3.5.7 基线）；⑦ 台账沿承三件确认（DB-64 / F-W1 / 8086 停用口径不变）。

---

## 确认记录（V0.9 → V1.0）

> **✅ 已签署（2026-10-26，编排师 AskUserQuestion 回复"一次确认"）**：**Q1~Q7 全采建议口径 A + D1 不拆分**（逐项口径见 lofi 确认记录）；本文件即**编码契约**——实现与本文件不一致 = 打回项（任务卡关闭条件②④ 核对面）。
> 确认批提交并推送 origin；编码段测试先行，第一动作 = §9 前置检查项七项逐项实测留痕。
