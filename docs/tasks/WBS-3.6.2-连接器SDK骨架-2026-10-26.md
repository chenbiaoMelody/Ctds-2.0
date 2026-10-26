# WBS-3.6.2 任务卡：连接器 SDK 骨架（2026-10-26）

| 项 | 内容 |
| --- | --- |
| 来源 | 台账「进行中」行 17 新头"下一候选包 = WBS-3.6.2 连接器 SDK 骨架（WBS 行 301）——立卡归新会话冷启动，待编排师指示" + 「待编排师」行 19"下一待编排师事项 = WBS-3.6.2 立卡指示"；开发日志 `docs/logs/Ctds-项目开发日志-26-10-26-0814.md` 续点"下一步项①"；本会话冷启动复述后编排师 AskUserQuestion 裁定"**立卡 WBS-3.6.2（推荐）**"（2026-10-26） |
| 级别与性质 | L1-3 平台功能**实施包（连接器域首个代码包）**（WBS 行 301 预算 **1 会话**）→ **实施类，走两级设计门禁**（非界面类：业务可读行为清单 + 接口契约表 + 边界值与异常行为；落盘 `docs/designs/WBS-3.6.2-lofi.md` / `-hifi.md`）；**≤1 天任务卡 → 两级设计一并提交、一次确认**（章程 2.6.3，沿 3.5.2 / 3.5.3 / 3.5.4 同款先例） |
| 需求锚点核对 | WBS `docs/C-TDS项目总体实施计划与WBS.md` **行 301**（连接器 SDK 骨架：六项功能框架〔身份/资源/产品/合约/交付/使用控制〕，依赖 3.6.1 ✅ 已闭环）；规格 `docs/specs/C-6.1-6.3-接入连接器.md` **V1.0**：**行为 2 = 核心承载**（六项功能以连接器 SDK 框架承载〔规则 1 映射表〕/ 路由一致性〔规则 2〕/ **框架边界〔规则 3：只承载接入框架，不含适配器实现与管理界面〕**）+ 行为 1（接入前提与身份锚）/ 行为 3（身份管理）/ 行为 4（交付：网关唯一入口 + 需求方侧发起端）/ 行为 5（使用控制：**消费判定结果 + fail-closed，双向必有**——AGENTS §4 双向用例硬约束在连接器域的落点，规则 3 明文"由 3.6.2 / 3.6.8 实现与测试承接"）+ 行为 11（适配器随连接器分发〔SDK 框架，3.6.2〕→ SPI 承载面）/ 行为 12~14（源接入 / 自检 / 先判定后取数的框架级接口）；规格 §4 非目标 1（SDK 模型与接口实现归 3.6.2~3.6.4）/ §7 Q2-A（身份锚 = 所属主体 DID + 唯一连接器编号，不另设独立身份）/ Q6-A（平台侧唯一执行）/ Q9-A（**本域不占错误码码值**，1010 段留实施落定）；PRD 行 145（C-6.1 六项功能 P0 原文）；ADR-002 §3-1（`connector-sdk/` 在 monorepo 目录规划内）§3-2（**V1.0 期间 monorepo 内开发，对外独立分发时再分仓**） |
| 上游能力边界 | **平台六域已闭环**（3.1.x 主体/DID、3.2.x 空间、3.3.x 目录、3.4.x 合约、3.5.x 交付与沙箱）——SDK 六项功能**只封装既有端点、零第二业务口径**；**判定语义唯一权威在合约域**（ADR-019/020，内部判定端点权限 `contract.internal.read` 仅 gateway-internal——**SDK 禁直调**，判定消费经网关交付链）；**网关唯一入口**（ADR-005/022：JWT 鉴权 + 剥离入站伪造身份头——SDK 经网关必须持 Bearer 令牌；V1.0 仅暴露交付面路由）；**注册核验服务归 3.6.3 未开工**（SDK 骨架的连接器编号为上下文占位）；`common` 六组件 + std-adapter **只读复用**（本卡预期零 common 改动）；ADR-006/015（**SDK 骨架零加解密面**——凭据采引用制模型，加解密归平台侧）；**本卡不改网关配置、不改既有服务、不改门禁配置、不建表、不迁移** |
| 交付物 | ① **SDK 模块** `connector-sdk/`（Maven jar 库：六项功能门面 + ConnectorContext 身份锚 + PlatformClient 平台调用封装 + SourceAdapter 适配器 SPI〔四类源枚举，零实现类〕+ fail-closed 语义 + 单测〔含行为 5 双向用例〕）+ 根 pom 挂载；② **两级设计** `docs/designs/WBS-3.6.2-lofi.md` / `-hifi.md`（一并提交，一次确认）；③ **ADR-026 连接器 SDK 契约**（编码段落盘，沿 3.5.2 Q9-A 新建 ADR-022 先例——供 3.6.5/3.6.6 适配器与 3.6.8 集成锚定）；④ 本任务卡 + 台账 + 会话日志；⑤ 验收剧本更新建议（预期 = 无需更新：C-6.1 剧本 S2 六腿演示前提本就登记"3.6.2~3.6.7 交付后执行"，界面入口占位待 3.6.7 不变） |
| 关闭条件 | ① **✅ 2026-10-26 达成**——两级设计经编排师**一次确认**（lofi Q1~Q7 全采 A + D1 不拆分，设计转 V1.0 = 编码契约生效）；② 编码段本地全绿：`mvn -B -ntp compile / test / checkstyle:check`（connector-sdk 模块）+ 新增用例独立计数只增不减 + 覆盖率实测留痕（整体 ≥70% 线，SDK 非核心六模块）；③ 4 视角评审循环闭环（≤3 轮 + 独立复审）+ 提交前自检单（章程附录 B1）；④ 编排师验收 + PR 合并（显式指示）；演示口径 = **单测级展示**（六门面调用 + fail-closed 三态），端到端受理联调归 3.6.8 |
| 分支 | `feat/WBS-3.6.2-连接器SDK骨架`（自本地 `main` = `3ef0a3f` 开立——**自然携带 3.6.1 合并段批（未推送），随本卡 PR 入库沿链式先例第七例**；立卡批随本段落盘提交，**确认后推送**——沿 3.5.2"确认后推送分支"先例） |
| 纪律声明 | **只做骨架**——不实现适配器（3.6.5/3.6.6，SPI 零实现类）、不做注册核验与生命周期（3.6.3/3.6.4，连接器编号为占位）、不做管理界面（3.6.7）、不做端到端联调（3.6.8）；**不占 1010 错误码码值**（Q9-A：码值归 3.6.3；平台码原样透传）；**不自研密码学、零加解密面**（凭据引用制）；**不直调平台内部端点**（usage-checks / internal/* 权限隔离，判定消费经交付链）；**不改网关配置与既有服务**（网关路由扩面登记为演进项，不在本卡做）；**零新第三方依赖**（全部使用 Boot BOM 内既有构件——spring-web/jackson）；不碰其它任务卡与其它域；发现规格缺口走变更流程，不在设计里私自补需求 |

---

## 一、范围与设计

- **做什么 / 不做什么**：见 lofi §1（业务可读行为清单 8 条）与 §2（边界划清 9 条）；技术契约见 hifi（装配 / 包结构 / 接口签名 / 端点映射 / 测试清单）。
- **设计组织**：非界面类两级设计——lofi（方向确认：行为清单 + 决策点 Q1~Q7 + 边界值）→ hifi（定稿确认：接口契约表 + 类清单 + 测试清单 = 编码契约）；≤1 天一并提交，请编排师**一次确认**。

**设计要点摘要（供快速表决）**

1. **单模块 jar 库**：`connector-sdk` 挂根 pom（ADR-002 §3 规划位），六门面 + 身份锚 + 平台客户端 + 适配器 SPI；**零新第三方依赖**（spring-web RestClient + jackson，均 Boot BOM 既有构件，无 dependencies.md 新登记项）；
2. **六项功能 = 六个能力门面**（接口 + 默认实现），每门面对应平台既有端点（映射表见 hifi §5）——**零第二业务口径**，业务能力全部由平台域提供（行为 2 规则 1）；
3. **身份锚 = ConnectorContext**（属主主体 + DID + 连接器编号占位〔3.6.3 签发后注入〕）+ **Bearer 令牌**（TokenProvider 接口）——沿 Q2-A"不另设独立身份"：网关剥离伪造身份头，SDK 不自注身份头；
4. **使用控制 = 消费判定结果 + fail-closed**（Q6-A）：凭合约凭据发起（网关交付链自动判定），SDK 三态——放行续行 / 平台拒绝原样透传且不重试 / **不可达抛异常绝不放行**；**无本地放行路径**；双向用例硬约束（行为 5 规则 3）；
5. **适配器 SPI**：SourceAdapter（type / check / read）+ SourceType 四类源枚举 + 源接入登记模型（**凭据引用制，禁明文**）——零实现类，归 3.6.5 / 3.6.6；
6. **错误语义零新增**：平台码透传（ConnectorException 携带原码原文）；SDK 自身错中性三分类（不可达 / 超时 / 配置错），**不占 1010 段**；
7. **契约沉淀 ADR-026**（编码段）：门面 / SPI / 身份形态 / fail-closed / 错误透传 / 演进（网关路由扩面、真实联调 3.6.3 后另卡、适配器包消费面）。

## 二、决策点与确认留痕（✅ 已确认 2026-10-26；章程 2.6.3）

> 决策点全文与备选业务影响见 lofi §3（**Q1~Q7 + D1**）。
>
> **确认留痕（✅ 2026-10-26 编排师 AskUserQuestion 回复"一次确认"）**：**Q1~Q7 全采建议口径 A + D1 不拆分** → ① lofi / hifi 转 **V1.0（方向定稿 / 编码契约即时生效）** + 确认记录回填（两文件末节）；② 本卡关闭条件① 达成、§四 状态行与确认段行更新；③ 台账行 19 确认销项；④ 确认批提交并**推送 origin**；⑤ **编码段测试先行**（hifi §9 前置检查七项第一动作），本会话上下文余量充足就地续接（沿 3.6.1 合并段"即席执行"先例）。

| 编号 | 问题 | 裁定 |
| --- | --- | --- |
| Q1 | 模块形态与依赖 | **A**：单 Maven jar 模块挂根 pom；零新第三方依赖；checkstyle 纳管；无 boot 打包插件 |
| Q2 | 平台调用面与身份口径 | **A**：单一入口（默认网关）+ Bearer 令牌；不自注身份头；connectorNo 占位；网关扩路由 = 演进项 |
| Q3 | 六门面方法集 | **A**：每门面 2~3 方法，接口 + 默认实现（对外契约） |
| Q4 | 适配器 SPI 形态 | **A**：SourceAdapter + 四枚举 + 凭据引用制；零实现类 |
| Q5 | fail-closed 语义 | **A**：三态（放行 / 拒绝透传不重试 / 不可达绝不放行）；双向用例硬约束 |
| Q6 | 错误语义 | **A**：平台码透传 + SDK 中性三分类；不占 1010 码值 |
| Q7 | 契约沉淀 | **A**：编码段新建 ADR-026 连接器 SDK 契约 |
| D1 | 体量 / 是否拆分 | **不拆分**（预估 850~1050 行，新模块原子集沿 3.4.2 / 3.5.2 先例） |

## 三、规格行为 → SDK 承载面映射表（评审与验收口径）

| 规格行为（框架承载面） | SDK 承载 | 测试口径 |
| --- | --- | --- |
| 行为 1 接入前提与身份锚（规则 2：主体 DID 身份锚 + 唯一编号） | ConnectorContext（subjectNo / did / connectorNo 占位） | 上下文构建 + 占位语义单测 |
| 行为 2 六项功能框架与平台域映射（规则 1 映射表 / 规则 2 路由一致 / 规则 3 框架边界） | 六门面（接口定型 + 默认实现）+ 端点映射表（hifi §5） | 每门面 ≥1 正向调用单测（mock 平台响应，断言路径 / 头 / 载荷；**字段与枚举名取自平台真实 View / DTO**） |
| 行为 3 身份管理（查验本主体 + DID 状态） | IdentityCapability（查本主体登记详情 / 查 DID 状态） | 正向 + 拒绝透传单测 |
| 行为 4 交付（凭据 = 已生效合约 / 网关唯一入口 / 需求方侧发起端） | DeliveryCapability（API 型经网关交付面 / 文件型〔幂等键必填〕/ 查交付详情） | API 型路径 + 凭据头编码断言 + 越界拒绝透传；文件型载荷（deliveryType=FILE）+ 幂等键本地校验 + 状态门槛 409 透传 |
| 行为 5 使用控制（判定唯一权威 / fail-closed / **双向必有**） | UsageControlCapability（执行使用〔同网关交付面，消费判定结果〕/ 查用量汇总）——**三态：放行 / 拒绝透传不重试 / 不可达抛异常**；**无本地放行路径** | **双向用例**（放行 200 + 越界 403 `1008C0020`）+ 不可达 / 超时 / 缺 data / 非统一结构响应 fail-closed 单测（硬约束） |
| 行为 11 适配器承载（四类源 / 适配器随连接器分发〔SDK 框架〕） | SourceAdapter SPI + SourceType 四枚举（零实现类） | SPI 契约测试（类型 / 方法签名 / 凭据引用制模型） |
| 行为 12 源接入登记与凭据保护（规则 2：凭据禁明文） | SourceRegistration 模型——**凭据引用制字段**（无明文凭据字段） | 模型无明文凭据字段断言 |
| 行为 13 连通性自检（只读探测 / 三类失败原因） | SourceAdapter.check() 返回三类原因可枚举结果模型 | 结果模型分类断言 |
| 行为 14 先判定后取数（拒绝不触达源） | SourceAdapter.read() 契约注记 + 使用控制三态（判定拒绝在平台侧先行） | 契约注记测试 + 行为 5 三态覆盖 |
| 行为 6~10（注册 / 核验 / 绑定 / 升级 / 注销） | **不在本卡**（归 3.6.3 / 3.6.4 平台侧服务；连接器编号在本卡为上下文占位） | —— |
| 规格行为 2 验收标准 1~3（六腿受理 / 留痕 / 拒绝一致） | **端到端验证归 3.6.8**（本卡为单测级；网关扩路由为演进项） | —— |

## 四、执行记录

| 项 | 内容 |
| --- | --- |
| 状态 | 🟢 **修复批完成（2026-10-26）——评审循环 1 去重 P1×4 + P2×10 + P3×9 全量处置：契约层按平台真实 View / DTO / 枚举值域重写（模型 15 + 六门面 + PlatformClient + Credentials + Pages），5 测试类重写 **39 例全绿**、checkstyle 0 违规、行覆盖 **94.22%**；hifi V1.1 / ADR-026 / 本卡同步勘正；下一步 = 修复批提交推送 → 独立复审（对象 = 修复批增量）+ 补独立视角④ → 编排师验收**（历史留痕）🔴 **评审循环 1 完成（2026-10-26）——①规格与设计符合性 FAIL（P1×5）/ ②安全供应链 PASS-with-findings（P0/P1 无，P2×3）/ ③一致性与重复 FAIL（P1×4）/ ④测试质量降级自检 FAIL（子智能体两次重试限流：100s + 240s，按记忆口径降级主智能体自检 + 留痕待补独立评审；结论与①③同源）——去重后 P1×4（根因 = 编码段仅做路径级复核、请求/响应字段级未对齐平台真实契约 + 网关可达性表述不实）+ P2×10 + P3×9；修复批方案见 §七，归新会话（本会话上下文达停机线）**（历史留痕）🟡 **编码段完成（2026-10-26，本会话就地续接）：SDK 骨架落地全绿——compile ✅ / test 26 例全过 ✅ / checkstyle 0 违规 ✅ / jacoco 行覆盖 89.84%（≥70% 线实测留痕）✅ / 根 reactor validate ✅；ADR-026 落盘；实测 1559 行（含 pom/ADR，D1 不拆分口径不变、预估偏差已落账 §五）；下一关口 = 提交前自检单 → 4 视角评审（另会/子智能体）→ 修复循环 ≤3 → 编排师验收**（历史留痕）🟡 **确认段完成（2026-10-26）：编排师"一次确认"（Q1~Q7 全采 A + D1 不拆分）→ lofi / hifi 转 V1.0（编码契约生效）+ 确认批推送 origin；编码段测试先行（本会话就地续接，上下文余量充足沿 3.6.1 即席先例）**（历史留痕）🟡 **立卡段完成（2026-10-26）：任务卡 + lofi V0.9 + hifi V0.9 落盘，待编排师一次确认（Q1~Q7 + D1，≤1 天两级一并提交）** |
| 确认段（本会话） | **2026-10-26 编排师 AskUserQuestion 回复"一次确认"** = **Q1~Q7 全采建议口径 A + D1 不拆分** → ① lofi / hifi 转 **V1.0** + 确认记录回填（版本头 / 末节确认记录块，正文零改动）；② 本卡 §二 确认留痕与裁定表 + 关闭条件① ✅ + §四 状态行与本行；③ 台账行 19 确认销项（下一待办 = 编码段推进）；④ 确认批提交并**推送 origin**；⑤ 编码段测试先行（hifi §9 前置检查七项第一动作）。**本段零代码、零依赖、零门禁配置改动** |
| 编码段（本会话） | **前置检查七项实测**：① spring-web/spring-test 6.2.19 在库（RestClient + MockRestServiceServer 绑定可用）②根 pom 挂载仅增行（modules + dependencyManagement）③checkstyle 单模块跑通（multiModuleProjectDirectory 惯例）④⑤ §5 端点映射表 **13 端点路径逐项对照源码核验一致** + ApiResult 结构确认（code="0" 成功 / 9 位错误码 / traceId）⑥全仓门禁复跑留待 PR（gate check 承载）⑦台账沿承三件确认不变。**实现**（40 主代码文件 + 5 测试类）：六门面接口 + Default\* 实现 + ConnectorContext / TokenProvider / StaticTokenProvider / PlatformClient / PlatformResponse / ConnectorException / SdkErrorType + model 13 record + SPI 7 件；**实现偏差留痕** = hifi `capability/impl/` 子包并入 `sdk` 包（包级私有实现 = 最小对外 API 面，判定语义零变化；ADR-026 §2-2 登记）。**验证全绿**（命令与结果）：`mvn -B -ntp compile` BUILD SUCCESS / `mvn -B -ntp test` **Tests run: 26, Failures: 0, Errors: 0**（含 UsageControlFailClosedTest 六例 = 行为 5 双向用例 + 不可达 fail-closed 硬约束）/ `mvn checkstyle:check` **0 violations** / jacoco CLI 实测 **行覆盖 89.84%**（只测量不开门禁）/ 根 `mvn -pl connector-sdk validate` 通过。**编码批 `c10cae5` 提交**（48 文件 1559 行含 pom/ADR + .gitkeep 废弃删除）。**本段零依赖新增（Boot BOM 既有构件）、零门禁配置改动、未动既有服务与网关** |
| 修复段（2026-10-26） | **评审循环 1 处置（全量闭环）**：P1×4 + P2×10 + P3×9 逐项落地——①**契约层重写**（按平台源码逐项核对：`SubjectView` / `DatasetView` / `ProviderProductView` / `CatalogProductDetail` / `DealViews` / `DealRequests` / `DeliveryViews` / `UsageViews` / `PageResult` / `RegisterDatasetRequest` / `CreateProductRequest` / 枚举值域 `DatasetType` / `PricingModel` / `ProductStatus` / `DeclareLevel` / `DatasetStatus` / `SubjectStatus` / `DidStatus` / 网关字符集三档）；②**判定落点修正**（`executeUsage` / `requestApiDelivery` 改走网关交付面 `GET /api/v1/data-products/{productId}`——403 `1008C0020` 在该端点真实可发生，双向用例从"mock 假绿"变可复现）；③**凭据头编码按网关真实解码器勘正**（`UriUtils.encode` RFC 3986 ↔ 网关 `UriUtils.decode`；`contractNo` **原值发送** + 本地字符集校验〔网关不解码该头〕；原修复批用 `URLEncoder` 会把空格编成 `+`，网关不解 `+`）；④**幂等键不再编码**（平台按原值 `[A-Za-z0-9._-]{1,128}` 校验，编码会改变键值语义）+ 文件型交付请求体补 `deliveryType=FILE`（平台 `DeliveryRequest` 必填字段，原修复批漏发）；⑤**分页信封**（三个 `/mine` 返回 `PageResult<T>` + 分页参数本地校验 1~10000 / 1~100）；⑥**异常语义**（成功响应缺 data → UNREACHABLE fail-closed；非统一结构拒绝响应 → 中性分类；令牌为空 → CONFIG）；⑦**路径变量**统一 `pathSegment` 编码（含 `registerDataset`）。**实现偏差留痕** = hifi §5「requestDelivery 单一方法」拆为 API / 文件 / 详情三腿（平台请求体与判定链不同，合并即必错）。**文档勘正**：hifi V1.1（§2 / §3 / §4 / §5 / §7 就地注记）+ ADR-026（§2-2 / §2-4 / §2-5 / §3 / §4-1）+ 本卡 §三 / §五 / §六。**验证全绿**：`mvn -B -ntp compile` BUILD SUCCESS / `mvn -B -ntp test` **Tests run: 39, Failures: 0, Errors: 0**（26 → 39，只增不减）/ `mvn checkstyle:check` **0 violations** / jacoco 行覆盖 **94.22%（277/294）** / 根 `mvn -pl connector-sdk validate` 通过。**本段零依赖新增、零门禁配置改动、未动既有服务与网关** |
| 立卡会话 | 2026-10-26（冷启动承接日志 `-0814` 续点 → 复述四要素 → 编排师 AskUserQuestion"立卡 WBS-3.6.2"）；**立卡前素材盘点（一路只读 Explore 子智能体 + 定点核验，本会话）**：① 平台六域对外端点全量盘点（subject 4 Controller / did 6 / space 6 / catalog 7 / contract 9+ / delivery+gateway——**关键事实：内部判定端点 `usage-checks` 权限 `contract.internal.read` 仅 gateway-internal（SDK 禁直调，判定消费经交付链）**；网关 JWT + 剥离入站伪造身份头（SDK 经网关必须 Bearer）；交付发起 `POST /api/v1/deliveries`〔幂等键 + 三凭据头经网关注入〕）；② 模块骨架参照（common/crypto 库模块无 spring-boot-maven-plugin；**9 个服务模块均显式声明 maven-checkstyle-plugin（common 未声明不跑）——SDK 沿服务模块惯例显式声明**；connector-sdk 一级目录 relativePath = `../pom.xml`）；③ 根 pom 现状（modules 未含 connector-sdk——本卡挂载；dependencyManagement 待登记）；④ ADR-002 §3-2 V1.0 期间 monorepo 内开发；⑤ 台账沿承三件不变（DB-64 / F-W1 / 8086 停用）。**本段零代码、零依赖、零门禁配置改动**（分支自本地 main `3ef0a3f` 开立，携带 3.6.1 合并段批） |

## 五、体量登记（立卡时填写）

| 项 | 数值 / 说明 |
| --- | --- |
| 实测（修复批） | **34 文件 +710/−317（跟踪内）** + **5 新增文件**（`Credentials` ~70 / `Pages` ~35 / `PageResult` ~11 / `ProductAccessRequest` ~11 / `FileDeliveryRequest` ~14 行）+ 1 删除（`DeliveryRequest`）；测试 **39 例**（builder 6 / 路由 10 / 交付 8 / fail-closed 10 / SPI 5）；行覆盖 **94.22%（277/294）**；**超 400 行指引说明** = 评审循环 1 的 P1×4 根因在契约层（模型字段 / 请求体 / 分页信封 / 判定落点全错），修复必须整层重写并整层重写测试（26 → 39 例），无法再拆；D1"新模块原子集不拆分"口径不变，编排师验收时知悉 |
| 实测（编码段） | **48 文件 1559 行**（git diff --cached --stat 实测）：主代码 **40 文件 1021 行** + 测试 **5 文件 439 行** + 根 pom 8 行 + 模块 pom 41 行 + ADR-026 48 行 + .gitkeep 删除；**超预估 850~1050 约 50%，偏差原因 = record 一类一文件的 Java 惯例（OneTopLevelClass 规则）+ 契约级 Javadoc 密度**——D1"新模块原子集不拆分"口径不变；测试 **26 例**（builder 5 / 路由 9 / 幂等 2 / fail-closed 6 / SPI 4），独立计数只增不减基线 = **26**；行覆盖 **89.84%** |
| 实测（立卡段） | 任务卡（本卡）**69 行**；lofi **117 行**；hifi **142 行** → 合计 **328 行**（`wc -l` 实测）；无代码、无测试（编码段开工） |
| 预估（编码段） | 有效变更 **850~1050 行**：SDK 主代码（~25 文件：六门面接口+实现 / Context / TokenProvider / PlatformClient / 异常三分类 / SPI 五件 / 模型 ~10 record）≈450~600 + 单测（mock 平台响应，含双向三态用例）≈300~400 + pom/ADR-026/文档 ≈100；**零新第三方依赖**（Boot BOM 既有构件） |
| 超 400 行指引说明 | 本卡为**新模块实施包**，骨架（门面 + 客户端 + SPI + 测试）为不可分原子集（沿 3.4.2 / 3.5.2 新模块先例）；**D1 建议不拆分**，请编排师在确认时一并表态 |
| 文档 | 本卡 / lofi / hifi / 台账 / 会话日志；ADR-026 归编码段 |

---

## 六、提交前自检单（章程附录 B1；编码段末落盘 2026-10-26）

| # | 项 | 结果 |
| --- | --- | --- |
| 1 | 两级设计门禁已过 + 实现与设计逐条一致 | ✅ lofi/hifi **V1.0 经编排师一次确认**（陈述见两文件末节确认记录）；**修复批后 hifi 转 V1.1 就地勘正**（§2 包结构 / §3 签名 / §4 调用链 / §5 端点与诚实边界 / §7 测试清单）。实现与 V1.1 逐条一致；**两处偏差均留痕**：① impl 子包并入 sdk 包（ADR-026 §2-2）；② 交付门面拆三腿（hifi §3 勘正注 + §四 修复段行） |
| 2 | 规格验收标准 → 设计 → 测试用例 → 剧本步骤 映射 | 见本卡 §三（修复批勘正后口径）：行为 1→builder 4 例（含 T16）/ 行为 2→路由 10 例（六门面正向 + 分页信封 + 分页越界）/ 行为 3→身份 2 例 / 行为 4→交付 8 例（API 腿路径与编码 / 越界 / 文件型载荷与幂等键 / 状态门槛 / 详情）/ 行为 5→fail-closed 10 例（**双向 + 不可达 + 超时 + 缺 data + 非统一结构 + 令牌缺失**）/ 行为 11~13→SPI 5 例（含掩码）；**剧本步骤 = 无需更新**（C-6.1 S2 六腿前提已登记"3.6.2~3.6.7 交付后执行"，界面入口占位待 3.6.7；骨架期单测级演示口径见下） |
| 3 | 复用声明 + 规格外实现声明 | **复用检索**：`common` 六组件逐项核对（errorcode / auth / crypto / idempotency / logging / pagination）——SDK 为**对外分发客户端库**（ADR-002 §3-2），**刻意不依赖 common**（避免把平台内部组件链带入参与方构件）；平台错误语义以**同构复刻解析**（PlatformResponse ↔ ApiResult code/message/traceId/data，码值语义零改写）而非依赖复用；身份头常量与凭据头三件**按 ADR-005/ADR-022 口径在 SDK 内定义常量**（网关权威，SDK 不注入身份头）。**规格外实现声明 = 空**（范围 = 行为 1~5 / 11~14 框架承载面，行为 6~10 未涉） |
| 4 | 本地检查命令与结果摘要 | **修复批复跑（最新）**：`mvn -B -ntp -pl connector-sdk compile` → **BUILD SUCCESS**；`mvn -B -ntp -pl connector-sdk test` → **Tests run: 39, Failures: 0, Errors: 0, Skipped: 0**；`mvn -B -ntp -pl connector-sdk checkstyle:check -Dmaven.multiModuleProjectDirectory=<仓库根>` → **0 Checkstyle violations**；`mvn -B -ntp -pl connector-sdk org.jacoco:jacoco-maven-plugin:prepare-agent test org.jacoco:jacoco-maven-plugin:report` → **行覆盖 94.22%（277/294）**（只测量不开门禁）；根目录 `mvn -B -ntp -pl connector-sdk validate` → **BUILD SUCCESS**（reactor 挂载有效）；全仓门禁由 PR gate check 承载（本地未全量复跑，登记） |
| 5 | 高风险点自查 | **并发**：SDK 无共享可变状态（PlatformClient 无实例可变字段、Default\* 无状态、TokenProvider 由调用方实现——线程安全由实现方负责，SDK 文档口径）；**事务**：无（纯客户端库，零持久化）；**加解密**：零加解密面（凭据引用制模型，无明文凭据字段——反射断言覆盖；不自研密码学）；**性能**：非性能敏感路径（参与方侧客户端；连接 2s / 读 10s 默认超时可配；无线程池与缓冲池） |
| 6 | 验收剧本更新建议（业务语言） | **无需更新**：C-6.1 / C-6.2 / C-6.3 三份剧本保持 V1.0；本卡为骨架（SDK 库），剧本六腿演示前提"3.6.2~3.6.7 交付后执行"不变；3.6.8 集成演练时按真实联调结果统一修订界面入口（沿 C-5.1 剧本 Q9 占位机制） |
| 7 | 业务可读交付说明（完成什么 / 如何演示 / 注意什么） | **完成什么**：参与方业务系统接入平台的"标准工具箱"骨架——引一个库即可经六个功能门面（身份/资源/产品/合约/交付/使用控制）调用平台；**如何演示**：单测级展示（**39 例**自动化用例逐条覆盖六门面调用语义 + 使用控制的"放行 / 越界拒绝 / 平台不可达"三态——越界、不可达、超时、平台异常返回时工具箱**绝不放行**）；演示命令 `mvn -B -ntp -pl connector-sdk test`；**注意什么**：① 本卡是骨架，端到端真实受理演示在 3.6.8 集成演练；② 连接器编号待 3.6.3 平台注册服务签发后注入；③ SDK 不直调平台内部判定接口——判定一律由平台在交付链中权威完成 |

**评审跟踪项登记（本批登记，不修复）**

| 编号 | 内容 | 承接对象 |
| --- | --- | --- |
| T1 | **11 方法经网关不可达**（修复批勘正：身份 / 资源 / 产品 / 合约四读腿与写腿无路由 → 404；`data-products/mine` / `publishProduct` 命中路由但缺凭据头 → 400；文件型交付 / 交付详情 / 用量汇总无路由 → 404）——SDK 已按平台路径封装，端到端受理不可用（诚实边界）；仅 `requestApiDelivery` / `executeUsage` 经网关可用 | 网关扩路由随 3.6.7 / 3.6.8 与部署期统一设计（ADR-026 §4-1） |
| T2 | SDK 视图 record 字段为平台响应核心子集（宽松映射）——静态字段 / 枚举名已随修复批逐项对齐，**运行期响应演变**未验证 | 3.6.8 集成演练（ADR-026 §3 / §4-3） |
| T3 | 编码批实测 1559 行超预估 850~1050 约 50% | 已落账 §五（偏差原因：record 一类一文件 + Javadoc 契约密度；D1 原子集口径不变），评审知悉 |

## 七、评审循环 1 结果与修复批方案（2026-10-26；修复归新会话）

**四视角判定**：① 规格与设计符合性 **FAIL**（P1×5 / P2×5 / P3×3，红线全干净）② 安全与供应链 **PASS-with-findings**（无 P0/P1；P2×3 路径变量编码 / 头值 CR-LF / 异常逃逸；P3×4）③ 一致性与重复 **FAIL**（P1×4 / P2×6 / P3×6）④ 测试质量 **降级自检 FAIL**（子智能体两次重试 `inference exceeds tpm/rpm limit`〔间隔 100s + 240s〕→ 按记忆口径降级主智能体自检并留痕，**修复批后须补独立视角④**；自检结论与①③同源：mock 自造形状 + 越界码在 mock 端点不可达 = 假绿）。

**去重后 P1×4（根因 = 编码段仅路径级复核，请求 / 响应字段级未对齐平台真实契约；网关可达性表述不实）**：

| # | 问题 | 修复方向（修复会话执行） |
| --- | --- | --- |
| P1-1 | `executeUsage` 以 `deliveryType=API` 调 `POST /api/v1/deliveries`，而该端点 API 型**不触发策略判定**（仅 FILE 分支 check）——"消费判定结果 + 双向用例"不成立（mock 403 `1008C0020` 在该端点不可发生） | **改用网关交付面**：`executeUsage` / `requestDelivery(API)` 改 `GET /api/v1/data-products/{productId}`（被交付资源 = 3.5.2 Q4-A 演示探针端点；网关 `PolicyExecutionGlobalFilter` 转发前判定，403 `1008C0020` 真实可发生）；`UsageRequest` 增 productId；FILE 型交付与 `getDelivery` 登记"经网关不可达"（T1 扩写） |
| P1-2 | "交付 / 使用两腿经网关即通"不实——网关 V1.0 唯一静态路由 = `/api/v1/data-products/**`（且交付链强制 `X-Ctds-Contract-No`）；13 方法经网关全不可用（四读腿 404 + 产品腿缺凭据头 400） | 修正 ADR-026 §4-1 / hifi §5 注 / T1：**V1.0 网关仅 data-products 交付面一处**；SDK 六门面路径全部登记"端到端受理归 3.6.8（网关扩路由统一设计）"；产品腿经网关调用时补凭据头（骨架期按网关交付面语义构造） |
| P1-3 | 模型字段与分页信封错（`name` vs `subjectName`、`datasetId` vs `id`、`applicantNo/providerNo` vs `requesterSubjectNo/providerSubjectNo`、`DeliveryView.status` 平台无、`UsageSummaryView.usedCount` 平台无〔实际 allowedCount/deniedCount/quota〕；三个 `/mine` 返回 `PageResult` 而 SDK 绑 `List`） | 按平台真实 View/`PageResult` 纠正模型与解析（SDK 新增 `PageResult<T>` 模型；`/mine` 方法返回分页信封 + 分页参数）；测试 mock 改用平台真实字段名 / 枚举值 |
| P1-4 | `CreateContractCommand(productId,purpose,territory)` vs 平台 `DealRequests(productId,templateNo,templateVersionNo,clauseValues)`——真实调用必 400 | 命令模型按平台 DTO 重设计 |

**去重后 P2×10**：① 凭据头 UTF-8 百分号编码（ADR-022 §2.5-2，与网关 `URLDecoder` 配对）+ 测试断言编码值；② 路径变量按 pathSegment 编码 + 字符集/长度校验；③ `PlatformClient` 兜底捕获 `RestClientException`/序列化异常 → `ConnectorException`（UNREACHABLE/CONFIG）；④ `code="0"` 但 data 缺失按 UNREACHABLE fail-closed；⑤ 空命令异常统一 `ConnectorException(CONFIG)`（现抛 IllegalArgumentException）；⑥ 枚举值域（`DatasetType` DATASET/API/REPORT/MODEL、`PricingModel` FREE/PER_CALL/MONTHLY/REVENUE_SHARE——测试现用中文假绿）；⑦ purpose/territory **可空**（ADR-022 §2.5：策略要素交判定，SDK 现强制必填）；⑧ 幂等键自动生成不可复用（平台幂等 = requester+key）——改为强制调用方传键或返回键；⑨ 补用例：跨主体拒绝透传 / 交付门面状态门槛拒绝 / 404 透传 / 非 JSON 响应体；⑩ `CreateProductCommand` 补 `priceAmount`/`categoryCode`、`RegisterDatasetCommand` 补可选 `tags`/`declareImportant`。**P3×9**：文件数 49 / pom 43 行勘正、builder 5 例、hifi §2 偏差指针、引文（`delivery-internal` 亦获授权、C-1.2 §4 非目标第 5 项）、台账/日志续点、网关注入表述、`ConnectorContext` 惰性登记、最小 API 面补 `PlatformResponse`/`StaticTokenProvider`、`executeUsage` 复用交付请求组装、`SourceRegistration.toString` 掩码建议。

**修复批约束**：技术实现层修正（业务判定语义零变更：六门面 / fail-closed / 凭据不变 / 零 1010 码值）；hifi §5/§4 与 ADR-026 同步勘正（版本注记"修复批勘正"）；**修复后须重跑全绿（compile/test/checkstyle/覆盖率）+ 独立复审（对象 = 修复批增量）+ 补独立视角④**；不新增依赖、不改网关与既有服务。

**修复批执行结果（2026-10-26）**：P1×1~4 全部按上述方向落地；P2×10 全部落地（含编码器按网关 `UriUtils` 口径勘正、幂等键改必填原值、文件型请求体补 `deliveryType`）；P3×9 全部落地（文件数 / pom 行数 / builder 例数 / hifi 偏差指针 / 引文 / 台账续点 / 网关注入表述 / `ConnectorContext` 惰性登记 / 最小 API 面补 `PlatformResponse` + `StaticTokenProvider` / `SourceRegistration.toString` 掩码）。**审核中新增发现并同批修复**（不属评审原项）：`contractNo` 凭据头不应编码（网关不解码该头且设 `[A-Za-z0-9._-]{1,64}` 正则）→ 改原值 + 本地校验；幂等键不应编码（平台按原值校验，编码破坏键语义）；`registerDataset` 路径变量由表单编码改 `pathSegment`；`getDelivery` 补正向用例。**验证**：39 例全绿 / checkstyle 0 / 行覆盖 94.22% / reactor validate 通过。

---

> **边界复述（红线自查）**：本卡为**骨架实施包**——零适配器实现（3.6.5/3.6.6）、零注册核验（3.6.3）、零管理界面（3.6.7）、零端到端联调（3.6.8）；**不占 1010 错误码码值**（平台码透传）；**零加解密面**（凭据引用制，不自研密码学）；**不直调平台内部端点**；**不改网关配置 / 既有服务 / 门禁配置 / common**；规格与设计只从 C-6.1~6.3 V1.0 + ADR 派生，未私自补需求；不碰其它任务卡与其它域；演示库零接触（DB-64 沿承）。
