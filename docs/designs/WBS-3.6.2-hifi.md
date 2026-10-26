# WBS-3.6.2 高保真设计：连接器 SDK 骨架（V0.9，2026-10-26）

> 状态：**V0.9（待编排师一次确认，确认后 = 编码契约即时生效）**——与 lofi V0.9 一并提交（≤1 天任务卡，章程 2.6.3）。
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
├─ StaticTokenProvider          静态令牌默认实现（演示期）
├─ PlatformClient               平台调用唯一通道（RestClient 封装：Bearer / 超时 / 统一响应解析 / 异常转换）
├─ PlatformResponse<T>          平台统一响应同构解析（record：code / message / data / traceId）
├─ ConnectorException           连接器异常（platformCode / platformMessage 可空 + SdkErrorType）
├─ SdkErrorType                 SDK 自身错三分类（UNREACHABLE / TIMEOUT / CONFIG；无码值）
├─ capability/                  六门面接口（对外契约）
│   ├─ IdentityCapability       查本主体登记详情 / 查 DID 状态
│   ├─ ResourceCapability       登记数据集 / 查我的数据集
│   ├─ ProductCapability        发布产品 / 查我的产品
│   ├─ ContractCapability       创建磋商 / 查我的合约 / 查合约详情
│   ├─ DeliveryCapability       发起交付（幂等键自动）/ 查交付详情
│   └─ UsageControlCapability   执行使用（凭据 + 消费判定，三态）/ 查用量汇总
├─ capability/impl/             Default* 六默认实现（薄封装 PlatformClient，零业务判定）
├─ model/                       命令与视图 record（~10 个，视图字段 = 平台响应 data 子集）
└─ spi/                         适配器 SPI（零实现类，归 3.6.5 / 3.6.6）
    ├─ SourceAdapter            { SourceType type() / SourceCheckResult check(SourceRegistration) / SourceReadResult read(SourceRegistration, SourceReadRequest) }
    ├─ SourceType               四枚举：GOV_EXCHANGE / DATABASE / OBJECT_STORAGE / API_GATEWAY
    ├─ SourceRegistration       源接入登记（sourceType / endpoint / **credentialRef 凭据引用** / ownerConnectorNo——模型无明文凭据字段）
    ├─ SourceCheckResult        自检结果（passed / failureType：NETWORK_UNREACHABLE / INVALID_CREDENTIAL / INSUFFICIENT_PERMISSION）
    └─ SourceReadRequest / SourceReadResult
```

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
List<DatasetView>     listMyDatasets();
ProductView           publishProduct(CreateProductCommand cmd);                     // 行为 2 产品腿
List<ProductView>     listMyProducts();
ContractView          createNegotiation(CreateContractCommand cmd);                 // 行为 2 合约腿
List<ContractView>    listMyContracts();
ContractView          getContract(String contractNo);
DeliveryView          requestDelivery(DeliveryRequest req);   // 行为 4：幂等键头自动生成与复用
DeliveryView          getDelivery(String deliveryNo);
UsageResult           executeUsage(UsageRequest req);         // 行为 5：三态（放行/拒绝透传/不可达 fail-closed）
UsageSummaryView      getUsageSummary(String contractNo);
```

## §4 调用链与 fail-closed 语义（Q5-A，规格行为 5 硬约束落点）

```
门面方法
 → PlatformClient.execute(...)：组装 Bearer 头 + 路径 + 载荷（交付/使用腿另附 X-Idempotency-Key
   与 X-Ctds-Contract-No / X-Ctds-Purpose / X-Ctds-Territory 凭据头——网关交付面判定输入）
 → 三态归一：
   ① 2xx + 平台统一响应 code=成功 → 解析 data 返回视图
   ② 非 2xx（403/404/409/400/429…）→ ConnectorException(platformCode, platformMessage)
      ——原码原文透传、不重试、不触源（判定拒绝在平台侧先行，行为 14 规则 1）
   ③ IO / 超时 → ConnectorException(UNREACHABLE / TIMEOUT)——绝无"成功"返回路径
 SDK 全库无"跳过判定 / 强制放行 / 忽略错误继续"入参与分支（评审核对项）
```

## §5 端点映射表（六门面 → 平台既有端点；立卡盘点实测，编码段逐项复核）

| 门面方法 | 平台端点 | 附带头 |
| --- | --- | --- |
| querySubject | GET `/api/v1/subject/registrations/{subjectNo}` | Bearer |
| resolveDid | GET `/api/v1/did/{did}` | Bearer |
| registerDataset | POST `/api/v1/data-spaces/{spaceId}/datasets` | Bearer |
| listMyDatasets | GET `/api/v1/datasets/mine` | Bearer |
| publishProduct | POST `/api/v1/data-products` | Bearer |
| listMyProducts | GET `/api/v1/data-products/mine` | Bearer |
| createNegotiation | POST `/api/v1/contracts` | Bearer |
| listMyContracts | GET `/api/v1/contracts/mine` | Bearer |
| getContract | GET `/api/v1/contracts/{contractNo}` | Bearer |
| requestDelivery | POST `/api/v1/deliveries` | Bearer + `X-Idempotency-Key`（自动）+ 三凭据头 |
| getDelivery | GET `/api/v1/deliveries/{deliveryNo}` | Bearer |
| executeUsage | POST `/api/v1/deliveries`（deliveryType=API；判定由网关交付链在转发前完成——**SDK 禁直调 usage-checks**） | Bearer + `X-Idempotency-Key` + 三凭据头（X-Ctds-Contract-No / Purpose / Territory） |
| getUsageSummary | GET `/api/v1/contracts/{contractNo}/usage-summary` | Bearer |

> **诚实边界（登记 ADR-026 演进项）**：V1.0 网关仅暴露交付面路由（3.5.2 Q4-A）——交付 / 使用两腿经网关即通；四读腿端点路径按平台既有路径封装，端到端受理验证归 3.6.8（网关扩路由随 3.6.7 / 3.6.8 与部署期统一设计，**本卡不改网关配置**）。

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
| T1 | builder 缺 baseUrl / tokenProvider | 构建 fail-fast（CONFIG，不静默降级） |
| T2~T7 | 六门面各 ≥1 正向 | MockRestServiceServer 断言：路径 / Bearer 头 / 载荷字段 / 视图解析（对照 §5 映射表） |
| T8 | requestDelivery 幂等键 | `X-Idempotency-Key` 自动生成且同请求复用 |
| T9 | **executeUsage 放行**（行为 5 标准 1） | 2xx → 成功结果（双向用例之一） |
| T10 | **executeUsage 越界拒绝**（行为 5 标准 2） | mock 403 + `1008C0020` → ConnectorException 原码原文 + **调用计数 = 1（不重试）**（双向用例之二） |
| T11 | **executeUsage 不可达**（行为 5 规则 2） | mock 网络错误 → UNREACHABLE，断言绝无成功返回（fail-closed） |
| T12 | executeUsage 超时 | mock 延迟 → TIMEOUT |
| T13 | 平台非 2xx 统一响应透传 | 404 / 409（合约状态门槛）原码原文到达 |
| T14 | SPI 契约 | SourceType 四枚举齐；SourceCheckResult 三类失败原因枚举齐 |
| T15 | **凭据引用制**（行为 12 规则 2） | SourceRegistration 字段集反射断言——无任何明文凭据字段 |
| T16 | ConnectorContext 占位 | connectorNo=null 容忍 + 构建成功 |

## §8 ADR-026 大纲（编码段落盘）

门面与端点映射 / 身份锚与令牌口径（Q2-A）/ 判定消费与 fail-closed 三态（Q5-A）/ SPI 与凭据引用制（Q4-A）/ 错误透传不占码值（Q6-A）/ 诚实边界与演进（网关扩路由、CHG-C-1.2-V1.1 真实联调另卡、分仓分发触发条件〔ADR-002 §3-2〕、3.6.5 / 3.6.6 / 3.6.8 消费面）/ ADR-016 §6 消费方补记评估。

## §9 前置检查项（编码段第一动作，逐项实测留痕）

① Boot 3.5.16 下 `spring-web` RestClient 可用性与版本核验；② MockRestServiceServer 对 RestClient 的绑定 API 核验（`MockRestServiceServer.bindTo(restClient)`）；③ 根 pom 挂载两处（modules + dependencyManagement）改动 = 仅增行；④ checkstyle 对新模块可解析（`mvn -B -ntp checkstyle:check` 单模块跑通，注意 `-Dmaven.multiModuleProjectDirectory` 指仓库根惯例）；⑤ §5 端点映射表逐行对照六域 Controller 源码复核（路径 / 请求字段 / 响应结构）；⑥ 全仓门禁基线 GREEN 复跑（沿 3.5.7 基线）；⑦ 台账沿承三件确认（DB-64 / F-W1 / 8086 停用口径不变）。

---

## 确认记录（V0.9 → V1.0）

> （待编排师一次确认后回填：Q1~Q7 采纳口径 + D1 表态 + 确认时点；本文件与 lofi 同批转 V1.0 = 编码契约即时生效；确认批推送 origin，编码段测试先行。）
