# WBS-3.5.2 高保真设计：API 网关交付服务（V1.0，2026-10-16）

> 状态：**V1.0（编码契约，已确认生效）**——编排师 2026-10-16 一次确认 **Q1~Q9 全采建议口径 A + D1 不拆分**（确认记录落本文件末节）；V0.9 → V1.0 变更 = 版本头与确认状态更新，**正文零改动**。
> 输入：lofi V0.9 + 任务卡 + 立卡前素材盘点（两路独立只读子智能体 + 定点补充，结论见任务卡 §一 表）。
> 口径纪律：本文件只从规格 C-5.1-5.2 V1.0（行为 1 / 2 / 7）+ ADR-005 / ADR-016 / ADR-020 / ADR-021 + WBS 行 289 派生；**未在设计中补需求**；一切"规格未定项"登记为未定义项或演进项。

## §1 网关装配契约（Q1-A / Q8-A）

1. **模块**：`services/gateway-service`（本地 `pom.xml`，parent `ctds-platform` + `<relativePath>../../pom.xml</relativePath>`，沿 7 个既有服务骨架）；**Reactive**（`spring-boot-starter-webflux`）；**端口 8086**；**无库、无迁移、无 mybatis、无 flyway**。
2. **依赖（全部登记 `docs/dependencies.md`，四步流程）**：`spring-cloud-starter-gateway`（落实 ADR-005 已批准方案 A）+ 根 `pom.xml` 新增 `spring-cloud-dependencies` BOM import（`<type>pom</type><scope>import</scope>`，本仓首个；版本按 Spring Cloud 官方兼容矩阵核验后锁定）+ `spring-boot-starter-webflux` + `spring-boot-starter-oauth2-resource-server`（common-auth 以 `<optional>` 屏蔽，网关须显式引入——`common/auth/pom.xml:31-43` 注释原文点名 3.5.2）+ `common-{errorcode,auth,logging,api 相关}`（按实测坐标引入）。
3. **鉴权装配（零新研发）**：置 `ctds.auth.gateway.enabled=true`，并使应用具备反应式安全自动装配（`@EnableWebFluxSecurity`）——否则 `GatewayAuthAutoConfiguration` fail-fast（`IllegalStateException`，消息含 `ServerHttpSecurity`）；JWT 密钥来源**二选一强校验**（`ctds.auth.jwt.secret` ≥32 字节 或 `ctds.auth.jwt.jwk-set-uri`；`issuer` 可配）；`ctds.auth.gateway.permit-paths` 仅放行健康检查类路径，**业务交付路径一律 `authenticated()`**。
4. **过滤器编排（顺序不可变）**：`HeaderStripFilter`（order **-200**，无条件剥离入站 `X-Ctds-Subject`/`X-Ctds-Roles`）→ 安全链（JWT 校验 + 出口 `GatewayAuthEntryPoint`）→ `ContextHeaderInjectFilter`（order **-90**，`sub`→`X-Ctds-Subject`、`roles`→`X-Ctds-Roles`，注入前二次 remove）；本卡**不改 common-auth 任何一行**。
5. **traceId（Q8-A）**：网关内新增反应式 traceId 过滤器（**order 最前，-300**）：入站 `X-Trace-Id` 存在则透传、否则生成；写入 Reactor Context + 响应头 `X-Trace-Id`；未认证 / 限流 / 拒绝三类错误响应体由网关自建写出（`ApiResult`，`traceId` 取本过滤器值）。**向 `common` 提沉淀建议**（沿 DB-26/DB-30 条件触发式口径），本卡不改 common。
6. **路由**：**静态配置**（`ctds.gateway.routes`：`id` / `uri`（环境变量注入，沿既有 `CTDS_<服务>_<目标>_BASEURL` 惯例）/ `predicates: Path` / `filters`）；**不引入 Nacos 服务发现**（登记演进项）；V1.0 仅暴露**交付面路由**（见 §5），其余端点不经网关（诚实边界）。

## §2 交付调用链路顺序（规格行为 2 规则 2，固定不可调）

```
入站请求
 → [ -300 反应式 traceId 过滤器 ]
 → [ -200 HeaderStripFilter：剥离伪造身份头 ]
 → [ 安全链：JWT 校验（失败 → 401 + 1000C0002，traceId 非 "-"）]
 → [ -90 ContextHeaderInjectFilter：注入权威 X-Ctds-Subject / X-Ctds-Roles ]
 → [ 交付面白名单（网关 GlobalFilter order -300）：routeId 不在白名单 → 404 + 1009C0004，不进入后续链路 ]
 → [ 限流（主体 + 接口令牌桶；超阈 → 429 + 1009C0002，不触达上游）]
 → [ 凭据解析：合约编号头（缺失/非法 → 400 + 1009C0001）]
 → [ 策略执行点（转发前）：调用合约域内部判定端点 ]
 → [ 交付域规则校验：发起人 = 合约需求方（非需求方 → 403 + 1009C0003）]
 → [ 转发数据源（上游服务）]
 → [ 计量埋点：放行产生 1 条计量事件（拒绝 0 条）；拒绝留痕 ]
```

- **判定在转发之前**：越界一律**不触达数据源**（验收标准 2）；
- **判定不缓存**：ADR-020 §2.8「放行即计数」——缓存结论会导致"未计数即放行"，**禁止**；
- **判定服务不可达/超时 → fail-closed**（503 + `1009S0001`，**不放行**）；超时阈值配置化（默认 2s，ADR-020 §2.8 授权消费方定）。

## §3 限流口径（Q2-A，规格行为 2 规则 5 / ADR-005 §3-6）

1. **维度 = 调用主体 + 接口**：key = `<主体编号>#<routeId>#<HTTP 方法 + 路径模板>`（用**路径模板**而非原始路径，避免路径参数造成 key 爆炸）；未认证请求在安全链已被 401，不进入限流。
2. **算法 = 令牌桶（进程内）**：容量 + 补充速率可配置（`ctds.gateway.rate-limit.{enabled,default-capacity,default-refill-per-second,routes.<routeId>.*}`）；`enabled=false` 可关（默认**开**）。
3. **超限出站**：HTTP **429** + `1009C0002`（可附 `Retry-After`），**不触达上游、不产生计量事件**、**不影响业务配额计数**。
4. **边界声明（诚实）**：进程内实现 = 单实例语义；多实例部署前须换分布式实现（登记演进项；沿 `common-idempotency` "memory 模式"先例与既有边界表述）；正式吞吐承诺归 **4.2.3**（≥2000 TPS / P99<500ms），本卡只做正确性验证与轻量基准留痕。

## §4 策略执行点接入口径（Q3-A，ADR-020 §2.8）

### 4.1 合约域：新增内部判定端点（最小改动）

| 项 | 契约 |
| --- | --- |
| 路径 | `POST /api/v1/contracts/internal/usage-checks`（沿 `/api/v1/{域}/internal/...` 惯例） |
| 权限 | `@RequirePermission("contract.internal.read")`；`contract-service/application.yml` 增 1 行角色映射（服务身份角色 → `contract.internal.read`） |
| 请求体 | `{ contractNo, requesterNo, actionType(=USE), purpose?, territory? }` |
| 处理 | ① 以 `requesterNo` 视角读合约摘要（复用既有查询/可见性口径：**非参与方 → `1008C0012` 404 同码同文防枚举**）② 委托既有 `PolicyExecutionService.check`（**零第二语义**；状态门槛 `1008C0013` / 越界 `1008C0020` / 参数非法 `1008C0008` 沿用既有链路） |
| 响应 | `ApiResult<{ verdict: {allowed, usedCount, violations[], occurredAt}, contract: {applicantNo, providerNo, status, effectiveAt} }>`（`applicantNo` 供交付域校验"发起人 = 需求方"；**不含合约条款原文与数据本体**） |
| 出站映射 | 沿 `ContractExceptionHandler` 既有映射（C0020→403 / C0013→409 / C0012→404 / C0008→400）——**网关原码透传** |
| 登记 | **ADR-016 §6 消费方补记**（网关 = 消费方登记第 7 位，计数口径 = 该节登记先后序）+ `ADR-022`（视 Q9）；**ADR-021 §2.10 退役评估**关系注明（受控执行演示端点**不被复用**） |

### 4.2 网关侧调用口径

- **调用身份**：出站请求注入**服务身份头**（`X-Ctds-Subject: gateway-service`、`X-Ctds-Roles: gateway-internal`，名称按配置）；**判定主体 = 请求体 `requesterNo`（来自 JWT `sub`）**，不依赖身份头；
- **诚实边界登记**：用户身份向合约域的**透传**归 3.9.1 真实令牌（本卡为服务身份 + 体携带主体编号）；服务间调用不落用户令牌；
- **不复用** `POST /api/v1/contracts/{contractNo}/usage-executions`（3.4.6 演示期受控执行入口，ADR-021 形态；权限面向参与方）。

## §5 交付调用面与凭据（Q4-A）

1. **交付面 = 配置化路由白名单**：`ctds.gateway.delivery.routes`（显式列举 routeId；**默认空 = 无交付面可用**，fail-closed）；V1.0 路由按下表。
2. **凭据与判定要素（请求头，头名可配置）**：

| 头（默认名） | 含义 | 缺失/非法 |
| --- | --- | --- |
| `X-Ctds-Contract-No` | 交付凭据 = 已生效合约编号（3.5.1 Q2-A） | 400 + `1009C0001` |
| `X-Ctds-Purpose` | 本次使用声明的用途（策略要素，可空） | 交给判定（用途不符由判定拒） |
| `X-Ctds-Territory` | 本次使用声明的域（策略要素，可空） | 交给判定（域外由判定拒） |

> 动作类型固定映射 `UsageActionType.USE`（再分发 `REDISTRIBUTE` 不在交付调用面——转授/转售不走 API 交付链）。
> **独立 API Key 的签发 / 轮换 / 吊销 = 未定义项**（规格 §6-5），归后续包。

3. **V1.0 被交付资源（诚实边界）**：演示期以**既有只读端点**承载"被交付资源"（探针路由，配置化，指向既有服务）；**真实数据源接入归连接器 3.6.x**（登记）；候选探针路由在编码段按实测确定并登记（不新增业务端点）。
   - **（DB-47 实测登记，2026-10-16）探针路由实测值 = `delivery-catalog-products` → `Path=/api/v1/data-products/**`**（catalog 真实只读读面，实测直调 200）；原编码段值 `/api/v1/catalog/products/**` 在 catalog 不存在（真链走查放行后取货腿恒 404 = 缺陷 A）——本项登记即"按实测确定并登记"的兑现。
4. **不切换面**：`frontend/vite.config.ts` 11 条代理与 `deploy/k8s/kustomization.yaml` 的"单入口收敛"**不在本卡**（登记为切换动作，建议归 3.5.6/3.5.7 前统一执行）；本卡仅新增 `deploy/k8s/gateway-deployment.yaml` + `gateway-service.yaml` + `kustomization.yaml` 2 行。

## §6 计量埋点口径（Q6-A，规格行为 2 规则 6）

1. **出口**：复用 `common-logging` `AuditRecorder`（`AuditEvent`）落 `logs/audit-*.jsonl`（审计铁律"绝不抛错、绝不阻塞"沿用）；反应式上下文以专用调度执行落盘（不阻塞事件循环）。
2. **事件字段（最小必要，不含数据本体与原文）**：`traceId` / `主体编号` / `合约编号` / `被交付资源（routeId + 方法 + 路径模板）` / `结果（ALLOWED / DENIED_*)` / `时点（注入 Clock）`。**禁止**：请求体、响应体、数据原文、令牌、密钥。
3. **对账锚（硬断言）**：放行 **1 次 ≡ 计量事件 1 条**；拒绝 **0 条**（拒绝留痕独立存在，不与计量混算）。
4. **归属**：**计量事件模型、流水落库、采集与计费均归 3.7.5 / 3.7.6**（WBS 行 317~318）；存证上链归 3.8.3——本卡只保证"事件按口径产生且可对账"。
5. **分级规范回写（§9 义务）**：网关新增字段（判定请求/结果、计量事件、限流计数）按 CAT-01 / CAT-06 / CAT-04 核对类目与级别，**刷新 `docs/domain/数据分类分级规范.md` §6.2/§6.3 相应行**并随任务分支提交，开发日志声明"分类分级规范已刷新：××字段"。

## §7 错误码与出站策略（Q7-A）

**新增 `1009` 段码位（3.5.1 Q8-A 已预留交付与沙箱域；立卡实测全库零占用）**——常量类 `GatewayErrorCodes`（沿各模块自建常量类惯例，禁裸写字符串）：

| 码值 | 含义 | HTTP |
| --- | --- | --- |
| `1009C0001` | 交付凭据缺失或格式非法（合约编号头） | 400 |
| `1009C0002` | 调用频率超出限制 | 429 |
| `1009C0003` | 非合约需求方不可发起交付（发起人唯一） | 403 |
| `1009C0004` | 请求路径不匹配已开放的交付面 | 404 |
| `1009S0001` | 策略判定服务不可达 / 超时（fail-closed） | 503 |

**复用与透传**：未认证 `1000C0002`（401）/ 无权限 `1000C0005`（403）复用平台公共段（**`1000C0005` 不由本卡网关产生**——common-auth 安全链无 `accessDeniedHandler` 接点，无权限面 403 由下游服务承载，见补登记第 4 条）；**越界一律透传合约域原码**（`1008C0020`→403 / `1008C0013`→409 / `1008C0012`→404）；**禁止**改写合约域结论码或新增"跳过判定/强制放行"入参（规格行为 2 规则 3 / fail-closed）。

**`1009C0004` 两形态登记（DB-47 缺陷 B，2026-10-16）**：① **路由内但白名单外**（原形态，`DeliveryWhitelistGlobalFilter` 出口）；② **无路由匹配**（路径不匹配 `ctds.gateway.routes` 任何路由，不进网关过滤器链）——由网关侧最高优先级 advice 将 WebFlux 响应式 `NoResourceFoundException` 收敛为 **404 + `1009C0004`**（与形态 ① **同码同文同写出口**）+ `delivery.denied` 留痕。**上游 404 透传不受影响**（转发响应不抛该异常）。未新增码位。

## §8 头处置与信任边界（规格行为 1 / 行为 7；ADR-016 §2.7）

1. **入站**：`X-Ctds-Subject` / `X-Ctds-Roles` **无条件剥离**（-200，先于安全链）——伪造头不可能进入下游；下游只见网关按 JWT 生成的权威值（-90）。
2. **出站**：网关对上游仅**注入权威身份头**；不转发调用方原始身份头；`X-Forwarded-*` 族本卡**不引入**（common 现状无该族处理，登记为演进项，避免引入未定义信任语义）。
3. **口径冲突处置**：身份头**不参与**判定（判定主体由请求体 `requesterNo` 显式给出）——避免"网关注入头"与"交付判定主体"两套口径漂移。
4. **DB-03 关口**：网关上线 = 长期解除条件**之一**兑现（另一条件 3.9.1 真实令牌未生效）→ **不改 DB-03 状态**（本卡只在 ADR-022 / ADR-016 §2.7 注明"其一已落地"），DB-03 关闭判定由编排师/后续包裁量。
5. **诚实边界（规格 §6-1 / §6-4）**：不经网关的直连（回环、内网、服务直连）不在平台承诺范围；"下载后线下扩散"不在承诺范围（沿 ADR-020 §2.9 措辞）。

## §9 测试清单（测试先行，红相即时归档）

> 归档目录 `build-output/w352-red-*`；断言**双向齐备**，禁止空洞断言与仅 happy-path（AGENTS §4）。

| # | 组 | 用例（要点） | 双向/边界 |
| --- | --- | --- | --- |
| T1 | 装配契约 | 真实 REACTIVE 应用（`@SpringBootTest(webEnvironment=RANDOM_PORT)` + `@EnableWebFluxSecurity`）启动：链内 401 出口生效、`ctds.auth.gateway.enabled=false` 时不激活（负向）、密钥来源二选一强校验沿用 | 正/负 |
| T2 | 头处置真链 | 经真实 HTTP：伪造 `X-Ctds-Subject`/`X-Ctds-Roles` 被剥离；合法令牌注入 `sub`/`roles`（含多角色逗号串与非字符串角色跳过） | 正/反 |
| T3 | 未认证 | 401 + `1000C0002` + 统一文案 + **traceId 非 "-"**；原因只进审计（不暴露内部） | 边界 |
| T4 | 限流 | 阈值内放行（N 次）；第 N+1 次 429 + `1009C0002` + **上游零请求** + 计量零事件；`enabled=false` 时不限 | 双向 |
| T5 | 策略执行点 | 放行：上游收到 1 次请求 + 计量 1 条；越界：403 + `1008C0020` **原码透传** + 上游零请求 + 计量 0 条；状态失效 409 + `1008C0013`；不存在/非参与方 404 + `1008C0012` **同码同文** | 双向 |
| T6 | 凭据与发起人 | 缺头/非法头 → 400 + `1009C0001`（**不调判定**）；提供方发起 → 403 + `1009C0003`；需求方发起 → 放行 | 双向 |
| T7 | fail-closed | 判定服务不可达 / 超时 → 503 + `1009S0001` + **上游零请求**（不放行） | 边界 |
| T8 | 计量对账 | 放行 N 次 ≡ 事件 N 条；拒绝 M 次 ≡ 0 条；事件字段断言**不含**请求体/原文/令牌 | 双向 |
| T9 | 合约域内部端点 | 权限（无 `contract.internal.read` → 拒）；防枚举同码同文；越界/状态失效原码；授权 yml 真链（网关服务身份可调、普通角色不可调） | 正/反 |
| T10 | 交付面白名单 | 白名单外路径 → 404 + `1009C0004`；白名单内 → 进入链路 | 边界 |

**上游 stub 形态**：以上游为 **reactor-netty `HttpServer` 本地 stub**（零新依赖）承载，断言"请求计数"（越界/fail-closed 时**必须为 0**）；judge stub 同法。

## §10 前置检查项（编码会话第一动作，逐项实测留痕）

1. `git status` 干净 + `main == origin/main`（`b20d84b`）；
2. contract 基线复跑：**233 例 0 失败 0 错误 0 跳过 + checkstyle 0**（防基线漂移）；3. 前端基线复跑：**440 例**；4. 依赖可用性与版本兼容核验（Spring Cloud 官方兼容矩阵 + 注册表真实存在 + `docs/dependencies.md` 登记 + 审批留痕）；
5. 端口 **8086** 空闲实测；演示环境状态实测（Docker 当前未运行 → 人侧启动 MySQL 容器与所需服务，口令仅环境变量）；
6. `gates-config.json` 基线 ConfigSha256 记录（用于"零改动"自证）；7. Q1~Q9 确认记录在案（lofi/hifi V1.0 签署）。

## §11 交付门禁与真链走查判定表

- **本地**：`mvn -B -ntp -pl services/gateway-service -am test checkstyle:check` 全绿；contract `mvn -B -ntp -pl services/contract-service test checkstyle:check` 全绿（233 例只增不减）；全仓 `run-gates.ps1` **GREEN**（单发；环境性假红沿干净复跑先例，不申请特批）；
- **真链走查（方式 A 代执行，判定表全 PASS 方可交验收）**：

| 组 | 判定面 |
| --- | --- |
| 准入 | 未认证 401（`1000C0002`）；伪造头被剥离；白名单外 404（`1009C0004`） |
| 合规调用 | 经网关放行 + 上游收到 1 次 + 计量事件 1 条（1:1 对账） |
| 越界 | 超次数 / 超期 / 用途不符 / 域外 → 403 + `1008C0020` + **上游零请求** + 计量 0 条 |
| 状态与防枚举 | 已终止合约 409 + `1008C0013`；非参与方 404 + `1008C0012` 同码同文 |
| 发起人唯一 | 提供方发起 403 + `1009C0003` |
| 限流 | 超阈 429 + `1009C0002` + 业务配额计数不受影响 |
| fail-closed | 判定不可达 503 + `1009S0001` + 上游零请求 |
| 绕过 | 绕过界面直调与界面路径同判定面（服务端强制） |

## §12 未定义项 / 演进项 / 跟踪项登记（本卡不实施）

| 项 | 承接 |
| --- | --- |
| 真实用户令牌（端到端信任边界完整） | 3.9.1 |
| 真实数据源接入（被交付资源） | 连接器 3.6.x |
| 分布式限流（多实例） | 网关演进项（换实现）+ 4.2.3 压测 |
| 独立 API Key 签发 / 轮换 / 吊销 | 规格 §6-5 未定义项，归后续包 |
| 交付记录（`deliveries`）模型与读面 | 交付域宿主包（Q5-A 衔接项） |
| 前端代理与部署单入口收敛 | 3.5.6 / 3.5.7 前统一（切换动作登记） |
| 计量流水落库与计费对账 | 3.7.5 / 3.7.6 |
| 存证上链（交付 / 使用环节） | 3.8.3 |
| `X-Forwarded-*` 信任语义 / 反应式 TraceId 沉淀 | common 沉淀评估（本卡提建议） |
| DB-40 清理脚本段值守卫 / 跟踪项 T1 台账脱敏 | 4.2.1 / 另立 DB 族工具包 |

---

## 编码段就地补登记（2026-10-16，实现期细化，**不动已确认口径**）

> 记载实现期必须落定的细节与两条边界判定；均为契约条文的**具体化**，不构成口径变更（口径变更须走变更流程）。

1. **§1.2 依赖装配补记（实现口径）**：`common-auth` 以 `spring-boot-starter-web` 为**编译依赖**（其 servlet 装配面），网关模块在依赖声明中对该 starter 做 **`<exclusions>`**——否则 classpath 同时含 `spring-webmvc` 会使 Boot 判定 `WebApplicationType=SERVLET`，反应式安全链与网关整体不激活（静默失效 = 鉴权旁路）。同时 `application.yml` 显式声明 `spring.main.web-application-type: reactive` 作为第二道防线。**common-auth 一行未改**。
2. **§1.3 配置补记**：本卡不引入 `spring-boot-starter-actuator`（依赖最小化）→ `ctds.auth.gateway.permit-paths` 留空；部署就绪/存活探针沿既有 k8s `tcpSocket` 惯例（`deploy/k8s/gateway-deployment.yaml`）。
3. **§1.4 / §1.5 401 出口补记**：common-auth 的 `GatewayAuthEntryPoint` 固定 `traceId="-"`（其类注释为已知限制），而 §1.5（Q8-A）要求未认证响应亦带追踪号 → 网关侧**自建** `ServerAuthenticationEntryPoint`（`GatewayUnauthorizedEntryPoint`，以 `@ConditionalOnMissingBean` 语义优先于 common-auth 缺省 Bean），复用 `1000C0002` 码值 + `AuthAdvice` 常量文案 + `auth.verify` FAILURE 审计动作（原因枚举口径逐字保持）。**不改 common**。
4. **§1.5 无权限面补记（诚实边界）**：`1000C0005`（403）在本卡**不由网关产生**——common-auth 安全链只挂 `authenticationEntryPoint`（无 `accessDeniedHandler` 接点），网关以 `anyExchange().authenticated()` 只做"是否认证"判定；无权限面的 403 + `1000C0005` 由下游服务承载（本卡不改 common，故不新增接点）。
5. **§1.6 路由配置补记（支持子集 + fail-fast）**：`ctds.gateway.routes` 本卡支持形态 = **谓词仅 `Path=<模板>`、`filters` 为空列表**；出现其它谓词/过滤器形态**启动即失败**（配置被静默忽略等于安全口径失守）。路径模板以 `path-template` 元数据随路由登记，供 §3-1 限流 key 与 §6-2 计量"被交付资源"字段取用。
6. **§4.1 响应字段补记**：响应 `contract` 摘要字段名以 `InternalUsageCheckViews.ContractSummary` 锚定（`applicantNo` = `Contract#requesterSubjectNo` 需求方 / `providerNo` = `providerSubjectNo` / `status` / `effectiveAt`）；`verdict.violations` 出站为枚举名集合（不含条款原文）。
7. **§4.2 判定不可用口径补记（边界判定）**：判定响应的**非合约域业务码**（`1008S` 型依赖不可用 / 未知形态 / 非 2xx 无码体）→ 统一 **fail-closed**（503 + `1009S0001`），不透传 S 型原码——理由：`§7` 明文透传集为**越界类 C 型码**（C0020/C0013/C0012/C0008），而 `§2` 明文"判定不可达 → 不放行"；把"判定未能完成"透传为业务结论会削弱 fail-closed 语义。**（评审①补正 2026-10-16）实现识别口径 = 按 `1008C` 前缀判定"合约域 C 型结论码"并原码透传，其余（含 `1008S` 型 / 空码 / 非 2xx 无码体 / 解析失败）一律 fail-closed**——前缀口径是 §7 明文四码（C0020/C0013/C0012/C0008）的超集，但仍严格限定合约域结论码、不改写码值（§7"禁止改写合约域结论码"）；内部端点实测仅产出该四码，故不构成口径变更。
8. **§5.2 凭据格式补记**：合约编号头取值规则 = **非空且仅 `[A-Za-z0-9._-]`、长度 ≤64**（与 common-api traceId 净化同族字符集，避免把控制字符/空白带入下游与留痕）；空值/null 与格式非法同码同文（`1009C0001`）。**任务卡 Q1 摘要曾以 `ctds.gateway.delivery.paths`（路径白名单）表述，与本 hifi V1.0 §5.1（routeId 白名单）不一致——实现按已确认 hifi（`delivery.routes`，routeId）执行**，差异登记于此供评审①复核。
9. **§8-4 计时/次序观察项（登记，不擅自调整）**：合约域 `check` 为"放行即计数"，而 §2 固定次序为"判定 → 发起人校验"→ "发起人 ≠ 需求方"的拒绝发生在计数之后（提供方可消耗该合约配额一次）。本卡按已确认设计实现（未改次序）；如需前置该规则须走变更流程（关联 3.7.5 计量口径复核）。
10. **§6.5 分级规范回写**：已刷新 `docs/domain/数据分类分级规范.md` §6.1（新增"网关交付链审计事件字段"行，CAT-06 **L3**）与 §6.2（新增"网关交付链事件与限流计数"示例行）。

## 评审修复段就地补登记（2026-10-16，4 视角评审循环 1 处置，**不动已确认口径**）

> 承"编码段就地补登记 1~10"续号；记载本轮评审的勘正、补正与"登记不做"——均为已确认条文的口径澄清或如实登记，**不构成口径变更**（口径变更须走变更流程）。

11. **§2 链路图补入交付面白名单步骤（评审①P2-4）**：白名单在 §5-1 已定义、ADR-022 §2.1 已固化位次，编码段实现为网关 GlobalFilter（order -300），本次回写 §2 使编码契约单一权威。**网关自身 GlobalFilter 顺序以 `GatewayFilterOrders` 为单一事实源**（-300 白名单 → -250 限流 → -200 凭据 → -150 策略执行点 → -140 计量）；common-auth 的 WebFilter（-200 剥离 / -90 注入）先于网关 GlobalFilter 链执行，两套序号不同轴（前者 = 链内步骤，后者 = WebFilter 阶段），非冲突。
12. **§7 补注（评审①P2-6）**：`1000C0005`（403）**不由本卡网关产生**（见编码段补登记第 4 条）——避免误读为"应实现而未实现"。
13. **§4.2 补正（评审①P2-2）**：透传识别口径见编码段补登记第 7 条末尾"按 `1008C` 前缀"。
14. **消费方登记序号勘正（评审①P2-5）**：ADR-016 §6 本条由"第 6 消费方"勘正为**第 7 位**（第 6 位 = 上条 contract-service 消费目录内部端点），并注明计数口径 = 该节登记先后序；本文件 §4.1 / lofi / 任务卡同步勘正。
15. **§9 未按原文落地 / 弱化项登记（评审④P2，如实登记不隐匿）**：
    - **T1 形态**：以 `ReactiveWebApplicationContextRunner`（无 HTTP 服务器，仅装配面）承载，未按 §9 原文用 `@SpringBootTest(RANDOM_PORT)` 真启动——理由：真链 401 出口与密钥 fail-fast 已由 T2/T3（真 HTTP）与负向用例覆盖，重复启动第二个完整上下文徒增门禁时长；**T1 两处弱断言已在本修复批强化**（去同义反复断言 → 断言 `headerStripFilter`/`contextHeaderInjectFilter` 存在且 order = -200 / -90）。
    - **T3 后半"原因只进审计（不暴露内部）"无断言覆盖**：承载类测试 `ctds.audit.enabled=false` 使 `GatewayUnauthorizedEntryPoint` 审计分支不执行（等于未验证）→ **登记跟踪**，承接 = 交付域宿主包 / 3.9.1 真实令牌包（届时统一开启审计开关并断言 `auth.verify` FAILURE 事件字段）。
    - **T10 起点**：以"白名单枚举一个不匹配 routeId"表达 fail-closed（等价），**未直接覆盖"白名单为空列表"起点**；"白名单内进入链路"由交付链各类隐式覆盖（`application.yml` 默认列出该 routeId）→ **登记跟踪**（新增独立上下文成本 > 收益）。
    - **边界缺口（登记跟踪）**：畸形 JWT（非 JWT 串 / 空 Bearer）、空 roles / 缺 roles 声明、上游 5xx 与上游不可达下的计量口径、限流并发。**本修复批已补**：合约编号头超长（>64）一例（T6 内扩展）。
    - **`@Testcontainers(disabledWithoutDocker = true)` 静默跳过**（合约域内部端点 7 例；沿各服务既定先例）：Docker 缺席时该组零运行而门禁仍 GREEN → **登记跟踪**（全仓同族口径，须在门禁可观测性增强包内统一处置）。
16. **P1 修复（评审④P1，挂钟依赖消除）**：限流令牌桶时间来源由"构造函数内置 `System::nanoTime`"改为**可注入 bean `gatewayNanoClock`**（生产默认仍为 `System::nanoTime`，零行为变化），测试以 `TestNanoClockConfiguration`（`@Primary`）注入可控刻度 → T4"第 N+1 次被拒"与新增"推进 1 秒后补充 1 个令牌"两条判定均为确定性断言；连带消除原"测试用构造"无人调用的死代码，并补"超限不调用判定"断言（§2 链路顺序回归锚）。
17. **用例数变更登记**：网关模块 **26 → 27 例**（新增限流补充速率双向用例 1 例），其余为断言强化（不改例数）；合约域 **7 例不变**。
18. **②安全与供应链 P2×5 登记跟踪（复审补登；按评审卡 02 明文「P2 = 记录跟踪」）**：① `deploy/k8s/gateway-deployment.yaml` 的 `CTDS_GATEWAY_JWT_SECRET` 以明文 `value: ""` 占位（**无真实密钥**，空值经 common-auth ≥32 字节强校验 fail-fast；建议激活前改 `secretKeyRef` 或移除该 env 行）；② `RateLimitGlobalFilter` 令牌桶容器无容量上限/空闲回收（key 主体取自已认证 `sub`，非匿名 DoS 面；长期运行内存缓慢膨胀）；③ `GatewayErrorWriter` 拒绝留痕的 `contractNo` 未走凭据过滤器净化口径（限流 429 早于凭据解析；经核实 common-logging 以 Jackson 转义 + 单值 512 截断 + 20 键上限，**不构成 JSONL 行注入**，建议统一净化）；④ `PolicyExecutionClient` 失败日志用 `exception.toString()`，可能含内部判定服务 URL（服务端 app 日志，非审计事件、非对外响应）；⑤ `ctds.auth.jwt.issuer` 空默认 → `ClaimValidator` 跳过 `iss` 校验（签名 / `exp` / `nbf` 仍强校验；激活演示与生产须显式配置 issuer）。**承接 = 网关演进包 / 3.9.1 真实令牌包 / 3.7.5 计量包**，均不属本卡关闭条件（本卡不夹带修改）；另见 ADR-022 §2.9「网关加固（跟踪）」行。
19. **③P2-1 登记落点补正（复审补登）**：自建 401 出口（`GatewayUnauthorizedEntryPoint`）的 **common 沉淀建议已补入 ADR-022 §2.9「common 沉淀」行**（原仅列反应式 traceId 与 `X-Forwarded-*`）——沉淀理由 = 与 common-auth 缺省出口形成重复实现（仅 traceId 来源不同），口径漂移风险随 3.9.1 令牌改造放大。
20. **复审 P3 登记（测试替身边界）**：`TestNanoClockConfiguration` 的刻度为静态全局值——当前 surefire 未并行 + 用例按主体隔离令牌桶，无串扰；**若后续开启模块测试并行须改为一用例一刻度实例**（已在类 Javadoc 就地登记）。

## 确认记录（V0.9 → V1.0）

> **✅ 已签署（2026-10-16 08:1x，编排师会话回复"一次确认"）**：**Q1~Q9 全采建议口径 A + D1 不拆分**（与 lofi 末节确认记录同源同批）；**编码契约（本文件 §1~§12）即时生效**——编码会话按 **§10 前置检查项七项**逐项实测留痕开工，测试先行（§9 十组用例，红相归档 `build-output/w352-red-*`），判定与走查按 **§11 判定表**执行；§3 限流 / §4 策略执行点 / §6 计量 / §7 错误码 / §8 头处置为本卡实现与评审的逐条依据。

## DB-47 修复段就地补登记（2026-10-16，真链走查缺陷修复，**不动已确认口径**）

> 承"评审修复段就地补登记 11~20"续号；记载真链走查两缺陷（A/B）的修复落地与如实登记——缺陷修复 = 回到已确认条文的正确实现，**不构成口径变更**（缺陷 C〔要素传递编码约定〕属契约缺口，走 `CHG-WBS-3.5.2-hifi-V1.1` 变更流程，不在本节）。

21. **§5-3 探针路由实测登记（缺陷 A 修复）**：`delivery-catalog-products` 谓词由 `/api/v1/catalog/products/**` 勘正为 **`/api/v1/data-products/**`**（catalog 真实只读读面）；原因 = 原值在 catalog 不存在（真机放行后取货腿恒 404，判定与计量面均正确）。
22. **§7 无路由出口（缺陷 B 修复）**：新增网关侧最高优先级 advice（`GatewayNoRouteAdvice`），将 WebFlux 响应式 `NoResourceFoundException` 收敛为 **404 + `1009C0004`**（与白名单外**同码同文同写出口**）+ `delivery.denied` 拒绝留痕。**根因如实登记** = `common-errorcode` 的 `GlobalExceptionHandler` 仅识别 servlet 版 `NoResourceFoundException`，reactive 版落入其 `Exception` 兜底（实测 500 + `1000S9999`）；**common 零改动**，向 common 提沉淀建议（兼容响应式异常类型；承接沿 DB-26/DB-30 条件触发式口径）——本卡不夹带。
23. **`GatewayErrorWriter` traceId 解析补强（副作用登记）**：上下文缺失时回退响应头 `X-Trace-Id`（`TraceIdWebFilter` 恒先写入）——无路由 advice 位于 WebFilter 链外层订阅位置、上下文不可见，回退保证出口追踪号非 `"-"`。**过滤器链内路径行为零变化**（上下文优先）。
24. **用例数变更登记**：网关模块 **27 → 32 例**（新增 `GatewayProbeRouteIntegrationTest` 3 例〔T-A1a 谓词 / T-A1b 反向探针 / T-A2 转发路径实例〕+ `GatewayWhitelistRejectIntegrationTest` 2 例〔无路由同码同文 / 不判定不转发不计量留痕〕），其余断言零弱化（仅请求路径随探针勘正同步）；`StubServer` 测试设施增强（记录最近请求路径与请求体——供 T-A2 与变更 C 断言复用）。
