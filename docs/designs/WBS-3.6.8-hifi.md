# WBS-3.6.8 高保真设计：连接器模块测试与集成（V1.0，已确认 = 编码契约即时生效）

| 字段 | 内容 |
| --- | --- |
| 版本 | **V1.0（2026-10-30 编排师一次确认"全部按建议"：Q1~Q8 均采建议口径 A + D1 不拆分——编码契约即时生效）**；草案 V0.9 随立卡批提交 |
| 日期 | 2026-10-30 |
| 上游 | lofi `docs/designs/WBS-3.6.8-lofi.md`（Q1~Q8 + D1 建议口径 A）；规格 C-6.1~6.3 V1.0；ADR-026 / 027 / 028 / 029 / 030；DB-68 / 72 / 73 / 75 / 76 / 84 / 89 / 93 |

> **编码段第一动作 = §9 实施前置检查八项逐项实测留痕**（沿 3.6.7 先例）；本文所有"实测回填"标注以前置检查实测为准，实测与本文不一致时以实测修正本文并留痕（勘误制）。

## 1. 接口契约表

### 1-A 网关路由增量（W1；`services/gateway-service/src/main/resources/application.yml`）

| 路由 id（拟） | Path | 下游 | 说明 |
| --- | --- | --- | --- |
| `connector-identity-subject` | `/api/v1/subject/**` | subject-service（前置检查实测端口，现网直连 8080） | SDK `querySubject`（Identity） |
| `connector-identity-did` | `/api/v1/did/**` | did-service（现网直连 8082） | SDK `resolveDid`（Identity） |
| `connector-resource-*` | `/api/v1/datasets/**`、`/api/v1/data-spaces/**` | 目录 / 空间服务（前置检查实测服务名与端口） | SDK Resource 两方法 |
| `connector-product-*` | `/api/v1/data-products/**`、`/api/v1/data-products/mine` | 既有路由 `delivery-catalog-products` 已覆盖 | 零改动（既有唯一路由） |
| `connector-contract` | `/api/v1/contracts/**` | contract-service（前置检查实测） | SDK Contract 三方法 |
| `connector-delivery` | `/api/v1/deliveries/**` | delivery-service（前置检查实测） | SDK Delivery 剩余两方法 + Usage `getUsageSummary`（路径归属前置检查核对） |

- **形态约束**（既有 `GatewayRouteConfig.java:28-57` 通用装配，Java 零改动）：仅 `Path=` 谓词、`filters` 为空；路由 id 唯一；
- **判定链白名单零改动**：`ctds.gateway.delivery.routes`（application.yml:52-54）不新增——新增路由为**普通代理**，不进合约头强制 / 判定 / 计量过滤器链（`DeliveryWhitelistGlobalFilter.java:38-45` 对白名单外路由直接放行）；六门面动作的鉴权 = 各下游服务既有 `@RequirePermission` + `X-Ctds-Subject` / `X-Ctds-Roles` 头（SDK 侧组装，`PlatformClient.java:36` 合约头仅交付 / 使用腿携带）；
- **计量语义**：计量事件挂在交付判定链（白名单路由），新增路由零计量——与"计量 = 交付面"口径一致（`DeliveryMeteringGlobalFilter` 白名单内生效）。

### 1-B 交付 CONNECTOR 形态契约（W3；delivery-service 加性，既有 API / FILE 契约零改动）

| 项 | 契约 |
| --- | --- |
| 端点 | `POST /api/v1/deliveries`（既有端点复用，零新端点；`@RequirePermission("delivery.request")` / 幂等键沿既有） |
| 请求增量 | `deliveryType` 新枚举值 `CONNECTOR`；请求 DTO 新增可选字段 `connectorNo`（`^CN\d{6}$`）、`sourceNo`（`^SR\d{6}$`）——`deliveryType=CONNECTOR` 时**必填**，其余形态必须为空（混带即 400 参数无效，`1009` 段既有参数码） |
| 处理顺序（`DeliveryCommandService.create()` 新分支） | ① 幂等查重（既有）→ ② 合约摘要 + 申请人一致性（既有）→ ③ 产品形态校验（`validateForm` 增 CONNECTOR 规则：允许形态与 FILE 同构，前置检查核对 `validateForm` 现行矩阵后落定并回填本文）→ ④ **策略判定** `contractPort.check(contractNo, requester, purpose, territory)`（先判定——拒绝即 `DENIED` 留痕，零触达源、零计量）→ ⑤ 签发交付编号 → ⑥ **占用登记** `occupy(connectorNo, jobRef=deliveryNo)`（新客户端；连接器未注册 / 并发冲突 → 拒绝）→ ⑦ **连接器属主核对**：经内部读连接器端点取属主主体，与合约 `providerNo` 比对（不一致 → 拒绝，防"借他方连接器读己方合约标的"）→ ⑧ **内部源读取**（携带 `jobRef` + `contractNo` + `purpose`；见 1-C）→ ⑨ `release`（finally 兜底）→ ⑩ SM4 加密落包 + SM3 摘要 + 交付记录 / 动作留痕落库（字段与 FILE 同构：`sourceFileId` 位记 `connectorNo:sourceNo`、包体 / 校验和 / 密钥引用同口径） |
| 失败映射（对外 1009 段，不透传 1010 内部码） | 占用失败 → 既有"状态冲突"语义码 + 文案"连接器当前状态不允许该作业"；属主不一致 → 既有参数 / 权限语义码 + 可读文案；内部读取失败（网络不可达 / 凭据无效 / 权限不足 / 源拒绝 / 未登记 / 跨归属）→ **新增 1009 段码 1 个**：`取数失败`（detail 沿内部读取返回的业务可读摘要归类，不含源数据本体）；判定拒绝沿既有 DENIED 口径（拒绝零计量不变） |
| 记录与计量 | 交付记录 / 留痕 / 计量事件口径与 C-5.1 完全一致（统一事件源）；CONNECTOR 腿无网关判定链参与 → 计量沿交付记录事件源，拒绝零计量（行为 14 规则 3） |
| 事务边界 | 占用 / 读取 / 释放为跨服务 HTTP 调用，**不在 delivery 本地事务内**；`release` 以 finally 兜底 + 幂等（重复释放幂等）；进程崩溃残留 OCCUPY 为已知限制（演示期）：经内部占用清单端点可查、release 幂等可手工补，**如实登记不虚报自动对账** |

### 1-C 内部源读取契约增量（W6 / DB-89；connector-service）

| 项 | 契约 |
| --- | --- |
| 端点 | `POST /api/v1/connectors/internal/{connectorNo}/sources/{sourceNo}/reads`（既有；W9 统一仅影响 `/internal/connectors/{no}` 读连接器端点，本端点路径不变） |
| 请求增量 | `ReadRequest` 新增**必填** `jobRef`（作业号；格式非空、≤64 可打印字符——与占用登记 `jobRef` 同键同约束） |
| 校验顺序（`ConnectorSourceReadService.read()`） | ① 参数合法性（既有）→ ② **占用凭证核验（新增，DB-89）**：`occupancyRepository.find(connectorNo, jobRef)` 存在且 `status='OCCUPY'`，否则拒绝（既有业务码语义 + 文案"作业未登记或已释放"）→ ③ 既有本地前置四校验（连接器 `REGISTERED` / 源归属 / 参数合法 / 类型有适配器）→ ④ 端点策略与 SSRF 守卫（既有）→ ⑤ 适配器调用 |
| 凭证不可伪造性 | 占用登记 / 释放端点权限点 = `connector.internal.write`（仅 `connector-internal` 服务角色持有，`application.yml:31`）；`admin` 仅持 `connector.internal.read`（:29）→ admin 无法自造占用记录，读取凭证不可绕过（负向用例锚 T5-③） |
| 契约同步 | ADR-029 §2.6 追加修订记录：读取请求作业凭证字段与核验顺序（判定放行锚定 = 占用登记先行） |

### 1-D 占用端点消费契约（W4；零新端点，既有三个占用端点的首个平台消费方）

| 端点 | 调用方与时机（delivery-service 新客户端） |
| --- | --- |
| `POST /api/v1/connectors/internal/{connectorNo}/occupancies` | 取数前（判定放行、编号签发后）；body `jobRef = deliveryNo`；幂等（重复登记同键幂平安静） |
| `DELETE /api/v1/connectors/internal/{connectorNo}/occupancies/{jobRef}` | 落库成功后 + 全部失败路径 finally；幂等（`release` 带 `status='OCCUPY'` 谓词，`JdbcConnectorOccupancyRepository.java:35-39`） |
| `GET /api/v1/connectors/internal/{connectorNo}/occupancies` | 本卡不消费（运维排查面，保留） |
| 鉴权 | 新客户端固定携带服务身份头 `X-Ctds-Subject: delivery-service` + `X-Ctds-Roles: connector-internal`（演示期信任边界 DB-03 沿承；与 connector-service 调 subject 的 `SubjectAdmissionClient` 同构） |

### 1-E 内部读连接器契约（W3-⑦ 属主核对；W9 路径统一后调用）

| 项 | 契约 |
| --- | --- |
| 端点 | `GET /api/v1/connectors/internal/{connectorNo}`（W9 统一后形态；现状 `/internal/connectors/{no}`，`InternalConnectorController.java:21`） |
| 用途 | delivery-service 取属主主体编号，与合约摘要 `providerNo` 比对（1-B-⑦）；返回 DTO 复用既有（前置检查核对属主字段名后回填） |

### 1-F occupy / cancel 原子谓词契约（W5 / DB-75；connector-service 服务端语义修复）

| 方法 | 现状 | 契约（修复后） |
| --- | --- | --- |
| `occupy` | 服务层 `requireRegistered`（读后写，TOCTOU）+ 无谓词 upsert（`JdbcConnectorOccupancyRepository.java:27-32`） | SQL 改 `INSERT … SELECT … FROM connector_registration WHERE connector_no=? AND status='REGISTERED'`（0 行 → 业务拒绝 `1010C0004` 语义）；服务层前置检查保留为快路径，**原子性以 SQL 谓词为准** |
| `cancel`（`ConnectorCancellationService.java:55-67`） | 先 `countInProgress` 再改状态（读-改-写，TOCTOU） | 状态变更改原子谓词 UPDATE（`… SET status='CANCELLED' WHERE connector_no=? AND status='REGISTERED'`，影响 0 行 → 拒绝）；计数前置检查保留为可读拒绝理由；后续解除绑定 / 作废待确认升级逻辑不变 |
| 并发断言 | —— | 真并发窗口（CountDownLatch 握手）下"CANCELLED 连接器上不得残留 OCCUPY 行"、"并发双核验仅一成功"（T4 组） |

## 2. 模块改动清单（逐文件，编码段按此对账；★ = 新文件）

| 模块 | 文件 | 改动 |
| --- | --- | --- |
| gateway | `src/main/resources/application.yml` | `ctds.gateway.routes` 增 5 条普通路由（1-A；精确 id / 下游前置检查回填）；白名单 / 限流 / 过滤器零改动 |
| gateway | `src/test/...`（既有路由测试类旁）★ | 新增路由装配与转发断言用例（Path 匹配 / 白名单外不进判定链 / 未知路由 404 既有语义不回归） |
| delivery | `domain/DeliveryType.java` | 枚举值 `CONNECTOR` |
| delivery | `interfaces/dto/*`（请求 / 视图） | 请求增 `connectorNo` / `sourceNo` 可选字段 + 形态条件校验；视图沿既有（记录字段复用） |
| delivery | `application/DeliveryCommandService.java` | `validateForm` 增 CONNECTOR 规则；`create()` 增分支（1-B 顺序）；失败映射 |
| delivery | `infrastructure/ConnectorInternalClient.java` ★ | 内部面客户端（源读取 / 占用登记 / 释放 / 读连接器属主；服务身份头常量；超时与错误收敛沿 `SubjectAdmissionClient` 同构——不引入新 HTTP 基座，DB-71 沉淀建议另登记） |
| delivery | `domain/DeliveryErrorCodes.java` | 新增 1 个取数失败码（1009 段顺延，前置检查核对既有段内最大值）+ 文案常量 |
| delivery | `src/test/...` ★ | T2 / T3 组（判定拒绝零触达 / 全链成功 / 失败映射 / finally 释放 / 幂等重放 / 客户端桩 500·超时） |
| connector | `infrastructure/JdbcConnectorOccupancyRepository.java` | `occupy` 谓词化 SQL（1-F） |
| connector | `application/ConnectorCancellationService.java` + `infrastructure/*Repository` | `cancel` 谓词化 UPDATE（1-F） |
| connector | `interfaces/InternalConnectorSourceController.java` + `application/ConnectorSourceReadService.java` | `ReadRequest.jobRef` 必填 + 占用凭证核验（1-C） |
| connector | `interfaces/InternalConnectorController.java` | `@RequestMapping` 路径统一（W9） |
| connector | `src/test/...` ★ | T4（并发双核验 / 重复提交 / 双写升级 / 不落半成品）、T5（凭证负向 ×3）、T6（occupy 谓词并发）、T7（三类直测 stub 500 / 超时 / 降级）、T8（放行对照腿逐段 1 例） |
| connector-sdk | `src/test/...`（既有 5 测试类） | 运行期字段级比对：mock 响应样本更新为真实服务实测 JSON（W2 探针采集后回填）；**主代码零改动** |
| 文档 | ADR-026 / ADR-027 / ADR-028 / ADR-029 修订记录；三剧本承载修订；任务卡 / 台账 / 日志 | 见 §7 |
| frontend | —— | **零改动**（零回归验证） |

## 3. 边界值与异常行为表（CONNECTOR 腿与内部面）

| # | 输入 / 场景 | 预期行为 |
| --- | --- | --- |
| E1 | `deliveryType=CONNECTOR` 且缺 `connectorNo` / `sourceNo` | 400 参数无效（既有 1009 段参数码），零侧效 |
| E2 | `deliveryType=API/FILE` 却携带 `connectorNo` / `sourceNo` | 400 参数无效（形态与字段互斥） |
| E3 | 连接器编号 / 源编号格式非法（非 `CN\d{6}` / `SR\d{6}`） | 400 参数无效（客户端侧预校验 + 连接器侧既有正则兜底） |
| E4 | 判定拒绝（越界 / 超次 / 用途不符） | DENIED 留痕；**零占用、零适配器调用、零计量**（拒绝零计量；"拒绝不触达源"完整证明） |
| E5 | 合约申请人 ≠ 请求方 / 属主主体 ≠ 合约提供方 | 拒绝（既有申请人码 / 属主不一致可读文案）；零触达源 |
| E6 | 连接器非 REGISTERED（待核验 / 已注销）发起 CONNECTOR 交付 | 占用登记被拒（谓词 0 行）→ 业务可读拒绝；零触达源 |
| E7 | 源未登记 / 源属他连接器（跨归属） | 连接器侧四校验拒绝（`1010C0003` 防枚举 → 对外映射取数失败可读文案）；**不泄露他源信息** |
| E8 | 适配器失败（网络不可达 / 凭据无效 / 权限不足 / 源拒绝） | 1009 段取数失败码 + 业务可读原因；占用已释放（finally）；零交付记录、零计量 |
| E9 | 内部读取请求缺 `jobRef` / `jobRef` 无占用记录 / 已释放 | 连接器侧拒绝（作业未登记或已释放）；适配器零调用 |
| E10 | `admin` 携 `connector.internal.read` 直调源读取（无占用） | 拒绝（凭证不可伪造——`internal.write` 仅服务身份） |
| E11 | 注销时存在 OCCUPY 在途记录 | 拒绝注销（提示先完结或终止）；释放后可注销（链 C） |
| E12 | 并发：注销与占用登记同时提交 | 谓词原子性保证二者恰一成功：要么占用成功且注销被拒，要么注销成功且占用失败；无中间态 |
| E13 | 并发：同一申请双核验 / 同请求重复提交 / 两条不兼容升级双写 | 恰一成功，其余业务码拒绝；编号唯一、仅一条绑定、DB 唯一索引兜底可达（`1010C0004`） |
| E14 | 幂等键重放 CONNECTOR 交付 | 返回首次交付结果（既有幂等语义），不重复取数 |
| E15 | 内部面客户端超时 / 5xx | 交付侧按取数失败收敛（可读文案 + 留痕），不重试（N3：平台拒绝与源失败均不盲目重试） |
| E16 | 落库失败（DuplicateKey / RuntimeException） | 既有补偿（删包 + 幂等重放 / 失败码）不回归；占用已释放 |

## 4. 测试锚点（测试先行：红相归档 → 实现 → 绿相；命名沿既有惯例）

| 组 | 覆盖 | 模块 | 对应 |
| --- | --- | --- | --- |
| T1 | 路由装配与转发（新路由 Path 匹配 / 白名单外不进判定链 / 计量不触发） | gateway | W1 / 行为 2 规则 2 |
| T2 | CONNECTOR 交付全链（判定 → 占用 → 读取 → 释放 → 落包 → 记录与留痕五要素）+ 判定拒绝零触达源（桩断言零请求） | delivery | W3 / 行为 14 验收 1~3 |
| T3 | CONNECTOR 失败矩阵（E1~E3 / E5~E8 / E14~E16）+ finally 释放 + 客户端 500 / 超时收敛 | delivery | W3 / E 表 |
| T4 | 并发组：双核验恰一成功 + 重复提交 + 双写升级触达唯一索引 + "四件事不落半成品"（DB-72 / 76 / 73-Q5A） | connector | W7 |
| T5 | 凭证负向：缺 jobRef / 无占用 / 已释放 / admin 直调（DB-89） | connector | W6 |
| T6 | occupy 谓词：非 REGISTERED 占用拒绝 + 真并发"注销 × 占用"恰一成功（DB-75） | connector | W5 / E11~E12 |
| T7 | 三类直测：`SubjectAdmissionClient` / `DidStatusClient` / `ConnectorApplication`（stub 500 / 超时 / 降级；行覆盖 ≥70%，DB-84） | connector | W8 |
| T8 | 放行对照腿逐段 1 例（`allow-private-endpoint=true` 放行；DB-93） | connector | W7-⑤ |
| T9 | 内部读连接器新路径（W9）+ 既有内部面用例路径同步零回归 | connector | W9 / DB-68 |
| T10 | SDK 运行期字段级比对（真实响应样本 vs 门面映射；含 14 方法路径核对） | connector-sdk | W2 / ADR-026 |
| T11 | 六门面经网关端到端探针（走查环境实时执行，证据落 `build-output/`）：六类动作受理 + 三拒绝腿 + 留痕同源 | 环境 | W2 / W10 |
| T12 | 链 C 注销前置真实生效探针（在途构造 → 拒绝 → 释放 → 注销成功）+ 链 B 真实源取数探针（MySQL 演示源） | 环境 | W4 / W3 |

## 5. 红相 / 绿相与达标口径

- 测试先行：T1~T9 先落（新端点 / 新分支红相：404 / 枚举缺失），红相输出落 `build-output/w368-red-*.log`（**DB-83 整改：红相证据落可检索路径**）；实现后全绿；
- 达标 = lofi §5 六条（构建 / 基线零回归 / 新增全绿 / 覆盖率 / 变异既有口径 / 端到端证据）。

## 6. 实施前置检查（编码段第一动作，八项逐项实测留痕）

1. 分支 `feat/WBS-3.6.8-连接器模块测试与集成` == origin 且基于本地 main 梢 `0b2ee46`（3.6.7 收口批随本卡 PR 入库——链式先例）；
2. 基线复跑：前端 650 例零回归 + typecheck / lint / build；connector-service 247 例（子智能体静态清点 `@Test` 计数 250 与门禁口径差 3，须澄清口径——`@Testcontainers` 等注解行误计嫌疑）；SDK 109 例；delivery-service / gateway 既有用例数登记；
3. 网关路由表 / 白名单 / 过滤器逐行读 + 新增路由下游端口实测（subject / did / 目录 / 空间 / 合约 / 交付六服务 `application.yml` 端口与上下文路径），回填 §1-A；
4. `validateForm` 现行形态 × 产品矩阵实测，落定 CONNECTOR 允许形态并回填 §1-B；
5. 内部读连接器返回 DTO 属主字段名实测，回填 §1-E；`DeliveryErrorCodes` 段内最大值实测，落定新码值；
6. 静态事实复核：交付域无完结端点（释放时机关定的前提）、占用仓储 `find` 方法可复用、`SubjectAdmissionClient` 头组装形态（新客户端同构）、DB-88 挂起（内部面调用仅 `connector-internal`，不触碰运营方读面）；
7. `gates-config.json` V1.12 ConfigSha256 `955bdbba…2ee2` 零改动核对；
8. 走查环境现状盘点（三容器 + 五服务起停脚本沿用 / 扩展 `w368-*`；演示数据 DB-64 严禁脚本清理；CONNECTOR 演示源 = 演示库新建演示表或独立容器，随验收方案定并留痕）。

## 7. 剧本承载核对修订方案（业务判定一字不改，PO 批准留痕）

| 剧本 | 步骤 | 修订内容 |
| --- | --- | --- |
| C-6.1 V1.1 | S2 全幕（技术侧配合项） | 承载说明更新：六类动作 = SDK 经网关（路由已扩）真实执行 + 探针证据；三条拒绝腿判定原文不变 |
| C-6.2 V1.2 | S3-1（在途作业拒注销） | 技术侧配合项"构造在途作业"更新为**真实承载**：CONNECTOR 交付在途窗口内发起注销即拒（判定面不变）；S3-3 端点验证口径沿用 |
| C-6.3 V1.1 | S2-1~4（技术侧配合项） | 承载说明更新：真实源取数（CONNECTOR 交付）/ 越界不触达源 / 未登记拒绝 / 跨归属拒绝的执行路径与证据形态；S2-5 界面核对不变 |
| 维护说明 | 三份第 3 / 4 条 | 执行顺序注记补"WBS-3.6.8 交付后技术侧步骤可执行"；版本号各 +1（V1.2 / V1.3 / V1.2），PO 批准留痕 |

## 8. 红线与边界复述（自查清单）

零新依赖 / 零新镜像 / 零库表迁移 / 零新权限点 / 零门禁配置改动（ConfigSha256 不动）；**不改判定链语义**（合约头强制 / 白名单 / 计量 / C-5.1 既有腿）；**先判定后取数**（拒绝零触达源零计量）；**零凭据明文 / 零数据本体**（交付记录与留痕不含源数据原文；错误映射不暴露内部实现与 1010 内部码）；**不加解密自实现**（SM4 / SM3 沿 `common` 既有服务）；**DB-88 不擅裁**（内部面调用仅服务身份，运营方读面零触碰）；**SDK 主代码零改动**；**前端零改动**；变异范围不扩（如实登记）；规格缺口走变更流程；发现冲突或疑似 S0 → 立即停止上报（红线 4）。

## 9. 交付物与证据清单

① 网关扩路由 + CONNECTOR 交付形态 + 占用接入 + 凭证校验 + 原子谓词修复（代码与测试）；② T1~T12 全绿证据（含红相归档路径）；③ 端到端探针证据（链 A / B / C，业务可读，`build-output/w368-*` 不入库）；④ 三剧本承载修订（PO 批准留痕）；⑤ ADR 四处修订留痕；⑥ 复用声明（`common` 国密 / 幂等 / 鉴权 / 既有内部面端点与占用仓储 / Testcontainers 共享容器基建）与规格外实现声明（应为空；DB-75 / DB-89 修复均有 DB 编号承接）；⑦ 台账转态 + 业务可读交付说明（含"剧本是否需更新"回答）。
