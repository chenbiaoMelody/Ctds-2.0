# WBS-3.1.12 C-1.2 测试补全与集成 · 高保真设计

| 字段 | 内容 |
| --- | --- |
| 任务卡 | WBS-3.1.12（产出定义：测试达标、与连接器注册联调；前置 3.1.9~3.1.11 已关闭） |
| 编码契约 | 本表定稿并经 PO 确认后，任何与本表不一致的实现 = 打回项（章程 2.6） |
| 关联 | 规格 C-1.2（行为 1~5 + §4 非目标 + §6 边界声明）；上游卡 `3.1.8`/`3.1.9`/`3.1.10`（§七 7.3）/`3.1.11`（§七 7.5、7.7）；ADR-001（质量工具链）/ADR-005（错误码与分页）/ADR-010（集成测试容器规范）/ADR-015（KMS）/ADR-017（DID 契约）；剧本 C-1.2（S1/S2/S3 + S4）；台账 DB-06/DB-07/DB-14 |
| 设计裁决 | **Q1=A / Q2=A / Q3=A / Q4=B / Q5=A**（2026-09-25 编排师一次确认，Q1~Q5 均采建议口径）+ **D1 体量不拆分**（同日 15:3x 补裁决）——本契约自确认起生效，实现与本表不一致 = 打回项 |

## 1. 达标口径与工具形态（本包只测量，不开门禁）

- **达标线**（AGENTS.md §4 / 章程 4.2）：`services/did`（**核心模块**）行覆盖 **≥80%**、变异杀除率 **≥60%**；本卡触及模块整体行覆盖 **≥70%**；
- **工具**：JaCoCo 0.8.13 + PIT（`org.pitest:pitest-maven`）**命令行临时注入**——**不改任何 pom、不改 `gates-config.json`、不改 `dependencies.md`**；阈值与门禁接入归质量基建包 `2.2.8`（DB-06）承接；
- **环境**：Docker 在线（Testcontainers 真实 MySQL）；无 Docker 时集成用例被跳过而产生的数字**不予采信**（ADR-010 第 4 条）；
- **产出**：实测数字回填 §1.3 表 + 交付说明"接入建议"（供 2.2.8）。

### 1.1 覆盖率执行命令（口径固定）

```
mvn -B -ntp -pl services/did -am install -DskipTests
mvn -B -ntp -pl services/did org.jacoco:jacoco-maven-plugin:0.8.13:prepare-agent test org.jacoco:jacoco-maven-plugin:0.8.13:report
```
- 报告落 `services/did/target/site/jacoco/`（`index.html` / `jacoco.csv`）；其余触及模块（`services/subject-service`、`services/kms`、`std-adapter`、`common/*`）按需同法测量；
- **报告产物不入库**（`target/` 已在忽略范围）；只有数字回填设计文档与交付说明。

### 1.2 变异测试范围（耗时控制）

- **必测类**：`DidIssuanceService`、`DidResolutionService`、`DidVerificationService`、`DidInteropService`、`DidManagementQueryService`、`DidDemoSignatureService`；
- **抽检**：`interfaces` 层控制器（如耗时不可控，登记说明并说明覆盖比例）；
- 执行命令：`mvn -B -ntp -pl services/did org.pitest:pitest-maven:mutationCoverage -DtargetClasses=com.ctds.did.application.*`（目标类范围按本卡盘点结果微调）；
- **优先级**（单会话预算不足时）：覆盖率达标 > 变异必测类达标 > 变异抽检；**禁止**只报告好看的部分（未测项必须如实登记）。

### 1.3 实测数字（编码会话回填）

**测量时点**：2026-09-25 16:0x（**补测前基线**）；命令见 §1.1 / §1.2；口径偏差与工具链处置见 §11。

| 模块 | 行覆盖 | 分支覆盖 | 达标线 | 判定 | 变异杀除率 | 变异达标线 | 判定 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| `services/did`（核心） | **96.05%**（584/608，缺 24 行 / 7 类） | 88.27%（158/179） | ≥80% | **达标（+16.05pt）** | **73.17%**（检测 30 / 生成 41，其中无覆盖突变 2）——`DidIssuanceService` 基线试跑 | ≥60% | **达标（+13.17pt）** |
| `services/subject-service` | **96.22%**（890/925） | 82.79% | ≥70% | **达标** | —（非核心，抽检） | — | — |
| `services/kms` | **94.96%**（226/238） | 83.33% | ≥70% | **达标** | —（非核心，抽检） | — | — |
| `std-adapter` | **93.33%**（168/180） | 73.68% | ≥70% | **达标** | —（非核心，抽检） | — | — |
| `common/*`（触及模块） | auth **92.05%**（324/352）、crypto **89.74%**（306/341）、errorcode **79.45%**（58/73）、idempotency **88.54%**（224/253）、logging **90.85%**（129/142）、pagination **100%**（36/36） | — | 核心子模块 ≥80%（`common-crypto` / `common-auth`）其余 ≥70% | **达标**（最低 errorcode 79.45% ≥70%） | — | — | — |

**`services/did` 未覆盖明细（24 行 / 7 类，逐类如实登记）**：`DidKmsHttpClient` 9 行、`SubjectStatusHttpClient` 4 行、`DidJdbcRepository` 3 行、`DidIssuanceService` 3 行、`DidInteropService` 2 行、`DidServiceApplication` 2 行（`main` 入口，无测试价值）、`DidManagementQueryService` 1 行。补测后是否变化见 §11 终测记录。

> 基线参照（历史手工值，非本卡实测）：`3.1.8` did 行覆盖 92.8%、`3.1.9` 94.0% → 94.8%（瞬时 jacoco，未入库依赖）；**变异测试全仓零先例**——本卡为首例，工具链偏差与处置见 §11 第 1 条。

### 1.4 终测数字（T/F 补测落地后，2026-09-25 16:2x）

| 模块 | 行覆盖（终测） | 分支覆盖 | 判定 |
| --- | --- | --- | --- |
| `services/did`（核心） | **96.22%**（585/608，补测 +1 行） | 88.83% | **达标**（≥80%） |
| `services/subject-service` / `kms` / `std-adapter` / `common/*` | 同 §1.3（本卡未改其实现与用例，数字未变） | — | **达标** |

**变异（必测 6 类，口径 = PIT CLI + JUnit5 插件，见 §11 第 1 条）**：合计 **132 突变 / 检测 90 = 68.18%**（≥60% **达标**；剔除"测试集未覆盖"的 25 个后 **84.11%**）。

| 必测类 | 突变 | 检测 | 无覆盖 | 杀除率 |
| --- | --- | --- | --- | --- |
| `DidIssuanceService` | 41 | 30 | 2 | 73.17% |
| `DidInteropService` | 31 | 25 | 0 | 80.65% |
| `DidVerificationService` | 19 | 17 | 0 | 89.47% |
| `DidDemoSignatureService` | 10 | 9 | 0 | 90.00% |
| `DidResolutionService` | 7 | 7 | 0 | **100%** |
| `DidManagementQueryService` | 22 | 0 | **22** | **无法计分（无覆盖）** |

> `DidManagementQueryService` 无独立单元测试类（其行为由 `DidManagementIntegrationTest` 承载），而变异口径下 PIT 只运行进程内可重复执行的测试集，容器化集成用例因"固定造数 + 同 JVM 重复执行会撞唯一键"不可重复运行 → **如实登记"该口径下无覆盖"**，不制造假数字；**改善建议随 2.2.8（DB-06）接入时一并决定**（补充轻量单元用例，或明确变异口径只覆盖单元可测类）。

## 2. 盘点方法与映射表（编码会话第一步完成并回填）

- **盘点口径**：规格 C-1.2 **5 个行为 × 各条验收标准** → 现有测试锚点（后端 16 个 `services/did` 测试类 + subject/kms/std-adapter 相关类 + 前端 3 个 `views/did` spec）→ **缺口标记**（有锚点 / 无锚点 / 弱锚点）；
- **缺口的判定沿用"红锚"标准**：每条补测必须能指出"**删掉哪段实现会变红**"，否则视为空洞断言（章程 4.2 禁止）；
- 盘点产物同时作为任务卡 §三 映射表的输入。

### 2.1 映射表骨架（编码会话逐条回填）

| 规格条目 | 承载能力 | 现有锚点（**已盘点**） | 缺口（**已盘点**） |
| --- | --- | --- | --- |
| 行为 1（自动签发与主体绑定 / 幂等防重 / 失败停"待签发"可重试） | 3.1.8 | `DidIssuanceServiceTest`（11 例：四要素 / 幂等×2 / **并发签发 8 线程** / 失败→重试 / 吊销必填 / 五要素 / 重签新序号 / 越界拒绝 / 非待签发门槛 / 无明文私钥）、`DidIssuanceIntegrationTest`（21 例：含真实 SM2 三查、解析三态、响应与库表 0 明文、交错门槛） | ①"待认证/待审核/已驳回/认证失败 → 无有效 DID"**DID 侧无该状态面**（触发侧口径，处置见 §2.2-①）；②"主体仍为已入驻"未断言（现仅断记录 `PENDING_ISSUE`）；③时间字段未钉格式（T2 覆盖） |
| 行为 2（解析三态、无 L4 敏感信息） | 3.1.9 | `DidResolutionServiceTest`（5 例：三态 + 文档损坏 + 格式非法）、`DidIssuanceIntegrationTest`（集成解析三态 + 敏感明文扫描 + 文档损坏收敛） | ①L4 明文扫描为 3 模式（身份证 / 手机 / 64-hex）**弱锚点**（登记观察项，不扩模式以免误判）；②"状态集仅两值"无专项枚举断言（现由类型 + 三态用例隐性锚定） |
| 行为 3（验证三查、失败原因、留痕三要素且无原文） | 3.1.9 | `DidVerificationServiceTest`（10 例：三查通过 / 篡改 / 已吊销 / 绑定失败 / 绑定服务不可用 / 未登记 / 入参非法 / 超长 / 异常收敛 / 留痕三要素且无原文）、`DidIssuanceIntegrationTest`（6 验证例，真实 SM2）、`SubjectStatusClientFailureIntegrationTest`（真实不可达端口） | ①留痕 `verifiedAt` 仅 `isNotBlank`（**T2**）；②"验证不等于授权"仅由出参字段集间接锚定（口径说明，见 §2.2-③） |
| 行为 4（吊销：理由必填被拒 / 不可逆 / 无恢复 / 重签旧行保留） | 3.1.8 + 3.1.11 | `DidIssuanceServiceTest`（吊销必填 / 五要素留痕 / 重签新序号且旧行保留）、`DidIssuanceIntegrationTest`（吊销 + `/recovery` → 404 + 守卫释放）、前端 `IndexView.spec.ts`（理由必填零请求 / 取消 / 确认 / 无恢复入口）、`DidSourceGuard.spec.ts`（回滚字样扫描） | ①重签"**必须全新密钥对**"仅断 `keyRef` 不同、未比公钥不同（**弱锚点**，T8 附带强化）；②"取消 → 零留痕"仅"零请求"（**F3**）；③**并发吊销无用例**（**T8**） |
| 行为 5（跨空间互认三态与双向口径、未开放占位） | 3.1.10 | `MockDidInteropStandardApiTest`（11 例）、`DidInteropSamplesTest`（4 例）、`DidInteropServiceTest`（7 例）、`DidInteropIntegrationTest`（9 例） | ①样例自洽仅单维（验签 vs `expectedReason != SIGNATURE_INVALID`），未全量比对 `expectedResult`/`expectedReason`（**T1**）；②留痕时间未钉（**T2**）；③收口守卫为单向（**T3**）；④磁盘日志未扫（**T4**）；⑤"能力未开放占位"条件已不成立（见 §2.2-②） |
| 界面承载（管理面 + 演示面） | 3.1.11 | `IndexView.spec.ts`（17 例）、`DemoView.spec.ts`（8 例）、`DidSourceGuard.spec.ts`（5 例，源集扫描 + 反向探针） | **F1** 分页越界 / **F2** 留痕空态文案 / **F3** 取消零留痕独立断言 / **F4** 回滚字样反向探针 / **F5** 类型收口 |

### 2.2 盘点结论之"无锚点 / 条件性标准"处置（不造空洞断言）

> 判定口径（§2 缺口的判定沿用"红锚"标准）：补测必须能指出"删掉哪段实现会变红"。以下三条**无法产生红锚**，故**不作补测**，如实登记：

1. **行为 1 验收 4**（"待认证/待审核/已驳回/认证失败的主体 → 不存在有效 DID"）：DID 侧**无该状态面**——DID 记录仅在签发触发后才产生，"是否触发"由 **subject-service 状态机**（ADR-016 衔接契约）决定，承载侧为 `3.1.6`/`3.1.8` 触发链。本卡在 DID 侧补测只能断言"库里没有该行"（恒真、无红锚）→ **登记为触发侧口径**，不补测。
2. **行为 5 验收 4**（"互认能力未开放 → 返回该标准互联功能尚未开放"）：Given 前提为"**协议实现交付前**"，而 `3.1.10` 已交付互认实现（`MockDidInteropStandardApiTest#DID互认域已开放且占位类已删除` 锚定 `status().implemented() == true`、占位类已删）→ **条件已不成立**，制造"未开放"态需改实现或配置（超本卡"只补测、不改行为"边界）→ **不新增**，登记于 §11。
3. **行为 3 验收 5**（"验证通过仅代表身份主张成立，不附带业务授权"）：以出参字段集（`did`/`result`/`reason`/`verifiedAt`）间接锚定 + 规格行为 3 规则 4 为业务边界声明 → **判定现有锚定够用**（T2 加强时间口径后覆盖面更完整），不另造断言。

## 3. 后端补测清单（T1~T8 候选；编码会话按 §2 盘点结果增删定稿）

要求：**全部落既有测试类内**（不新建平行测试世界）；每条含"断言要点 + 删锚验证"。

| # | 缺口来源 | 落点 | 断言要点 | 删锚验证（必红项） |
| --- | --- | --- | --- | --- |
| T1 | 3.1.10 §7.3 第 8 条① | `std-adapter` `MockDidInteropStandardApiTest` / `DidInteropSamplesTest` | **样例自洽遍历**：`did-interop-samples.json` 全量样例的"实现结论/原因 == 样例 `expectedResult`/`expectedReason`" | 改样例期望或改实现判定 → 红 |
| T2 | 3.1.10 §7.3 第 8 条② | `services/did`（互认/验证相关集成或单测） | 时间字段（`verifiedAt`/`signedAt`）**钉 ISO-8601 秒级口径**（格式 + 精度 + 时区口径），不再仅断"非空" | 时间格式改毫秒/非 ISO → 红 |
| T3 | 3.1.10 §7.3 第 8 条③ | `services/did` `architecture/InteropSeamTest` | 守卫扩为"**互认域源集不得引用 did 域具体类型**"（现仅断言依赖互认域接口类型） | 互认域实现类 import did 域具体类 → 红 |
| T4 | 3.1.10 §7.3 第 8 条④ | `services/did` 集成测试 | 留痕/验证的**落盘日志扫描**：`services/did/logs/*.log` 与既有 appender 输出中数据原文 **0 命中** | 在日志中打印原文 → 红 |
| T5 | 3.1.11 S6 | `DidManagementIntegrationTest` | 出参字段白名单由 `list.get(0)` 扩为**对所有元素**（含"无私钥材料/无原文"列级口径） | 出参 DTO 增加材料字段 → 红 |
| T6 | 3.1.11 S7 | `DidManagementIntegrationTest` | 权限用例 **GET 分支补业务码 `1000C0002`** 断言（与 POST 分支口径一致） | 权限拦截改返回其它错误码 → 红 |
| T7 | 3.1.11 S8 | `DidManagementIntegrationTest` | `total` 断言**去隐性耦合**：改为与库表 COUNT 比对或按 `did` 隔离断言（含前提注释） | 分页 total 改为不含过滤条件 → 红 |
| T8 | 3.1.11 S9（后端侧） | `DidIssuanceIntegrationTest` 等 | **并发与幂等**：同主体并发签发 / 并发吊销 → 恰一次生效、其余按幂等或状态门槛返回；留痕条数断言 | 去掉幂等锁或状态门槛 → 红 |

> T8 形态（新增用例 vs 强化既有）由编码会话按盘点结果定；若既有用例已覆盖，改为"断言补强"并如实登记。

### 3.1 定稿（T1~T8 落地形态与删锚验证实绩）

| # | 定稿形态 | 落点（既有测试类内） | 删锚验证（实测必红） |
| --- | --- | --- | --- |
| T1 | **新增用例**（样例清单驱动全量遍历，双字段比对） | `std-adapter` `MockDidInteropStandardApiTest#全部样例的实现结论与样例预期逐字段一致` | 改样例 `expectedResult`（FAIL→PASS）→ **红**（`expected: PASS but was: FAIL`；同轮其余 11 例仍绿 → 佐证新增用例的独立价值） |
| T2 | **断言强化**（新增共享断言 + 落 4 个既有集成用例） | 新增测试辅助 `services/did/…/support/IsoSecondTimestamp.java`；`DidIssuanceIntegrationTest`、`DidManagementIntegrationTest`、`DidInteropIntegrationTest`、`DidDemoSignatureIntegrationTest` | 去掉 `withNano(0)` → **红**（`verifyPassesWithRealSm2Signature`） |
| T3 | **守卫扩展**（单向 → 双向收口） | `services/did` `architecture/InteropSeamTest#互认域实现源集不得依赖did域具体类型`（含反向探针） | 互认域源集加 `import com.ctds.did.domain.DidIdentity;` → **红**（精确命中违规文件） |
| T4 | **新增用例**（落盘日志扫描 + "appender 生效"非空转探针） | `DidIssuanceIntegrationTest#verificationPlaintextNeverLandsInLogFile` | 验证服务打印原文 Base64 → **红** |
| T5 | **断言强化**（白名单与"无材料/无原文"口径由 `list.get(0)` 扩到**全部元素** + 列级公钥值探针） | `DidManagementIntegrationTest#recordsListSupportsPagingAndFilters`（留痕列表同法） | DTO 把 `keyRef` 改回填公钥 hex → **红** |
| T6 | **断言强化**（GET 三分支补业务码 `1000C0002`） | `DidManagementIntegrationTest#managementEndpointsEnforceDidAdminPermission` | 见下方"T6 口径说明" |
| T7 | **断言强化**（total 去硬编码 → 与库表 COUNT 比对 + 前提注释） | `DidManagementIntegrationTest#verificationLogsPaginateAndExposeNoRawPayload` | 计数忽略 did 过滤 → **红** |
| T8 | **新增用例**（集成侧并发吊销，真实 MySQL 乐观门槛，8 线程） | `DidIssuanceIntegrationTest#concurrentRevokeAppliesExactlyOnceWithSingleLog` | 去掉 `AND status = ?` → **红**（"恰一次生效"实测 8 次） |

> **删锚验证执行口径（如实登记）**：T1 单独一轮；T3 单独一轮（只跑 did 模块守卫——守卫为源文本口径，无需重编 `std-adapter`，避免"加非法 import 导致编译失败"干扰）；T2+T4 同轮注入（did 服务两处，逐用例失败归属清晰）；T5+T7 同轮注入（管理面两处）；T8 单独一轮；F1~F5 同轮注入（前端五处）。**每轮注入后均已完全还原**（`git status` 复核仅含预期变更，见 §11 第 5 条）。
>
> **T6 口径说明（未做破坏性验证的如实登记）**：`1000C0002` 由 `common-auth` 统一产出，GET 分支新增断言与既有 POST 分支断言**同源同码**；本卡对 T6 采"同源断言复用"，不重复做破坏性验证（改动 `common-auth` 属跨模块，超本卡边界）。

## 4. 前端补测清单（F1~F5）

| # | 缺口来源 | 落点 | 断言要点 | 删锚验证 |
| --- | --- | --- | --- | --- |
| F1 | 3.1.11 S9 | `views/did/IndexView.spec.ts` | **分页越界**：页码超范围时请求参数与页面表现符合既有分页契约 | 改分页边界处理 → 红 |
| F2 | 3.1.11 S9 | `views/did/DemoView.spec.ts` | **验证留痕空态文案**断言（含"无原文"口径的界面表述） | 删空态分支 → 红 |
| F3 | 3.1.11 S4 | `views/did/IndexView.spec.ts` | 二次确认**取消 = 零留痕**：除"零请求"外**独立断言**留痕列表未新增 | 取消分支误发请求/插留痕 → 红 |
| F4 | 3.1.11 S5 | `views/did/DidSourceGuard.spec.ts` | `restore`/`unrevoke`（无恢复入口）模式补**专用反向探针**，四条扫描各自可证伪 | 移除扫描规则或植入恢复字样 → 红 |
| F5 | 3.1.11 S2 + S10 | `frontend/src/api/did.ts`、`api/subject.ts`、`constants/did.ts` | **类型收口**（行为零变化）：`PageData<T>` 重复声明合并到共享模块；`RecordStatusFilterValue` 与 `DidRecordStatus` 三值集合合并为一处 | 收口后前端全量用例仍全绿；故意改一值 → 类型/用例红 |

> `3.1.11` **S1**（守卫不扫中文"私钥"属设计使然）与 **S3**（页面私有错误分发范式）以**文档补注**闭环（本文件 §11 + 任务卡交付说明），**不改代码**。

### 4.1 定稿（F1~F5 落地形态与删锚验证实绩）

| # | 定稿形态 | 落点 | 删锚验证（实测必红） |
| --- | --- | --- | --- |
| F1 | **新增用例**（末页下一页禁用 → 越界不产生请求） | `views/did/IndexView.spec.ts` | 去掉 `:total="total"` → **红**（F1 与既有分页透传用例同红） |
| F2 | **新增用例**（留痕空态文案 + "无原文"说明） | `views/did/DemoView.spec.ts` | 去掉 `empty-text` → **红** |
| F3 | **新增用例**（清单未刷新 + 详情留痕仍为服务端原值，**独立于"零请求"**） | `views/did/IndexView.spec.ts` | 取消分支改为触发刷新 → **红** |
| F4 | **新增用例**（三条回滚字样口径各自反向探针 + 合规样本不误判） | `views/did/DidSourceGuard.spec.ts` | `hits()` 置 0 → **红**（4 条守卫用例同红） |
| F5 | **行为零变化重构** + 新增运行时不变量用例 | 新增 `api/types.ts`（`PageData` 唯一声明）、`api/did.ts` / `api/subject.ts`（改为 import + re-export，导出面不变）、`constants/did.ts`（`RecordStatusFilterValue = '' \| DidRecordStatus`）、`IndexView.spec.ts` 新增一致性用例 | 常量加第 4 个筛选取值 → **红**；收口后前端全量 **118 例仍全绿**（行为零变化实证） |

> F5 为**行为零变化**的源码重构（`vue-tsc` 类型面收口）：收口前 `PageData<T>` 在 `api/did.ts:28` 与 `api/subject.ts:18` 各声明一份、三值集合在 `api/did.ts:11`（`DidRecordStatus`）与 `constants/did.ts:21`（`RecordStatusFilterValue`）各写一遍；收口后为**单一声明 + 类型引用**，并以运行时不变量用例兜住"两侧取值集漂移"。

## 5. 达标的补测边界（防"凑数字"）

1. 补测必须由"**行为映射缺口 / 已登记欠账 / 红锚验证**"驱动，**不得以拉高覆盖率数字为目的**；
2. `did` 行覆盖 <80% 时按缺口性质分流：① 属本卡行为映射缺口的 → 本卡补测；② 属**存量组件**（如 `common-crypto`/`common-auth`）的 → **登记移交 2.2.8**，不混入本卡（沿 3.1.6 Q1 先例）；
3. 变异杀除率 <60% 时**逐个存活突变**判定："冗余谓词 / 不可达分支"类存活 → 登记观察项（沿 3.1.6 T8 先例）；"真缺口"类 → 本卡补测。

## 6. 联调方案（按 Q1 / Q5 裁决口径）

**环境编排**（复用 3.1.11 走查就绪口径）：三容器（`sc-mysql`/`sc-minio`/`sc-redis`）+ 三服务 8081/8082/8080（回环）+ 前端 5173（仅本机）；did 带 `CTDS_DID_DEMO_SIGNATURE_ENABLED=true`（**仅演示期**）；**KMS 重启必须沿用同一把演示根密钥**。

**联调链路清单（接口级端到端，逐步留痕）**：

1. **主体链路**：注册 → 认证 → 审核通过 → **DID 自动签发**（subject→did 回环触发）；
2. **身份三态解析**：`ACTIVE` / `REVOKED` / 未登记（业务答复而非报错）；
3. **验证三查**：`PASS` / 篡改 `SIGNATURE_INVALID` / 已吊销 FAIL / 主体绑定失败 `SUBJECT_BINDING_FAILED`；
4. **写操作**：吊销（理由必填，`1005C0002` 兜底）、重签（**旧行保留、旧标识永不复用**）、重试（"待签发"态）；
5. **跨空间互认**：有效来访 PASS / 对端已吊销 FAIL / 签名篡改 FAIL / 出向双向口径 / 能力未开放占位；
6. **下游可消费性证明**：以"第三方视角"用 `GET /api/v1/did/{did}` + `POST /api/v1/did/{did}/verifications` 完成一次完整"**取 DID → 验身份**"闭环——这就是"下游（连接器注册）绑用 DID"的**等价接口证据**。

**"与连接器注册联调"的处置**：按 Q1 裁决执行。

- **Q1=A（建议）**：第 6 条即本卡联调交付；连接器注册的**真联调顺延**至 `3.6.3` 交付后，由编排师指示**走变更流程**同步 C-1.2 规格 §6.1 与 WBS 行 244 的表述（消除规格自张力）；
- **Q1=B**：在 `std-adapter` 增"模拟连接器注册"并联调——**须先走规格变更流程**把连接器行为固化为条款，本卡默认不执行。

走查结果写入开发日志"联调记录"节；临时脚本用毕即删（沿 3.1.6 / 3.1.11 先例）。

## 7. 遗留项吸收清单（逐条处置；按 Q3 裁决）

| 来源条目 | 处置 | 落点 |
| --- | --- | --- |
| 3.1.11 **S9**（分页越界 / 留痕空态 / 并发幂等） | **本卡做** | T8 + F1 + F2 |
| 3.1.11 **S4**（取消零留痕独立断言） | **本卡做** | F3 |
| 3.1.11 **S5**（恢复模式反向探针） | **本卡做** | F4 |
| 3.1.11 **S6 / S7 / S8**（出参全元素 / 业务码 / total 口径） | **本卡做** | T5 / T6 / T7 |
| 3.1.11 **S2 + S10**（前端类型收口） | **本卡做**（行为零变化） | F5 |
| 3.1.11 **S1**（守卫中文"私钥"边界） | **文档补注闭环**（不改测试） | 本文件 §11 + 任务卡 |
| 3.1.11 **S3**（页面私有错误分发范式） | **文档补注闭环** | 本文件 §11 + 任务卡 |
| 3.1.10 §7.3 **第 8 条**（互认测试加固 4 项） | **本卡做** | T1~T4 |
| 3.1.10 §7.3 **第 7 条**（留痕表收敛） | **只做"收敛评估结论"**，不做表结构收敛（**不动库表**） | 本文件 §11 + 交付说明 |
| 3.1.10 §7.3 其余 6 条（装配条件 / 传递依赖 / 诚实边界等） | **登记顺延**（文档类，非本卡目标） | 台账 + 交付说明 |
| `vue-tsc` 4 处**既有**类型错误（C-1.1 域） | **不并入**（按 Q4 建议 B） | 任务卡边界声明 |

## 8. 边界值与异常行为

| 编号 | 场景 | 期望 |
| --- | --- | --- |
| E1 | Docker 不在线 | 集成用例跳过 → **达标数字不予采信**；如实登记并建议在有 Docker 环境复测（ADR-010 第 4 条） |
| E2 | PIT 插件需联网拉取失败 | 退化为"覆盖率达标 + 变异登记顺延"，**如实留痕，不伪造数字** |
| E3 | 变异执行超时（>10 分钟） | 缩小目标类范围（§1.2 必测清单），未测部分如实登记 |
| E4 | 工具版本与本地仓库不一致 | 以 Maven Central 真实坐标为唯一来源；**不引入仓库依赖文件变更** |
| E5 | 补测触及既有断言 | **只允许加强，禁止弱化**；弱化既有断言 = 打回项 |
| E6 | 发现真缺陷（非测试问题） | **停止并上报**（红线 4），不擅自修复绕过；缺陷修复另立卡 |

## 9. 交付门禁与依赖声明

- **门禁**：`mvn -B -ntp -pl services/did -am clean test checkstyle:check`（Docker 在线、did 全部用例真实执行）；前端 `npm test` + `npm run lint`；全仓 `run-gates.ps1` **GREEN**；**用例数只增不减**（基线：后端 580 / 前端 113）；
- **依赖清单：零新增**（jacoco / pitest 走**命令行插件调用**，不写入 pom 与 `docs/dependencies.md`；前端零新增 npm 包）；
- **数据库：零新增迁移**（用例内造数仅存在于一次性容器）；
- **门禁配置：零改动**（`gates-config.json`、`run-gates.ps1` 均不动）；
- **规格外实现声明**：无（T/F 清单全部由行为映射缺口与已登记欠账驱动）。

## 10. 实施前置检查项（编码会话第一步，逐项实测留痕）

| # | 检查项 | 实测结果（2026-09-25 15:5x~16:0x） |
| --- | --- | --- |
| 1 | 冷启动读序 + 分支/工作树核验 | ✅ 读序 `AGENTS.md` → 最新日志 `-1552` → `-1534`/`-1527` → 任务卡 → 本设计与 lofi；分支 = `feat/C-1.2-测试补全与集成`（HEAD `c11903c`），工作树干净 |
| 2 | 环境核验 | ✅ `docker ps`：`sc-mysql` / `sc-minio` / `sc-redis` 三容器 Up 6h；`/actuator/health` = **200 / 200 / 200**（subject 8080 / kms 8081 / did 8082）；前端 `http://localhost:5173/` = **200** |
| 3 | 基线用例数复核 | ✅ 后端 `mvn -B -ntp test` 全仓 11 模块 **580 例 / 0 失败**（与台账口径一致）；前端 `npm test` = **113 例 / 14 文件 / 0 失败**（一致） |
| 4 | 覆盖率基线测量（§1.1 命令） | ✅ 已完成并回填 §1.3：`did` 行覆盖 **96.05%**、分支 88.27%（缺 24 行 / 7 类）；`subject-service` 96.22% / `kms` 94.96% / `std-adapter` 93.33% / `common/*` 79.45%~100% |
| 5 | 变异基线试跑（先单类评估耗时） | ✅ 单类 `DidIssuanceService`：**41 突变 / 30 检测 = 73.17%**（无覆盖突变 2），单类耗时约 **6 秒** → §1.2 范围确定为**全量 6 个必测类**；`mvn` 直调用法因缺 JUnit5 插件不可用（偏差与处置见 §11 第 1 条） |
| 6 | §2 盘点回填 | ✅ 已回填 §2.1「现有锚点 / 缺口」两列（覆盖 16 个 `did` 测试类 + 6 个 `std-adapter` 测试类 + 3 个前端 spec）与 §2.2 无锚点项处置；T/F 定稿见 §3.1 / §4.1 |
| 7 | `git log` 核验分支基线 | ✅ 分支自 `main` = **`593bc26`** 起（含 3.1.11 合并 `ad6a447`）→ `edfbd6e` 立卡 → `acb668d` → `fad146` → `efa25a0` → `4745730` → `7cd7963` → `c11903c` = 当前 HEAD；`main` 未动 |

## 11. 变更登记（实现期补记，如实留痕）

1. **工具链偏差与处置（PIT × JUnit 5）**：按 §1.2 的 `mvn -pl services/did org.pitest:pitest-maven:mutationCoverage` 直调用法**不可用**——`pitest-maven` 1.30.0 无"命令行注入插件依赖"参数（`help -Ddetail` 实测仅有 `targetClasses`/`targetTests` 等，无 `testPlugin`/插件依赖入口），而 JUnit 5 支持必须经 pom 插件依赖引入 `pitest-junit5-plugin`（**本卡禁止改 pom/依赖**）。直调实测结果为 `JUnit 5 is on the classpath but the pitest junit 5 plugin is not installed` → `Ran 0 tests`、41 突变全 `NO_COVERAGE`（**该组数字不可采信，作废**）。**处置 = 改用纯临时 PIT CLI**：`target/precheck/pit-bundle/` 内临时放置 `pitest-command-line` / `pitest-junit5-plugin` / `junit-platform-launcher` / `commons-text` / `commons-lang3`（**不入库、不改 pom / `dependencies.md` / `gates-config.json`**，工具形态与 Q2=A"命令行临时注入"同格），复跑有效（`Ran 97 tests`）。**接入建议（交 2.2.8 / DB-06）**：正式接入时须在 pom 增加 `pitest-junit5-plugin` 插件依赖，并明确变异口径（是否含集成用例、`DidManagementQueryService` 的覆盖方式）。
2. **构建期环境事故（已恢复，如实登记）**：为做覆盖率测量执行的 `mvn -pl services/did -am install -DskipTests` 在 `spring-boot:repackage` 阶段失败（`Unable to rename … .jar → .jar.original`）——**运行中的 did 服务（`java -jar`）持有目标 jar 文件锁**；该次构建已把新 jar 写到同一路径，**导致运行中进程后续 lazy 加载的类抛 `NoClassDefFoundError`**（联调首轮互认接口出现 `1000S9999`，日志栈 `NoClassDefFoundError: DidInteropService$1` / `InteropSampleView`）→ 判定为**环境事故而非产品缺陷**；处置 = 停 did 服务 → 重建 jar → 按既有演示口径重启（本地库/KMS/主体基址 + `CTDS_DID_DEMO_SIGNATURE_ENABLED=true`，**未动 KMS、演示根密钥沿用**），恢复后互认接口全部正常。**教训**：测量用构建须与运行服务隔离（停机或换 `-Dspring-boot.repackage.skip=true` 等价手段），否则会破坏运行环境。
3. **联调走查记录（真实环境，2026-09-25 16:2x~16:4x；演示数据保留至验收后清理）**：
   - **链路 1 主体链路**：注册 `S20260925000005`（GOV）→ 政务 CA 提交（预置有效证书 `A3`，`GovCaVerification.passed=true`）→ `PENDING_REVIEW` → 审核通过 `ADMITTED` → **DID 自动签发** `did:ctds:S20260925000005.1`（ACTIVE + keyRef 齐备）✅（注：企业类型走政务 CA 会被 1004B0007 拒，属主体类型与流程匹配校验，**符合预期**，非缺陷）
   - **链路 2 解析三态**：`ACTIVE`（公开要素齐全、无 keyRef/私钥字段）✅ / `REVOKED`（照常返回文档 + 状态）✅ / 未登记 → `1005B0003 该 DID 未登记` ✅
   - **链路 3 验证三查**：真实 SM2 签名 `PASS` ✅ / 篡改原文 `FAIL + SIGNATURE_INVALID` ✅ / 已吊销 DID 的**真实签名** `FAIL + REVOKED` ✅（实时复核，证明重签后新记录可用）；**主体绑定失败（`SUBJECT_BINDING_FAILED`）分支**：真实环境无"非 ADMITTED 主体却有 DID"的数据，**不造脏数据**，该分支由自动化用例（`DidVerificationServiceTest` + `SubjectStatusClientFailureIntegrationTest`）承载 ✅（登记口径）
   - **链路 4 写操作**：吊销空白理由 → `1005C0002 吊销理由必填` ✅；吊销生效即时（解析 `REVOKED` + 验证 `REVOKED`，双向口径一致）✅；**重签**（`S20260925000002`）→ 新 DID `.2` + 新 keyRef + ACTIVE，**旧行保留**（记录 2 行：seq2 ACTIVE / seq1 REVOKED），旧 DID 操作留痕 ISSUE+REVOKE **五要素齐备** ✅；**重试（待签发态）**：真实环境无"待签发"记录（需 KMS 不可用才会产生），**不制造该态**，由自动化用例（失败→重试）承载 ✅（登记口径）
   - **链路 5 跨空间互认**：`/samples` 返回 3 条预置样例；来访 `E1-VALID→PASS`、`E1-PEER-REVOKED→FAIL+REVOKED`、`E1-TAMPERED→FAIL+SIGNATURE_INVALID` ✅；出向双向：有效 DID→`PASS`、已吊销 DID→`FAIL+REVOKED`（与本空间状态一致）✅；**"能力未开放占位"**：条件已不成立（3.1.10 已交付，见 §2.2-②），**不适用**如实登记
   - **链路 6 下游可消费性证明（第三方视角）**：`GET /api/v1/did/{did}`（取公开文档）→ `POST …/demo-signatures`（真签）→ `POST …/verifications` → `PASS`，并在**重签后的新 DID** 上复跑一遍闭环 ✅——即"下游（连接器注册）绑用 DID"的**等价接口证据**
4. **盘点结论与清单定稿**：见 §2.1 / §2.2（无锚点项处置）/ §3.1（T 定稿）/ §4.1（F 定稿）；**未新增规格外功能**。
5. **删锚验证与还原核对**：本轮共做 10 处突变注入（T1 / T3 / T2+T4 / T5+T7 / T8 / F1~F5 同轮），**全部实测必红、全部完全还原**；`git status` 复核工作区仅含预期变更（**零主源集业务逻辑变更残留**）。
6. **交付边界自检**：**零新增依赖**（PIT 工具 jar 仅落忽略目录，不入库）/ **零门禁配置改动**（`gates-config.json`、`run-gates.ps1` 未动）/ **零新增错误码、状态枚举、库表、迁移**；**用例数只增不减**（后端 did 108→**111**、`std-adapter` 34→**35**、前端 113→**118**，全部 0 失败）。
7. **未达标项**：**无**（`did` 行覆盖 96.22% ≥80%；变异杀除率 68.18% ≥60%）。`DidManagementQueryService` 变异无覆盖为**口径限制**（见 §1.4），非达标失败项。
8. **遗留项处置落点（Q3=A）**：
   - `3.1.11` **S1**（守卫不扫中文"私钥"）：**设计使然**——页面需展示"仅公开要素，界面不展示任何私钥"等提示语，扫中文会误判；判定口径 = 英文标识符（`privateKey`/`PrivateKey`）+ F4 同法反向探针 → **文档补注闭环，不改代码**；
   - `3.1.11` **S3**（页面私有错误分发范式）：各 DID 页面各自 `handleOperationError` / `ElMessage` 分发，属页面级范式，**未沉淀 `common`** → **建议登记**（交 2.2.8/后续前端小卡统一），本卡不改；
   - `3.1.10` §7.3 **第 7 条（留痕表收敛评估结论）**：三张留痕表（`did_operation_log` 五要素 / `did_verification_log` 三要素且"验证通道不得变成数据存储通道" / `did_interop_log` 含对端空间标识）**字段语义不同、约束不同** → **结论：不收敛**；若未来需要统一查询，走查询层聚合（视图/查询服务），**不动表结构**；
   - `3.1.10` §7.3 其余 6 条：**登记顺延**（文档类，非本卡目标）。

## 确认记录（人签署；未签署 = 未确认 = 禁止进入编码）

| 确认人（PO/编排师） | 结论 | 时间 |
| --- | --- | --- |
| 编排师 | **确认进入编码**——Q1 采 A / Q2 采 A / Q3 采 A（按推荐）/ Q4 采 B（按推荐）/ Q5 采 A；**D1 体量 = 不拆分**（补裁决，本次验收按整体受理） | 2026-09-25 15:2x（Q1~Q5）/ 15:3x（D1） |
