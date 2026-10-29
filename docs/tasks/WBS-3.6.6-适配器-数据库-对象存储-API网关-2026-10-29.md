# WBS-3.6.6 任务卡：适配器-数据库/对象存储/API 网关（2026-10-29）

| 项 | 内容 |
| --- | --- |
| 来源 | 台账「进行中 / 待编排师」行 17 / 19 新头"下一候选包 = WBS-3.6.6 适配器-数据库/对象存储/API 网关（台账行 305）"+ 开发日志 `docs/logs/Ctds-项目开发日志-26-10-29-0720.md` 续点"下一步项 1"；本会话冷启动复述续点后编排师会话指示"**进行 WBS-3.6.6 立卡**"（2026-10-29） |
| 级别与性质 | L1-3 平台功能**实施包（连接器域第五个代码包 · 三类通用源适配）**（WBS 行 305 预算 **1 会话**）→ **实施类，走两级设计门禁**（非界面类：业务可读行为清单 + 接口契约表 + 边界值与异常行为；落盘 `docs/designs/WBS-3.6.6-lofi.md` / `-hifi.md`）；**≤1 天任务卡 → 两级设计一并提交、一次确认**（章程 2.6.3，沿 3.5.4 / 3.6.2 / 3.6.3 / 3.6.4 / 3.6.5 同款先例） |
| 需求锚点核对 | WBS `docs/C-TDS项目总体实施计划与WBS.md` **行 305**（适配器-数据库/对象存储/API 网关：**三类通用源适配器**；依赖 3.6.2 ✅ 已闭环）；规格 `docs/specs/C-6.1-6.3-接入连接器.md` **V1.0**：**行为 11 规则 1 = 核心承载**（四类源全量——政务归 3.6.5 已交付，**数据库 / 对象存储 / API 网关三类归本卡**；适配器随连接器分发；先登记后使用）+ **行为 12 / 13 / 14 = 复用既有面、按三类源补适配口径**（凭据保护、自检三查、读取摘要与留痕均由 3.6.5 端点承载，本卡只补适配器实现与调用边界安全）+ **§7 Q7-A**（四类源全量 / 凭据国密 / 先判定后取数，已裁决）；PRD V1.0 **行 147 C-6.3**（多源适配：政务共享交换平台、数据库、对象存储、API 网关等，**P0**）；上游契约 ADR-026（SDK 适配器 SPI / 凭据引用制 / 三类失败原因）/ ADR-029（连接器源适配契约：登记面 / 凭据保护 / 自检编排 / 适配器注册表，§2.6 明文"3.6.6 只新增三类适配器实现"）/ ADR-006·ADR-015（国密唯一入口）/ ADR-005（API 与错误码）/ ADR-019·ADR-020（判定单点权威）/ ADR-008（std-adapter 边界） |
| 上游能力边界 | **SPI 已冻结、实现仅 1 类**：`connector-sdk/…/spi/` 七类型冻结（`SourceAdapter.type()/check()/read()`、`SourceType` 四值、`SourceRegistration` 凭据引用制、`SourceCheckResult` + 三类失败枚举、`SourceReadRequest` / `SourceReadResult` 摘要口径）；`adapter/gov` 5 类为唯一实现；**三类适配器零实现**。**登记 / 凭据 / 自检 / 读取面已就位**：`connector-service` 6 端点（对外 5 + 内部 1）/ `connector_source` + 取号表（V3）/ 凭据 SM4 三列 + 掩码 + 密钥不可用 503 fail-closed / 自检 200 业务结论 + 三类原因 + 留痕 / 读取摘要 + 五要素留痕 / `SourceAdapterRegistry` 按 `type()` 自动索引（同类型重复启动失败）；**新增适配器唯一装配点 = `SourceAdapterConfig` 追加 `@Bean`**。**依赖现状**：`connector-service` 已有 `mysql-connector-j`(runtime) + `spring-boot-starter-jdbc`；`connector-sdk` 仅 `spring-web` + `jackson-databind`，**无 JDBC 驱动、无对象存储 SDK**（全仓零 MinIO/S3/OSS 客户端）。**码位**：`1010C0010` 全仓零占用（3.6.5 明文留给本卡）；`1010C0011`（适配承载未开放）现有 **4 处测试断言**将随三类开放失效（编码段须改写并留痕）。**债务承接**：DB-90（自检结论直读留痕表）/ DB-91（源地址解析级校验含 DNS）挂本卡；**DB-88（运营方读面规格冲突）须先走规格变更流程，本卡不得实施**（立卡即提示编排师，红线 4）。**本卡零新增端点、零迁移、零新权限点、零新第三方依赖、零门禁配置改动、零回改 3.6.3/3.6.4/3.6.5 端点语义** |
| 交付物 | ① **`connector-sdk` 新增三类适配器包**（`adapter/database` / `adapter/objectstorage` / `adapter/apigateway`；各含适配器实现 + 协议客户端/报文模型，共约 12 类）；② **`services/connector-service` 增量**：`SourceAdapterConfig` +3 `@Bean`、**DB-91 解析级主机防护**（领域端口 + 基础设施实现 + 自检/读取调用点）、**DB-90 留痕读面泛化**（通用"按 action + 前缀取最近一条"方法替换源仓储直读）；③ **测试**：SDK 三类适配器单测 + 服务侧三类源集成用例（含既有 4 处 `1010C0011` 断言改写）+ DB-91 / DB-90 专项；④ **ADR-030 连接器三类通用源适配契约**（编码段落盘，七字段齐备）；⑤ 两级设计 `docs/designs/WBS-3.6.6-lofi.md` / `-hifi.md`（一并提交，一次确认）；⑥ 本任务卡 + 台账 + 会话日志；⑦ 验收剧本更新建议（预期 = **C-6.3 剧本步骤与判定无需修改**；维护说明建议补一行"3.6.6 已落地"） |
| 关闭条件 | ① **✅ 2026-10-29 达成**——两级设计经编排师**一次确认**（Q1~Q9 全采建议口径 A + D1 不拆分），lofi / hifi 转 **V1.0 = 编码契约生效**；确认批提交（**远端 main 受保护，直推被拒 → 随本卡 PR 入库**）；② **待达成**——编码段本地全绿：`mvn -B -ntp compile / test / checkstyle:check`（connector-sdk + connector-service）+ 用例数只增不减（基线 = connector-service **206 例**、connector-sdk **65 例**，mvn 实测为准）+ 覆盖率实测留痕（≥70%）+ **测试先行**（先落用例取得红基线）；③ **待达成**——4 视角评审循环 1（零 P0 / 零 S0）+ 修复批 + 独立复审 PASS + 提交前自检单（章程附录 B1，§五）；④ **待达成**——编排师验收通过 + 显式合并授权 → PR MERGED main；演示口径 = **接口级演示**（三类源登记 + 自检通过 + 三类失败原因 + 读取摘要 + 解析级拒绝 + 变更复检），界面演示归 3.6.7、端到端取数归 3.6.8 |
| 分支 | `feat/WBS-3.6.6-三类通用源适配器`（自本地 `main` = `3fc863e` 开立——**自然携带 3.6.5 收口批（未推送 origin），随本卡 PR 入库沿链式先例**；立卡 / 确认批随本段落盘提交——**远端 main 受保护，随本卡 PR 入库**） |
| 纪律声明 | **只做三类通用源适配器 + 调用边界加固 + 读面泛化**——不做政务源改造（3.6.5 已交付）、不做管理界面（3.6.7）、不做端到端交付链改造与网关路由扩面（3.6.8）；**DB-88（运营方读面）不实施**（规格变更前置，红线 4）；**不建第二判定语义**（不调合约域判定、不复制判定口径）；**不自实现密码学**（对象存储不做 SigV4 签名；凭据一律 `common-crypto` SM4）；**不默认放开内网**（`allow-private-endpoint` 默认 false，仅测试 / 演示显式放开）；**零新第三方依赖**（数据库驱动由宿主提供，SDK 只用 JDK 标准 API）；**零新增端点 / 迁移 / 权限点**；不改网关与路由、不动门禁配置、不碰演示库（DB-64 沿承）；不碰其它任务卡与其它域；发现规格缺口走变更流程，不在设计里私自补需求 |

---

## 一、范围与设计

- **做什么 / 不做什么**：见 lofi §1（业务可读行为清单 8 条）与 §2（边界划清 10 条）；技术契约见 hifi（模块装配 / 零迁移 / 类清单 / 适配器契约 / 失败映射 / 安全口径 / 测试清单）。
- **设计组织**：非界面类两级设计——lofi（方向确认：行为清单 + 决策点 Q1~Q9 + 边界值）→ hifi（定稿确认：适配器与客户端契约 + 失败映射表 + 主机防护时序 + 测试清单 = 编码契约）；≤1 天一并提交，请编排师**一次确认**。

**设计要点摘要（供快速表决）**

1. **三类适配器全部落 SDK、零新增依赖**（Q1-A）：只用 JDK 标准 API（`java.sql` / `java.net.http` / `java.net.URI`）+ 既有 `spring-web`；数据库驱动由宿主提供（`connector-service` 已有 `mysql-connector-j`）；`connector-sdk` pom **零改动**。
2. **对象存储 = 预签名 URL 形态**（Q2-A）：源地址登记对象定位，访问凭据 = 提供方生成的预签名 URL；适配器只发 GET，**不做签名实现**（规避"自行实现密码学"红线）；AK/SK 直连列为备选（需新依赖审批）。
3. **数据库 = JDBC 只读探针**（Q3-A）：自检执行 `SELECT 1`（连通 + 凭据），读取只取 schema 级摘要；**JDBC URL 安全参数白名单**（显式拒绝 `allowLoadLocalInfile` / `autoDeserialize` / `allowUrlInLocalInfile` / `allowMultiQueries` 等危险参数）。
4. **失败原因按协议特征映射三类**（Q4-A）：连接 / 超时 / 解析失败 → 网络不可达；401 / SQLState 28xxx / 1045 / 签名类 403 → 凭据无效；403 / 1142 / 1044 → 权限不足；不可归类 → 502 中性（不猜）。
5. **DB-91 落调用边界**（Q5-A）：`SourceHostGuard` 在自检 / 读取**调适配器前**解析主机（含对象存储凭据 URL 与 JDBC host），命中回环 / 内网 / 链路本地即拒绝且零触达；登记 / 变更受理期不联网（避免 TOCTOU）。
6. **DB-90 本卡泛化**（Q6-A）：留痕仓储新增"按 action + detail 前缀取最近一条"通用读面，源仓储改为复用，删除自造 detail 解析的第二套读法。
7. **+1 码、零权限点**（Q7-A）：`1010C0010`「源地址不被允许（解析到受限网络 / 访问形态不支持）」；复用 `1010C0001` / `1010C0003` / `1010C0004` / `1010C0011`（兜底）与 `1010S0004`；不启用 `1010S0003`。
8. **演示前置显式化**（Q8-A）：自动化测试用既有 Testcontainers MySQL + 进程内 HTTP 桩；演示期数据库源指向 `sc-mysql`、对象存储用 `sc-minio` 预签名 URL、API 网关指向本地桩；**演示配置显式放开内网，代码默认仍为拒绝**。
9. **端到端归 3.6.8**（Q9-A）：S1 步骤 1~7 本卡接口级可演示；S2 步骤 1~2 归 3.6.8；S2 步骤 3~5 既有锚不变；剧本无需修改。

## 二、决策点与确认留痕（✅ 已确认 2026-10-29）

> 决策点全文与备选业务影响见 lofi §3（**Q1~Q9 + D1**）；技术契约见 hifi §1 / §5 / §6 / §7 / §9。
>
> **确认留痕（✅ 2026-10-29 编排师会话回复"确认"）**：**Q1~Q9 全采建议口径 A + D1 不拆分** → ① ✅ lofi / hifi 转 **V1.0**（方向定稿 / 编码契约即时生效）+ 两级文件末节回填；② ✅ 关闭条件① 达成、§四 状态行更新；③ ✅ 台账 L17 / L19 确认销项；④ ✅ 确认批提交（**远端 main 受保护，直推被拒 → 随本卡 PR 入库**，不绕过）；⑤ 🟡 **编码段测试先行**（另会冷启动，第一动作 = hifi §11 十项前置检查）。

| 编号 | 问题 | 建议 | 备选与业务影响（摘要） |
| --- | --- | --- | --- |
| Q1 | **三类适配器依赖策略**（关键） | **A**：零新增第三方依赖——只用 JDK 标准 API + 既有 `spring-web`；数据库驱动由宿主提供（服务已有 `mysql-connector-j`），SDK pom 零改动 | B 引入 MinIO 官方 SDK（AK/SK 直连）——新依赖须审批 + 供应链扫描 + `dependencies.md` 登记，1 会话预算风险 / C 只做模拟源——虚报能力，不可选 |
| Q2 | 对象存储访问形态 | **A**：预签名 URL（源地址 = 对象定位；凭据 = 提供方生成的预签名 URL；适配器只发 GET，主机做解析级校验） | B AK/SK + SDK 签名（依赖 Q1-B，且签名实现属密码学面）/ C 仅公开读对象（凭据必填硬约束冲突，不可选） |
| Q3 | 数据库适配安全与只读 | **A**：`SELECT 1` 只读探针 + 读取仅 schema 级摘要 + JDBC URL 安全参数白名单（拒危险参数） | B 只连接不查询（权限维度不可验证）/ C 放开全部 JDBC 参数（`allowLoadLocalInfile` / `autoDeserialize` 文件读取与反序列化风险） |
| Q4 | 三类失败原因映射 | **A**：协议特征映射三类（连接/超时 → 不可达；401/SQLState 28xxx/1045/签名类 403 → 凭据无效；403/1142/1044 → 权限不足）；不可归类 → 502 中性 | B 全部记"网络不可达"（原因不可读，违行为 13-2）/ C 403 一律记权限不足（签名过期被误判） |
| Q5 | DB-91 解析级校验落点 | **A**：`SourceHostGuard` 在自检/读取调用前解析主机（含凭据 URL 与 JDBC host），命中受限网络即拒绝零触达；受理期不联网 | B 登记期解析并固化 IP（DNS 漂移 + TOCTOU）/ C 不做（DB-91 不闭环） |
| Q6 | DB-90 留痕读面泛化 | **A**：留痕仓储新增通用"按 action + 前缀取最近一条"，源仓储复用、删自造解析 | B 维持现状并登记延期（债务不闭环） |
| Q7 | 错误码与权限点 | **A**：+1 码 `1010C0010`；零新增权限点；`1010C0011` 保留兜底 | B 复用 `1010C0001`（地址被拒与参数非法混同）/ C 每类源专属码（码位膨胀） |
| Q8 | 演示与自检目标形态 | **A**：测试用 Testcontainers MySQL + 进程内 HTTP 桩；演示期 `sc-mysql` / `sc-minio` / 本地桩，演示配置显式放开内网 | B 改默认放开内网（破坏安全默认）/ C 只做接口级不连真实容器（S1 步骤 3~6 无法验收） |
| Q9 | 剧本与端到端边界 | **A**：S1 步骤 1~7 本卡接口级可演示；S2 步骤 1~2 归 3.6.8；剧本无需修改 | B 本卡改交付链（超范围）/ C 剧本改判定（无业务变化） |
| D1 | 体量 / 是否拆分 | **不拆分**（预估 900~1200 行，同一能力链：三类适配器 + 调用边界加固 + 读面泛化） | 拆开产生"部分源已适配"的不完整交付，3.6.8 返工 |

## 三、规格行为 → 承载面映射表（评审与验收口径）

| 规格条目 | 本卡承载 | 测试口径 |
| --- | --- | --- |
| 行为 11 规则 1 + 验收 1（四类源全量 / 每类由适配器承载） | 三类适配器实现注册（`DATABASE` / `OBJECT_STORAGE` / `API_GATEWAY`），四类源均有适配器 | U1~U3（三类 `type()` 与注册）/ T1（四类自检均不再 `1010C0011`） |
| 行为 11 规则 2 + 验收 2（先登记后使用 / 未登记默认拒绝） | 既有读取前置不变（3.6.5 承载） | 既有 `ConnectorSourceNoTouchTest` 保持（仅"未开放类型"一条改写） |
| 行为 12 规则 1 / 2 + 验收 1 / 2（登记四要素 / 凭据国密 + 掩码 / 变更留痕） | 复用 3.6.5 端点与凭据链；本卡只定义三类源的凭据形态（JDBC `user:password` / 预签名 URL / Authorization 值）与掩码口径 | T2（三类源凭据密文非明文 + 掩码 + 变更留痕） |
| 行为 12 规则 3 + 验收 3（非本归属不可读 / 防枚举） | 既有归属校验不变；**DB-88 运营方读面不实施**（规格变更前置） | 既有 `ConnectorSourceOwnershipApiTest` 保持 |
| 行为 13 规则 1 + 验收 1（只读三查 / 源端零变更） | 三类 `check()` 只读语义（数据库 `SELECT 1` / 对象存储 GET / API GET），零写入 | U1~U3（只读断言）+ T3（源端变更计数 0） |
| 行为 13 规则 2 + 验收 2~4（三类失败原因可读） | 协议特征映射表（Q4-A），200 业务结论 + 失败留痕 | U1~U3（三类失败逐一）+ T4（服务侧三类失败对照） |
| 行为 13 规则 3（自检不等于授权） | 端点不写授权态（既有口径不变） | 既有用例保持 |
| 行为 14 规则 1 + 验收 2（先判定后取数 / 拒绝不触达源） | 内部读取端点契约不变；本卡补"解析级拒绝零触达"与"读取摘要不含本体" | U1~U3（摘要无本体）+ T5（解析拒绝零触达） |
| 行为 14 规则 3 + 验收 1 / 3（摘要 / 留痕五要素） | 三类 `read()` 返回摘要；留痕沿用既有五要素 | T6（读取摘要 + 留痕可查）+ DB-90 用例 |
| DB-91（解析级校验含 DNS） | `SourceHostGuard` + 调用点接入 | T5（回环 / 内网 / 解析失败三类拒绝） |
| DB-90（留痕仓储泛化） | 通用"按 action + 前缀取最近一条"方法替换源仓储直读 | T7（读面等价 + 无直读 SQL 断言） |
| C-6.3 剧本 S1 步骤 1~7 | 三类源自检 / 三类失败 / 变更复检接口级可演示 | 联调（编码段留痕） |
| C-6.3 剧本 S2 步骤 1~2 / 界面 | **不在本卡**（3.6.8 / 3.6.7） | —— |

## 四、执行记录

| 项 | 内容 |
| --- | --- |
| 状态 | 🟡 **编码段已交付且全仓门禁 GREEN，待 4 视角评审循环 1（2026-10-29）**——SDK 三类适配器（3 包 10 类）+ 服务侧 `SourceHostGuard` / DB-90 留痕读面泛化 / 装配与码表；**测试先行**（红基线 → 绿）；connector-sdk **105 例**（基线 65，+40）、connector-service **237 例**（基线 206，+31）、checkstyle **0 违规**；门禁第二发 `gate-report-20261029-222042` **ExitCode 0 / PASS=15 / FAIL=0**（coverage 92.33% / mutation 69.34% / sast 0）；第一发 RED 唯一红灯（DB-87 偶发）已确定性修复并留档；关闭条件② 全绿达成，③④ 待评审 / 验收 |
| 立卡素材盘点（本会话） | ① **SPI 面**：七类型冻结，`SourceAdapter.type()/check()/read()`；三类适配器零实现；② **服务面**：6 端点 / 2 表 / 凭据三列 / 自检 200 业务结论 / 读取摘要 + 五要素；新增适配器唯一装配点 = `SourceAdapterConfig`；③ **依赖面**：`connector-sdk` 仅 spring-web + jackson（无 JDBC 驱动 / 无对象存储 SDK）；`connector-service` 已有 `mysql-connector-j` + `starter-jdbc`；④ **码位**：`1010C0010` 零占用；`1010C0011` 现有 4 处断言将失效；⑤ **债务**：DB-90 / DB-91 可本卡闭环，**DB-88 须规格变更前置**；⑥ **测试基线**：connector-service 206 例 / connector-sdk 65 例；⑦ **演示**：`sc-mysql` / `sc-minio` 容器在运行，`allow-private-endpoint` 默认 false |
| 设计文件 | `docs/designs/WBS-3.6.6-lofi.md`（**V1.0，方向定稿**）、`docs/designs/WBS-3.6.6-hifi.md`（**V1.0，编码契约**）；非界面类，无原型 HTML |
| 确认（编排师） | ✅ **2026-10-29"确认"** = Q1~Q9 全采建议口径 A + D1 不拆分；确认批随本段落盘（**main 受保护，随本卡 PR 入库**） |

## 五、提交前自检单（章程附录 B1，编码会话填写）

> 编码段回填（2026-10-29）。命令与结果均为本地实测。

1. **两级设计门禁已过**：lofi / hifi **V1.0** 经编排师一次确认（2026-10-29"确认" = Q1~Q9 全采 A + D1 不拆分，见 §二）；实现与已确认设计**逐条一致**，偏差 2 项均已登记（见第 3 条）。
2. **映射表**：§三 已建立"规格条目 → 承载面 → 测试口径"初始映射，编码段实测编号如下——U1 `DatabaseSourceAdapterTest`（11）/ U2 `JdbcUrlPolicyTest`（9）/ U3 `ObjectStorageSourceAdapterTest`（11）/ U4 `ApiGatewaySourceAdapterTest`（9）；T1 `ConnectorSourceCheckAvailabilityApiTest`（3，由 3.6.5"未开放"用例改写）/ T2 `ConnectorSourceRegistrationApiTest` +4 / T3 `ConnectorSourceAmendApiTest` +2 / T4 `ConnectorSourceAdapterFailureApiTest` +6 / T5 `ConnectorSourceHostGuardApiTest`（6）+ `InetAddressSourceHostGuardTest`（7）/ T6 `ConnectorSourceReadApiTest` +4 / T7 `ConnectorSourceCheckApiTest` +2 / T8 既有断言改写 7 处（见下）。
3. **复用声明与规格外实现声明**：
   - **复用**（检索过程：`grep 1010C0011` / `grep findLastCheck` / 读 3.6.5 编码批 + ADR-029）：3.6.5 的登记 / 变更 / 自检 / 读取编排、`CredentialProtector` 国密链路、`SourceAdapterRegistry` 按类型索引、`ConnectorAuditService` 留痕、`SharedMySqlContainer` + JDK `HttpServer` 测试基建**全部原样复用**；`CredentialResolver` 沿用 `sdk.adapter.gov` 既有类型（**零新类型**）；`ConnectorActionLog` 直接作为通用读面返回类型（**零新类型**）。
   - **规格外实现**：**空**。两项"实现偏差"均非新需求：① hifi §4 通用读面签名拟定 `Optional<String> detail`，但既有响应字段 `lastCheck.checkedAt` 需要结论时间 → 返回 `ConnectorActionLog`（含 `createdAt`），语义等价、响应字段零变化；② hifi 写作 `ConnectorAuditRepository` / `JdbcConnectorAuditRepository`，仓库实际类型为 `ConnectorLogRepository` / `JdbcConnectorLogRepository`，按实际类型落地。两偏差见 ADR-030 §2.6。
   - **T8 断言改写逐条留痕（7 处，属能力开放的必要同步、非削弱）**：① `ConnectorSourceNoTouchTest.unsupportedTypeNeverTouchesAdapter` → `restrictedHostNeverTouchesAdapter`（DATABASE 读取不再 `1010C0011`，改为解析级拒绝 `1010C0010`）；②~④ `ConnectorSourceCheckUnavailableApiTest` 3 例整体改写为 `ConnectorSourceCheckAvailabilityApiTest`（三类源可自检，其中"读取不可用"腿由 T6 三类源摘要用例承接）；⑤ `ConnectorSourceBoundaryApiTest.unsupportedTypeCheckWritesDeniedTrail` → `restrictedHostCheckWritesFailureTrail`（`source.denied/1010C0011` → `source.check FAILURE/NETWORK_UNREACHABLE`）；⑥ `ConnectorSourceBoundaryApiTest.rejectsOversizeCredential` 256 → 1025（凭据上限 256 → 1024，hifi §2）；⑦ `ConnectorSourceOwnershipApiTest` / `ConnectorSourceRegistrationApiTest` 中 DATABASE 端点由 `https://…` 改为 `jdbc:mysql://…`（hifi §7.3 URL 形态；受理期新增 JDBC 形态与参数白名单校验 = hifi §5 #1）。
4. **本地全部检查命令与结果**（工作区 = `feat/WBS-3.6.6-三类通用源适配器`）：
   - `mvn -B -ntp -pl connector-sdk,services/connector-service org.jacoco:jacoco-maven-plugin:0.8.12:prepare-agent test org.jacoco:jacoco-maven-plugin:0.8.12:report checkstyle:check` → **BUILD SUCCESS**；connector-sdk **105 例 0 失败 0 跳过**、connector-service **237 例 0 失败 0 跳过**；checkstyle 两模块 **0 违规**（日志 `build-output/w366-final-verify.log`）。
   - 覆盖率（jacoco.csv 汇总行覆盖）：connector-sdk **90.68%**（642/708）、connector-service **92.11%**（1494/1622），均 ≥70%。
   - 测试先行红基线留痕：SDK `build-output/w366-red-sdk.log`（编译失败 = 实现缺失）；服务侧 `build-output/w366-red-service.log`（HostGuard 6/6、NoTouch/Read/Registration 等红）。
   - 已知偶发：`GovExchangeHttpMappingTest.mapsEmptyBodyToUnhandledFailure`（**DB-87** 既有登记，双模块联跑环境类 flaky），本轮复跑绿。
   - 全仓门禁：**第一发 RED**（`gate-report-20261029-213516`，`PASS=14 FAIL=1 SKIP=2 PENDING=5`）——唯一红灯 = `coverage` 段，失败用例 = `GovExchangeHttpMappingTest.mapsEmptyBodyToUnhandledFailure`（既有债务 **DB-87** 偶发；同套全量用例在 `unitTest` 段已 PASS，互为佐证）。按"门禁无特批"完成**确定性修复**（空应答用例改独立桩实例，零断言变化，连跑 **10/10 绿**，提交 `57802d1`）→ **第二发 GREEN**：`gate-report-20261029-222042`，**ExitCode 0，PASS=15 / FAIL=0 / SKIP=2 / PENDING=5**；coverage **overall line 92.33%（12205/13219）**、core `[auth 92.02% / crypto 89.74% / did 96.16%]`、mutation **69.34%（95/137）**、sast **0 findings**、secretsScan **0 hits**；`ConfigSha256` 两发逐字一致 = **门禁配置零改动**自证。**两发报告一并留档**（红灯不隐匿）。
5. **高风险点自查**：① **JDBC 攻击面**：参数白名单（默认拒绝，9 键）在**受理期**校验 + 连接 `readOnly=true` + 仅执行 `SELECT 1` 与 `information_schema` 计数（无用户 SQL、零写语句，测试以桩驱动断言"无写语句 / 已设只读"）；② **预签名 URL 明文面**：凭据仅 SM4 密文落库、掩码留末 4 位、适配器 `toString` 全掩、日志 / 响应零原文（T2 直查库 + T6 断言摘要）；③ **DNS TOCTOU**：受理期不解析、**调用前**解析判定（`getAllByName` 全量），解析失败 fail-closed；④ **超时**：HTTP 连接 3s / 读 5s、JDBC 语句超时 5s，异常映射"网络不可达"；⑤ **并发 / 事务**：适配器无共享可变状态；拒绝 / 失败留痕为 `REQUIRES_NEW` 独立提交，不随业务异常回滚丢失；⑥ **能力开放后的兜底**：`1010C0011` 保留为防御性分支。
6. **验收剧本更新建议**：**无需修改步骤与判定**。可选（经 PO 批准）在维护说明补一行："3.6.6 已交付，四类源（政务 / 数据库 / 对象存储 / API 网关）自检与读取均可用；数据库与 API 网关源按只读探测口径使用，对象存储使用提供方生成的临时授权链接。"
7. **业务可读交付说明**：**完成了什么**——连接器现在支持四类数据源，新增的数据库 / 对象存储 / API 网关三类可以登记、自检、读取摘要；访问前会再校验一次地址（防内网穿透），三类失败原因中文可读；**如何演示**——接口级演示（见日志"业务可读总结"四步）；**注意事项**——对象存储授权链接有有效期；内网源默认不可用（演示需显式放开）；界面与端到端取数分别归 3.6.7 / 3.6.8。

## 六、体量登记（编码交付时回填）

| 项 | 数值 |
| --- | --- |
| 预估（立卡阶段） | SDK 三类适配器 + 客户端/报文 ≈ 450 行；服务侧（装配 3 Bean + HostGuard + 留痕读面泛化）≈ 220 行；测试 ≈ 420 行；ADR-030 ≈ 90 行；**合计 ≈ 900~1200 行**（测试为主，业务代码为加性）；文档（设计约 380 行 + 任务卡/日志约 220 行）另计——沿 3.6.5"含测试与文档"D1 豁免口径申请不拆分 |
| 实测（编码交付） | **新增 2 108 行 + 既有修改 578 插入 / 119 删除**（21 个既有文件）：① SDK 新增生产代码 **834 行**（`adapter/database` 4 类 354 / `adapter/objectstorage` 3 类 264 / `adapter/apigateway` 3 类 216）；② 服务侧新增 **154 行**（`SourceHostGuard` 22 + `InetAddressSourceHostGuard` 132）+ 既有 7 类增量修改（装配 / 编排 / 读面 / 码表 / DTO）；③ 新增测试 **1 120 行**（SDK 5 类 779，服务侧 3 类 341）+ 既有 9 个测试类扩展与 7 处断言改写；④ ADR-030 与文档另计。超出 400 行常规 PR 上限（D1 已确认"不拆分"：同一能力链，拆开产生"部分源已适配"的不完整交付，3.6.8 返工——沿 3.6.5 同款豁免口径） |

---

> **边界复述（红线自查）**：本卡**零新增端点 / 迁移 / 权限点 / 第三方依赖 / 门禁配置改动**；**DB-88 不实施**（规格变更前置）；**不自实现密码学**（对象存储不做签名实现，凭据一律 `common-crypto`）；**不默认放开内网**；**不改网关与路由、不碰演示库**；补测只允许新增与强化断言，既有 4 处 `1010C0011` 断言改写须逐条留痕（属能力开放的必要同步，非削弱）；发现实现与已确认口径不一致 → 停止上报，不擅自修。
