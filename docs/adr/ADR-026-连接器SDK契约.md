# ADR-026 连接器 SDK 契约

| 字段 | 内容 |
| --- | --- |
| 编号 | ADR-026 |
| 状态 | **已生效（2026-10-26 随 WBS-3.6.2 编码批落盘；同日评审循环 1 后修复批勘正 §2-2 / §2-4 / §2-5 / §3 / §4-1）**——上游两级设计 V1.0 经编排师一次确认（lofi Q7-A 明文本 ADR 随编码段建立，沿 ADR-022 先例） |
| 日期 | 2026-10-26 |
| 关联工作包 | WBS-3.6.2 连接器 SDK 骨架（编码批 + 修复批）；规格 `docs/specs/C-6.1-6.3-接入连接器.md` V1.0（行为 1~5 / 11~14 框架承载面）；设计 lofi V1.0 / **hifi V1.1** |

## 1. 背景

规格 C-6.1~6.3 V1.0 行为 2 规则 1：六项功能（身份 / 资源 / 产品 / 合约 / 交付 / 使用控制）以**连接器 SDK 框架承载**（3.6.2），业务能力由平台对应域提供；行为 11 规则 1：适配器**随连接器分发**（SDK 框架，3.6.2）。SDK 是参与方业务系统接入平台的标准化客户端库，将来独立分仓分发（ADR-002 §3-2）——接口签名即对外契约，需固化统一锚点供 3.6.5 / 3.6.6 适配器包与 3.6.8 集成包消费。

## 2. 决策

1. **模块形态**：单 Maven jar 模块 `connector-sdk/`（一级目录，挂根 pom `modules` + `dependencyManagement`）；**零新第三方依赖**（spring-web〔RestClient〕/ jackson / spring-boot-starter-test 均 Boot BOM 既有构件，`docs/dependencies.md` 无新登记项）；checkstyle 显式纳管（沿 9 个服务模块惯例）；无 spring-boot-maven-plugin（库不 repackage）。
2. **对外 API 面（最小化）**：`ConnectorSdk`（builder 入口）+ 六能力接口（`capability/`）+ 模型 record（`model/`，视图宽松映射 `@JsonIgnoreProperties`）+ SPI（`spi/`）+ `ConnectorException` / `SdkErrorType` / `ConnectorContext` / `TokenProvider` / `StaticTokenProvider` / `PlatformResponse`；**实现类（Default\*）、`PlatformClient`、`Credentials`、`Pages` 包级私有**——实现细节对参与方不可见。
   > **实现偏差留痕（对 hifi §2）**：设计稿的 `capability/impl/` 子包在编码中并入 `com.ctds.connector.sdk` 包——理由：跨包引用要求实现类与客户端通道公开化，与"最小对外 API 面"冲突；包级私有实现是更严格的封装（评审可核对，判定语义零变化）。
3. **身份与调用口径（Q2-A）**：全部平台调用经单一 baseUrl（演示期 = 网关）+ `Authorization: Bearer`（TokenProvider 接口，演示期静态令牌；刷新型归 3.9.1）；SDK **不自行注入 X-Ctds-\* 身份头**（网关剥离伪造头、按令牌认定权威身份——ADR-005 §3-7）；身份锚 = `ConnectorContext`（subjectNo + did 必填，connectorNo 占位——3.6.3 签发后注入）。
4. **六门面 → 平台端点映射（14 方法，路径级 + 字段级逐项对照源码核验〔修复批〕）**：身份 2（主体登记详情 / DID 状态）、资源 2（登记数据集 / 我的数据集〔分页信封〕）、产品 2（发布 / 我的产品〔分页信封〕）、合约 3（创建磋商 / 我的合约〔分页信封〕/ 详情）、交付 3（**API 型经网关交付面 `GET /api/v1/data-products/{productId}`** / 文件型 `POST /api/v1/deliveries`（幂等键**必填**、原值发送）/ 详情）、使用控制 2（执行使用〔同网关交付面，消费判定结果〕/ 用量汇总）；交付与使用腿组装凭据头三件（`X-Ctds-Contract-No` 原值 + `Purpose` / `Territory` 按 **RFC 3986 UTF-8 百分号编码**——与网关 `DeliveryCredentialGlobalFilter` 的 `UriUtils.decode` 配对，ADR-022 判定输入）。
5. **判定消费与 fail-closed（Q5-A / 规格行为 5，硬约束）**：判定唯一权威在平台合约域引擎——SDK **禁直调内部判定端点**（`usage-checks` 权限 `contract.internal.read` 仅服务身份 `gateway-internal` / `delivery-internal` 持有，业务角色均不持有），判定消费经网关交付链（`PolicyExecutionGlobalFilter` 转发前判定）自动完成；三态归一：放行返回结果 / 平台拒绝**原码原文透传且不重试** / 不可达或超时按 `UNREACHABLE` / `TIMEOUT` 中性分类**抛异常绝不放行**（成功响应缺 data 亦按 UNREACHABLE fail-closed）；全库无"跳过判定 / 强制放行 / 忽略错误继续"入参与分支。双向用例（放行 + 越界拒绝 `1008C0020`）+ 不可达 / 超时 / 缺 data / 非统一结构响应用例已入单测（`UsageControlFailClosedTest`）。
6. **错误语义（Q6-A）**：平台码透传（ConnectorException 携 platformCode / platformMessage，同码同文防枚举原样到达）；SDK 自身错三分类（UNREACHABLE / TIMEOUT / CONFIG）**无码值**；**1010 段码值不占用**（归 3.6.3 落定，规格 §7 Q9-A）。
7. **适配器 SPI（Q4-A）**：`SourceAdapter`（type / check / read）+ `SourceType` 四枚举（政务共享交换平台 / 数据库 / 对象存储 / API 网关）+ `SourceRegistration` **凭据引用制**（credentialRef——模型无明文凭据字段，编译期阻断；加密存储在平台侧 common 国密组件）+ `SourceCheckResult` 三类失败原因可枚举 + `read()` 契约注记**先判定后取数**；**零实现类**（归 3.6.5 / 3.6.6）。

## 3. 消费方

| 消费方 | 消费面 |
| --- | --- |
| 3.6.5 政务适配器 / 3.6.6 三类通用源适配器 | 实现 `SourceAdapter` SPI（凭据引用制 + 自检三查 + 先判定后取数注记为契约义务） |
| 3.6.3 注册与核验服务 | connectorNo 签发后注入 `ConnectorContext`；1010 段码值落定时本 SDK 错误语义不受影响（透传层零改动） |
| 3.6.8 模块测试与集成 | 六门面端到端受理验证（本卡为单测级：mock 平台响应 **41 例**，字段 / 枚举名取自平台真实 View / DTO）；**运行时字段级比对**（本卡已完成静态字段级对齐，运行期响应演变核验归本包） |
| 前端 / 参与方业务系统 | 经 `ConnectorSdk.builder()` 三必填件构建（baseUrl / tokenProvider / context） |

## 4. 诚实边界（不作不实承诺）

1. **网关唯一路由 = 交付面一处**（修复批勘正，沿 3.5.2 Q4-A）：V1.0 网关静态路由仅 `/api/v1/data-products/**`，且交付链强制 `X-Ctds-Contract-No`——**14 方法**中**仅 `requestApiDelivery` / `executeUsage`（API 型交付与执行使用）经网关可用**；其余 **12 方法**经网关不可达（身份 / 资源 / 合约读腿与写腿无路由 → 404；`data-products/mine` 与 `publishProduct` 命中路由但缺凭据头 → 400；文件型交付 / 交付详情 / 用量汇总无路由 → 404）；端到端受理验证归 3.6.8（网关扩路由随 3.6.7 / 3.6.8 与部署期统一设计，**本卡未改网关任何配置**）；
2. **演示期静态令牌**（生产令牌体系归 3.9.1，DB-03 信任边界沿承）；令牌为空由 SDK 本地 CONFIG fail-fast；
3. **模型字段宽松映射**（视图核心字段取平台响应子集 + `PageResult` 分页信封，未知字段忽略；字段级静态对齐已随修复批完成，运行期响应演变核验归 3.6.8）；
4. **连接器编号占位**（3.6.3 未开工；null 容忍 + 注入即可用）；
5. **CHG-C-1.2-V1.1"与连接器注册的真实联调"**归 3.6.3 交付后另卡（本 SDK 身份锚语义已按 C-1.2 V1.1 §4.5 下游消费方口径承载）。

## 5. 演进与替换触发

- **分仓分发**（ADR-002 §3-2）：需对外独立分发版本时单独建仓并版本化发布（接口签名即本 ADR 契约，冻结口径随之迁移）；
- **网关路由扩面**：四读腿经网关受理时零代码变更（baseUrl 已指向网关；路径即平台路径）；
- **刷新型令牌**（3.9.1）：`TokenProvider` 接口零变更，新增实现类即可；
- **注册 / 升级 / 注销门面**（3.6.3 / 3.6.4 交付后）：按需增补能力接口（版本演进，不破坏既有签名）；
- ADR-016 §6 消费方补记评估：SDK 为平台端点的**外部消费方**（非服务间内部消费），暂不补记 ADR-016 消费方表（该表口径 = 内部服务消费方；3.6.3 建立平台侧连接器端点时再评估）。
