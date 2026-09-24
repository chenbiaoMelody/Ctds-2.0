# WBS-3.1.9 任务卡：DID 解析与验证服务（2026-09-22）

| 项 | 内容 |
| --- | --- |
| 来源 | 台账"下一包"行（3.1.9）+ 编排师指示"**确认立卡**"（2026-09-22，承接 3.1.8 合并收口 `4dc0444`） |
| WBS 依据 | `docs/C-TDS项目总体实施计划与WBS.md` 行 241：**3.1.9 DID 解析与验证服务——DID 解析接口、签名验证、吊销状态检查**，依赖 3.1.8（已完成合并），预算 **1 会话** |
| 规格依据 | `docs/specs/C-1.2-分布式数字身份DID.md`（V1.0 已确认）：**行为 2 全部 4 条规则 + 4 条验收标准（解析）**、**行为 3 全部 4 条规则 + 5 条验收标准（验证：签名 + 状态 + 绑定三查）**、行为 4 验收标准 4（吊销后解析/验证双向口径，吊销写操作已由 3.1.8 交付） |
| 上游契约 | **ADR-017 §2.3**（DID 标识格式 `did:ctds:<主体编号>.<序号>`、文档公开要素、状态集锁定两值）、**§2.4**（接口契约——**解析/验证归本包**；吊销写操作归 3.1.8，本包只读）、**§2.9**（后续包约束：不得新建平行错误码段/状态枚举/库表；解析/验证走 DID 文档公开要素 + 注册表当前状态）；ADR-006（国密运算唯一入口 `common-crypto`，**Sm2Service.verify 可复用**）；ADR-005 §3.5（错误码 1005 段顺延占用制度）；ADR-015/016（KMS 私钥不出 KMS；演示期回环安全边界与诚实边界登记先例）；ADR-009/010（迁移与集成测试规范）；ADR-007（幂等——解析/验证为只读，不涉幂等） |
| 本文件性质 | **实施包（非界面类）**；预算 1 会话（≤1 天）→ 按章程 2.6.3 **两级设计一并提交、一次确认**：本会话交付 `-lofi.md` + `-hifi.md`（hifi 按 lofi 建议口径定稿）→ PO 一次确认（= 编码契约生效）→ 测试先行 → 实现 |
| 交付物 | ① 设计：`docs/designs/WBS-3.1.9-lofi.md` + `docs/designs/WBS-3.1.9-hifi.md`（**本会话同批落盘**，一次确认）；② 代码：**扩展 `services/did`**（解析 + 验证两端点 + 验证留痕表；拟，待 Q1 裁决）+ `services/subject-service` 服务间只读衔接（主体绑定核验用，拟，待 Q2 裁决）；③ 契约：**ADR-017 补记**（解析/验证接口 + 新错误码 + 验证留痕表）；④ 迁移脚本 `V2__create_verification_log.sql`（库 `ctds_did`）；⑤ 测试（单测 + Testcontainers 集成 + 跨服务真实链路）；⑥ 业务可读交付说明（含剧本 S2/S4 解析验证步骤的注入方式） |
| 通用出口 | lofi + hifi 同批确认（Q1~Q6 方向 + 编码契约，一次签署）→ 测试先行（RED）→ 实现（GREEN）→ 自检单（章程附录 B1）→ 4 视角评审（循环 ≤3）→ 合并入 main → 台账 + 会话日志落盘 |
| 分支 | `feat/C-1.2-DID解析与验证服务`（AGENTS §6 `feat/C-x.y-短描述`；沿用 C-1.2 规格分支命名先例） |
| 纪律声明 | 只实现规格行为 2/3（及行为 4 验收标准 4 的解析/验证侧口径）与已确认设计，禁止规格外实现；复用既有组件（鉴权/错误码/国密/迁移/集成测试基座）；**零新增依赖**（服务间 HTTP 沿 `KmsKeyProvider`/`DidIssuanceClient` 先例）；**不新建平行错误码段/状态枚举/库表**（ADR-017 §2.9；验证留痕表为规格行为 3 规则 3 明示要求，登记于 ADR-017 补记）；发现规格缺口一律走变更流程，不在本包私自补需求 |

---

## 执行记录

| 项 | 内容 |
| --- | --- |
| 状态 | ✅ **两级设计门禁已过**（lofi + hifi **一次确认**，2026-09-22 编排师"确认" = Q1~Q6 采建议口径 + 编码契约生效）；**编码就绪**——必读 `docs/designs/WBS-3.1.9-hifi.md`（编码契约）+ §10 实施前置检查项三项 |
| 冷启动锚点 | 承接会话日志 `-2104`（WBS-3.1.8 合并收口）。核验：main = `2bcfdcb` 且与 origin 同步；工作树干净；演示环境三服务（8080/8081/8082 回环）与 Docker 三容器运行中；3.1.8 已合并闭环（`4dc0444`） |
| 需求锚点核对 | V1.0 PRD（C-1.2 DID 定义）；WBS 行 241（本包交付物）、行 242/244（3.1.10 互认 / 3.1.12 联调依赖本包，本包能力边界须支撑依赖链）；规格行为 2 规则 1~4 与验收标准 1~4、行为 3 规则 1~4 与验收标准 1~5；ADR-017 §2.3/§2.4/§2.9；剧本 S2（解析）/S4（验证）承载步骤 |
| 范围与边界 | 做什么 W1~W7、不做什么 N1~N7，逐条见 lofi §1 / §2（全部由规格行为 2/3 与 §4 非目标派生） |
| 设计门禁 | **低保真 + 高保真同批**（≤1 天包，章程 2.6.3）：`-lofi.md` 定方向（Q1~Q6 含建议倾向）、`-hifi.md` 按建议口径定稿（行为清单逐条对应验收标准编号 + 接口契约表 + 留痕表结构 + 边界值与异常行为）；**一次确认 = 两级同时生效（编码契约）**，若对 Q 点有不同意见则打回并按新口径修订 hifi |
| 实施前置检查项 | ① **Sm2Service.verify 与 KMS 签名 round-trip 实测**（KMS `/signatures` 出 DER 签名 → 本包用文档公钥验签通过；公钥格式 130 hex `04‖X‖Y` 与 verify 入参匹配性）；② **did→subject 服务身份头角色映射确认**（`GET /api/v1/subject/registrations/{subjectNo}` 需 `subject.read`；Q2 裁决后按口径配置） |
| 决策点 | lofi §4 **Q1~Q6 待编排师确认**（服务落位 / 绑定核验形态与不可达表征 / 验证接口鉴权 / 解析接口鉴权 / 解析留痕口径 / 失败原因枚举粒度），均附建议倾向与业务影响 |
| 验证口径 | ① 门禁全量复跑留痕（报告路径见会话日志）；② **行为 2/3 共 9 条验收标准 → 测试用例 → 剧本步骤**映射表（自检单第 2 项）；③ 跨服务真实链路（did→subject 绑定核验、verify 真实签名）在接口级演示留痕；④ 验证留痕三要素 + 无业务数据原文锚定测试 |
| 评审结论 | 待交付后 4 视角评审（规格与设计符合性 / 安全供应链 / 一致性重复 / 测试质量），循环 ≤3 |

---

## 编码会话执行记录（2026-09-22，承接 `-2125` 日志）

### 实施前置检查（hifi §10，三项全部完成）

| 项 | 结论 |
| --- | --- |
| ① 验签 round-trip 实测 | **通过**：真实 SM2 密钥对 + 真实签名 → 三查全过（集成测试 `verifyPassesWithRealSm2Signature`）；端到端再用 KMS `/signatures` 代签 → did 验证 `PASS`（编码口径对齐，不凭记忆） |
| ② did→subject 衔接实测 | **通过（含实施修正）**：原定复用 `GET /registrations/{subjectNo}` 被**对象级归属断言**拦截（`did-internal` 仅持 `subject.read`，400/1000C0003）→ 改**新增内部只读端点** `GET /api/v1/subject/internal/subjects/{subjectNo}/admission`（仅 `{subjectNo,status}` 两字段；不落归属断言；门槛 `subject.read`）→ 实测：did-internal 200（仅两字段）/ 不带头 401 / 无效角色 403 / 不存在 1000C0003。修正留痕：`WBS-3.1.9-hifi.md §5 实施修正` + `ADR-017 §2.9`。**不采纳**给 `did-internal` 加 `subject.review`（会误授审批主体能力） |
| ③ 构件重建与演示环境 | **完成**：did/subject 重打包；三服务重启（did 注入 `CTDS_DID_KMS_BASEURL` + `CTDS_DID_SUBJECT_BASEURL`）；netstat 复验 8080/8081/8082 均 127.0.0.1 回环 |

### 交付物清单（代码）

| 对象 | 内容 |
| --- | --- |
| `services/did` domain | `VerificationLog` / `VerificationOutcome` / `VerificationReason` / `SubjectAdmission` / `SignatureVerifier`（端口）/ `SubjectStatusPort`（端口）+ `DidErrorCodes` 三码 + `DidRepository.insertVerificationLog`（**评审修复 2026-09-23**：原列的死类 `DidResolution` 全仓无引用——解析结果用 `DidResolutionService.ResolutionResult`，已删除） |
| `services/did` application | `DidResolutionService`（B1~B4）/ `DidVerificationService`（B5~B9/B12；三查逐一判定 + UNAVAILABLE 独立结论） |
| `services/did` infrastructure | `Sm2SignatureVerifier`（common-crypto 唯一入口）/ `SubjectStatusHttpClient`（JDK HttpClient，did-internal 角色）/ `DidJdbcRepository` 留痕落库 |
| `services/did` interfaces | `DidResolutionController`（GET `/{did}`）/ `DidVerificationController`（POST `/{did}/verifications`）+ `ResolutionView`/`VerificationView` |
| `services/did` 迁移 | `V2__create_verification_log.sql`（`did_verification_log`） |
| `services/subject-service` | `SubjectRegistrationService.admission`（内部只读，无归属断言）+ `InternalAdmissionController`（`/internal/subjects/{subjectNo}/admission`）+ 角色映射 `did-internal: subject.read` |
| `services/did` pom | +`common-crypto`（验签唯一入口，内部模块零第三方新增） |
| 契约 | **ADR-017 §2.9 补记**（解析/验证接口 + 1005 段顺延三码 + 验证留痕表 + 绑定核验内部端点 + 无鉴权面诚实边界）；hifi §5 实施修正 |

### 自检单（章程附录 B1）

1. **两级设计门禁**：lofi + hifi **一次确认**（2026-09-22"确认"= Q1~Q6 采建议口径 + 编码契约生效）；实现与 hifi 逐条一致（B1~B12 全部落地）；**唯一偏离 = hifi §5 实施修正**（前置检查②实测发现归属断言缺口 → 内部端点方案），已留痕于 hifi §5 + ADR-017 §2.9，待评审核查。
2. **规格 → 设计 → 测试 → 剧本 映射表**：见下表。
3. **复用声明（含检索过程）与规格外实现声明**：
   - 复用：`common-crypto.Sm2Service.verify`（**验签唯一入口**，零自研密码学）；`common-auth` RBAC（`@RequirePermission("subject.read")` + `did-internal` 角色映射）；`common-errorcode`（1005 段顺延）；`JdbcClient`/Flyway（`V2` 迁移）；Testcontainers 基座（ADR-010）；JDK `HttpClient`（沿 `KmsKeyProvider`/`DidIssuanceClient` 先例）。
   - 检索过程：编码前检索 `common/`（crypto/auth/errorcode）与 `services/did`/`subject-service` 既有实现，确认验签能力（`Sm2Service.verify:92`）与主体查询形态；实现期发现既有查询端点归属断言（`OwnershipGuard`）不可复用 → 走实施修正。
   - **规格外实现声明：空**（无规格外功能、无未批准依赖、无演示专用代码路径）。**设计内新增（非规格外）且已登记**：验证结论第三值 `UNAVAILABLE`（规格未覆盖的核验不可用边界，hifi §1 B9 定形）、内部只读端点（hifi §5 实施修正）。
4. **本地全部检查的命令与结果摘要**：
   - `mvn -B -ntp compile`（全仓）：PASS
   - `mvn -B -ntp -pl services/did test`：**61 通过**（含新增 33：解析单元 4 + 验证单元 9 + 解析/验证集成 11 + 客户端桩测 8 + 跨服务不可达 1），0 失败 0 错误 0 跳过
   - `mvn -B -ntp -pl services/subject-service test`：**141 通过**（含内部端点集成 3，回归无破坏），0 失败 0 错误 0 跳过
   - `mvn -B -ntp -pl services/did,services/subject-service checkstyle:check`：0 违规
   - **did 核心模块行覆盖率实测 94.0%**（344/366 ≥80% 达标；瞬时 jacoco 0.8.12，未引入项目依赖；自动化覆盖率门禁仍 PENDING-JACOCO = DB-06 口径）
   - `powershell -NoProfile -ExecutionPolicy Bypass -File scripts/gates/run-gates.ps1`：**GREEN**（复跑 `gate-report-20260923-225313.md`，RunLabel `20260923-224326-2508`，`PASS=10 FAIL=0 SKIP=1 ERROR=0`，`unitTest` PASS）
   - **门禁 unitTest 超时合规处置（重要留痕）**：首跑红灯 = `mvn test timed out after 600s`（**非测试失败**）；实测全仓 `mvn test` 耗时 **595s（BUILD SUCCESS）**——测试规模增长已顶到门禁硬编码上限（`run-gates.ps1:298`，**按红线 3 不得擅改**）。**本包合规处置 = 压缩本包新增测试的容器启动开销（工程侧，不改门禁、不碰他人模块）**：① did 的解析/验证集成用例（11 例）并入既有 `DidIssuanceIntegrationTest` 共享容器（3 类→2 类，省 ~25s）；② subject 内部端点用例（3 例）并入既有权 `DidIssuanceTriggerIntegrationTest`（省 ~22s）。用例总数不变（did 61 / subject 141）、断言不变，仅测试类布局调整（类注释已注明理由）。**结构性矛盾如实登记 → 台账 DB-25**（集成测试容器共享化改造建议独立小卡）。
   - **评审修复后复测（2026-09-23，4 视角评审必修项落地）**：did **66 通过**（61 + 5：解析文档损坏单元/集成各 1、data 超上限单元 1、主体服务非 200 反向锚定 1、解析侧敏感扫描集成 1）、subject **141 通过**（内部端点权限收敛断言并入既有用例，**用例数不变**）、`checkstyle:check` 两模块 **0 违规**、did 行覆盖率 **94.8%**（347/366，在树 jacoco 0.8.12 报告）；门禁全量复跑结果见文末"4 视角评审与修复"节。
5. **高风险点自查**：
   - **并发**：解析/验证为只读判定 + 单条留痕插入（无共享状态、无竞争写）；留痕表无唯一键（每次验证独立记录，符合"每次验证留痕"设计）。
   - **事务**：验证三查无状态写；留痕 `insertVerificationLog` 单语句，无跨表事务需求。
   - **加解密**：验签经 `common-crypto` 唯一入口（零自研）；公钥为公开要素（文档已含）；无任何密钥材料落库/出站/入日志；留痕表结构上不含业务数据原文（锚定测试含反向探针）。
   - **性能**：验证含一次服务间 HTTP（连接 1s / 读取 3s）；验证为低频动作，非性能敏感路径，未跑基准脚本。
6. **验收剧本更新建议（业务语言）**：**剧本正文不改**。S2（解析）/S4（验证）由本包承载（接口级）：解析命令 `GET /api/v1/did/{did}`（有效/已吊销/未登记三态）；验证演示 = 经 KMS 代签（`POST /api/v1/key-pairs/{keyRef}/signatures`）→ 提交验证（PASS）→ 篡改数据（FAIL/SIGNATURE_INVALID）→ 已吊销 DID（FAIL/REVOKED）；命令原文随交付说明提供（统一 ASCII 数据样例，免中文编码坑）。
7. **业务可读交付说明**：见下节。

### 验收标准 → 测试用例 → 剧本步骤 映射表

| 规格验收标准（C-1.2 行为 2/3/4-4） | 已确认设计（hifi） | 测试用例 | 剧本步骤 |
| --- | --- | --- | --- |
| 行为2-1 有效 DID 解析：文档 + "有效" + 无 L4 | B1/B4 | `DidResolutionServiceTest.resolveActiveReturnsDocumentAndStatus`（字段集精确断言）、集成 `resolveActiveReturnsDocumentAndStatus` | S2 |
| 行为2-2 已吊销照常返回文档 + "已吊销" | B2 | `DidResolutionServiceTest.resolveRevokedStillReturnsDocumentWithRevokedStatus`、集成同名用例 | S2 |
| 行为2-3 未登记 → 明确答复 | B3 | `DidResolutionServiceTest/集成 resolveUnknownDidReturnsNotRegisteredAnswer`（1005B0003） | S2 |
| 行为2-4 解析全文无敏感明文 | B4 | 解析侧 `resolutionResponseContainsNoSensitivePlaintext`（响应全文敏感样式扫描 0 命中 + 身份证/手机号/私钥三类植入样本反向探针）+ 字段集精确断言（单元/集成 `resolveActiveReturnsDocumentAndStatus`） | S2 |
| 行为3-1 三查全过 → "验证通过" + 留痕 | B5 | `DidVerificationServiceTest.verifyPassesWhenAllThreeChecksPass`、集成 `verifyPassesWithRealSm2Signature`（**真实 SM2 验签**）、`SubjectStatusClientFailureIntegrationTest`（真实网络链路 B9） | S4 |
| 行为3-2 签名不匹配 → "验证不通过"+原因 | B6 | `verifyFailsWithSignatureInvalidWhenDataTampered`、集成 `verifyFailsWhenDataTampered` | S4 |
| 行为3-3 已吊销真签名 → "验证不通过"（原因=吊销） | B7 | `verifyFailsWithRevokedWhenIdentityRevoked`、集成 `verifyFailsWithRevokedWhenSignatureIsCryptographicallyReal` | S4 |
| 行为3-4 留痕三要素 + 无数据原文 | B10 | `verificationLogKeepsThreeElementsAndNoDataRawForEveryAttempt`、集成留痕表断言 + 数据原文扫描 | S4 |
| 行为3-5 验证通过 ≠ 授权 | B11 | 集成 `verifyPassesWithRealSm2Signature` 响应字段集断言（data 恰为 `did/result/reason/verifiedAt` 四字段）+ ADR-017 §2.9 声明 | S4 |
| 行为4-验收4 吊销后解析/验证双向口径 | B2/B7 | 集成 REVOKED 两例（解析可见 + 验证不通过） | S3/S4 |
| 边界：文档损坏 → 1005S0002（hifi §6） | —（边界表） | 单元 `resolveThrowsInternalErrorWhenDocumentCorrupted` + 集成同名用例 | S2 |
| 边界：data 超上限 → 1005C0004（hifi §6） | —（边界表） | 单元 `verifyRejectsDataExceedingUpperBound` | S4 |
| 边界：主体服务非 200（body 形似成功）→ UNAVAILABLE（hifi §6） | B9 | `SubjectStatusHttpClientTest.nonHttp200WithSuccessShapedBodyIsUnavailable` | S4 |

### 业务可读交付说明

**完成了什么功能**：给数字身份加上了"查验能力"——任何人凭一个身份编号（DID）可查到它的**公开说明书**（公钥、关联主体、解析入口）与当前状态（只两种：**有效 / 已吊销**）；查不到的编号得到明确答复"未登记"（不会显示成系统故障）。**身份验证**：别人拿"编号 + 一段数据 + 一段签名"来主张"这数据是我发的"，系统做三道检查——①签名是否对得上（国密算法）②身份是否有效 ③其主体是否已入驻——全过才判"验证通过"；任一不过给出**明确原因**（签名不对 / 身份已吊销 / 主体未入驻 / 未登记）；如果第③道检查所依赖的主体服务暂时不可用，系统**如实答复"核验不可用"，不会把它伪装成"验证不通过"**。每次验证留一条"时间/身份/结果（含原因）"记录，但**不保存被验证的数据原文**。

**如何演示**（三服务运行中：主体 8080 / 密钥 8081 / 身份 8082）：
1. **解析**：查有效样本 `http://127.0.0.1:8082/api/v1/did/did:ctds:S20260922000001.1` → 文档 + `ACTIVE`；查已吊销样本 `did:ctds:S20260921000001.1` → 文档 + `REVOKED`；查 `did:ctds:S20260922999999.1` → `1005B0003 该 DID 未登记`；
2. **验证（通过）**：先请密钥服务代签（`POST http://127.0.0.1:8081/api/v1/key-pairs/did-S20260922000001-1/signatures`，body `{"data":"<数据Base64>"}`）拿到签名 → 提交验证（`POST http://127.0.0.1:8082/api/v1/did/did:ctds:S20260922000001.1/verifications`，body `{"data":"...","signature":"..."}`）→ `PASS`；
3. **验证（三类失败）**：把数据改一个字符再提交 → `FAIL/SIGNATURE_INVALID`；对已吊销身份提交真签名 → `FAIL/REVOKED`；对未登记编号提交 → `FAIL/NOT_REGISTERED`；
4. **留痕核验**：查库 `SELECT did,result,reason,occurred_at FROM ctds_did.did_verification_log` 可见每次验证的记录（命令与口令读取方式同前次交付说明）。

**有无注意事项**：
- 解析与验证接口在演示期**无需登录身份**（仅本机可访问；登记于 ADR-017 §2.9 诚实边界，上线前由网关 + 令牌收紧）——演示机须为可信机器；
- 绑定核验需要**主体服务同时在线**（本包的验证依赖它查询"主体是否已入驻"）；主体服务不可达时验证答复为 `UNAVAILABLE`（这是设计行为，不是故障伪装）；
- 验证通过**只证明身份**，不代表任何业务授权（授权由各业务模块另行管理）；
- 本包不含跨空间互认（归 3.1.10）与 DID 管理界面（归 3.1.11）。

### 实施中的设计与实现修正（如实登记）

- **hifi §5 实施修正**：绑定核验原定复用既有主体查询端点，实测被其防枚举归属断言拦截（`did-internal` 仅持只读权限）；改为新增**内部只读端点**（仅状态两字段、不落归属断言），避免为服务读放宽既有安全口径。修正不影响 Q2 方向（仍为服务间只读调用），已留痕 hifi §5 + ADR-017 §2.9。

---

## 4 视角评审与修复（2026-09-23，循环 1/3）

### 评审结论（四个独立评审智能体、只读评审；评审对象 = 提交快照 `693949d`）

| 视角 | 结论 | 必修项（P1/P2） |
| --- | --- | --- |
| ① 规格与设计符合性 | 有条件通过 | 映射表"行为3-5"声称的集成响应字段集断言**不存在**（失实）；"行为2-4"引用错位（以验证留痕扫描冒充解析侧敏感探针） |
| ② 安全供应链 | 有条件通过 | 内部端点功能门槛复用共享权限 `subject.read`（`applicant`/`reviewer` 亦持有），未落地"服务身份专用"；`ctds.did.subject.base-url` 未在 yml 声明 |
| ③ 一致性重复 | 有条件通过 | "ADR-005 §3.5"引用错误（模块位顺延制度实际出处 = **ADR-006 §3.5**） |
| ④ 测试质量 | 有条件通过 | 同①的映射失实（记为 P1）；`data>1MB` 分支无用例；解析侧文档损坏抛裸 `IllegalStateException`，与 hifi §6 的 1005S0002 不符；解析侧缺可证伪反向探针 |

**四视角共同确认的硬事实（无 P1 阻断项）**：验签经 `common-crypto` 唯一入口、零自研密码学、无密钥/数据原文落库或出日志、无新增第三方依赖、无平行错误码段/状态枚举/库表、无未声明的规格外实现（`UNAVAILABLE` 第三值与内部端点均已诚实登记）。

### 必修项修复（全部落地）

| 编号 | 评审问题 | 处置 | 证据 |
| --- | --- | --- | --- |
| R1 | ②P2-1：内部端点权限门槛过宽（可按编号枚举状态） | 新增服务身份专用权限点 `subject.internal.read`（仅 `did-internal` 持有，`InternalAdmissionController` 门槛改用它）；申请人/审核员访问一律 403 | `InternalAdmissionController` / `subject-service application.yml` / `DidIssuanceTriggerIntegrationTest`（applicant·reviewer 双 403 断言） |
| R2 | ①P3-2：非 200 响应若 body 形似成功会被判"已入驻" | 非 200 一律不判通过（业务码 `1000C0003` 除外——其语义为"绑定不成立"，且本身即主体服务 400 业务答复） | `SubjectStatusHttpClient` + `nonHttp200WithSuccessShapedBodyIsUnavailable` |
| R3 | ④P2-4：解析文档损坏未按 hifi §6 出 1005S0002 | 改为 `BizException(1005S0002)` | `DidResolutionService` + 单元/集成各 1 例 |
| R4 | ①P2-1 / ④P1：映射表"行为3-5"失实（无字段集断言） | 集成用例补**响应字段集断言**（data 恰为 `did/result/reason/verifiedAt`），映射表同步更正 | `DidIssuanceIntegrationTest.verifyPassesWithRealSm2Signature` + 本卡映射表 |
| R5 | ①P2-2 / ④P2-2：解析侧无敏感探针（映射错位） | 新增解析侧敏感样式扫描用例（响应全文 0 命中）+ 身份证/手机号/私钥三类**植入样本反向探针**；映射表行为2-4 改指本用例 | `resolutionResponseContainsNoSensitivePlaintext` |
| R6 | ③P2-1：ADR 引用错误 | 三处（hifi 依据行/§4、ADR-017 §2.9）更正为 **ADR-006 §3.5** | `WBS-3.1.9-hifi.md` / `ADR-017` |

### 其余问题处置（P2/P3）

| 项 | 问题 | 处置 |
| --- | --- | --- |
| A | ④：`data>1MB` 边界无用例 | 补单元用例 `verifyRejectsDataExceedingUpperBound` |
| B | ②P3-1：`ctds.did.subject.base-url` 未入 yml（env 注入方式存疑） | `application.yml` 显式声明 `${CTDS_DID_SUBJECT_BASEURL:}`（与 `kms.base-url` 同形）——占位符按环境变量名**精确匹配**，不再依赖 `@Value` 松散绑定；ADR-017 §2.9 同步 |
| C | ①P3-4：留痕单元用例名实不符（未断言"无原文"） | 补"留痕记录文本不含数据/签名原文"断言（值 + 结构双重锚定） |
| D | ①P3-3 / ④P3-6：死类 `DidResolution` | 删除（全仓无引用；解析结果用 `ResolutionResult`） |
| E | ①P3-6：输入类拒绝/内部错误不留痕的口径未显式 | hifi §6 补例外行（"每次验证留痕"指**产生验证结论**的请求） |
| F | ①P3-5 / ④P3-9：`PENDING_ISSUE` 边界无独立用例 | 该记录无 did 值 → 与"未登记"**同一代码路径**，复用既有锚定用例；hifi §6 明确（不另立重复用例） |
| G | ④P3-5：覆盖率数字口径 | 以在树 jacoco 报告为准更新为 **94.8%（347/366）** |
| H | ②P3-2：回环绑定仅 mysql profile 生效 | **既有口径**（3.1.8 起如此、非本包引入）；不夹带修复，留既有登记（DB-24 约束注记/ADR-017 §3） |
| I | ③沉淀建议：服务间 JDK HttpClient 三客户端同构 | **不夹带**；建议随 3.1.10+ 或独立小卡上收 `common`（3.1.8 评审③已登记同项） |

### 评审修复后验证

| 项 | 结果 |
| --- | --- |
| did 测试 | **66/66**（61 + 5：解析文档损坏单元/集成、data 超限单元、非 200 反向锚定、解析敏感扫描集成） |
| subject 测试 | **141/141**（权限收敛断言并入既有用例，用例数不变） |
| checkstyle | 两模块 **0 违规** |
| did 行覆盖率 | **94.8%**（347/366，jacoco 0.8.12 在树报告） |
| 门禁 | **GREEN**（`gate-report-20260923-235835.md`，RunLabel `20260923-234855-9213`，`PASS=10 FAIL=0 SKIP=1 ERROR=0 PENDING=9`，`unitTest` PASS，退出码 0）——评审修复后全量复跑 |

**评审修复结论**：四视角必修项 6 条 + 其余 9 项（含 2 项登记不夹带）全部闭环；未新增规格外实现、未引入依赖、未放宽既有安全口径（内部端点权限面只减不增）。

---

## 验收环境与实测记录（2026-09-24，承接会话日志 `-2359`）

> 性质：**验收环境就绪 + 交付态全链路实测**（不改代码、不改规格、不新增用例）。交付态 = `a4f5ba6`（与 origin 同步）；编排师走查命令见本节与会话日志 `-1950`。

### 环境恢复（含一处必须动作：构件按交付态重建）

| 项 | 结论 |
| --- | --- |
| Docker | 首查引擎停止（`npipe` 不可达）→ 启动 Docker Desktop，`sc-mysql`/`sc-minio`/`sc-redis` 自愈（数据保留） |
| 库与演示数据 | 三库在位（`ctds_subject`/`ctds_kms`/`ctds_did`）；`did_identity` 演示样本三条（`S20260921000001.1` REVOKED + `.2` ACTIVE、`S20260922000001.1` ACTIVE）；`kms_key` 三把 SM2 键（`ENABLED`）；`did_verification_log` 既有 4 条 |
| **构件重建（关键）** | 原 jar（09-23 22:03）**早于当晚评审修复** → 按交付态重建 `services/did` + `services/subject-service`（`mvn -B -ntp -pl services/did,services/subject-service package -DskipTests`，11.5s）；**jar 内验证修复在位**：subject 内部端点类含 `subject.internal.read`（旧 `"subject.read"` 0 命中）、did 错误码类含 `1005S0002` |
| 三服务启动 | mysql profile + 回环：kms 8081（`CTDS_KMS_ROOT_KEY` 沿用演示根密钥）/ subject 8080（`CTDS_DID_ISSUANCE_BASEURL` + `CTDS_SM4_KEY_FILE`）/ did 8082（`CTDS_DID_KMS_BASEURL` + `CTDS_DID_SUBJECT_BASEURL`）；`netstat` 三端口均 `127.0.0.1`；`/actuator/health` 三服务 `UP` |
| 根密钥一致性 | KMS 真实代签成功（`did-S20260922000001-1`）→ 既有 SM2 私钥可解密，**未触发"换新根密钥须清库"路径** |

### 全链路实测（编排师走查同款命令，逐条实测通过）

| # | 场景 | 命令（Git Bash / cmd 均可） | 实测结果 |
| --- | --- | --- | --- |
| 1 | 解析·有效 | `curl.exe "http://127.0.0.1:8082/api/v1/did/did:ctds:S20260922000001.1"` | `code=0` + 文档（SM2 公钥 130 hex、解析入口）+ `status=ACTIVE` |
| 2 | 解析·已吊销 | 同上，替换 DID 为 `did:ctds:S20260921000001.1` | 文档照常返回 + `status=REVOKED`（吊销后仍可解析） |
| 3 | 解析·未登记 | 同上，替换为 `did:ctds:S20260922999999.1` | `1005B0003 该 DID 未登记`（HTTP 400，明确业务答复） |
| 4 | 验证·通过 | `curl.exe -H "Content-Type: application/json" --data-binary @build-output/demo-files/did/verify-pass.json "http://127.0.0.1:8082/api/v1/did/did:ctds:S20260922000001.1/verifications"` | `result=PASS`（data 恰为 `did/result/reason/verifiedAt` 四字段） |
| 5 | 验证·数据篡改 | 同上，body 换 `verify-tamper.json` | `FAIL / SIGNATURE_INVALID` |
| 6 | 验证·已吊销（真签名） | 同上，body 用 `verify-revoked.json` 打 `did:ctds:S20260921000001.1` | `FAIL / REVOKED` |
| 7 | 验证·未登记 | `verify-pass.json` 打 `did:ctds:S20260922999999.1` | `FAIL / NOT_REGISTERED` |
| 8 | 边界·data 超 1MB | 1,048,577 字节 Base64（请求体 1,398,226 字节）打有效 DID | `1005C0004 验证参数不合法`（HTTP 400）且**不留痕**（hifi §6 输入类拒绝例外口径） |
| 9 | **核验不可用**（反向演示） | 停止 subject 服务后提交 #4 同款请求 | `result=UNAVAILABLE / reason=BINDING_UNAVAILABLE`（**系统态未伪装成"验证不通过"**）；重启 subject 后复验回到 `PASS` |
| 10 | 内部端点权限收敛（R1 落地验证） | `curl.exe -H "X-Ctds-Subject: did-internal" -H "X-Ctds-Roles: did-internal" "http://127.0.0.1:8080/api/v1/subject/internal/subjects/S20260922000001/admission"` | 服务身份 `200`（仅 `subjectNo`/`status` 两字段）；`applicant`/`reviewer` **403**（`1000C0005`）；无身份头 **401**；主体不存在 `1000C0003`（400） |
| 11 | 留痕核验 | `powershell -NoProfile -ExecutionPolicy Bypass -File build-output/demo-files/did/show-verification-log.ps1` | 每次产生结论的验证各一条记录（三要素 did / result+reason / occurred_at）；本次冒烟新增 **9 条**（含 UNAVAILABLE 一条） |
| 12 | 留痕无原文 | 留痕表全文转储后扫描样本数据 Base64 与签名 DER 串 | **0 命中**（值 + 结构双重锚定） |

### 与交付说明的口径差异（如实登记）

1. **走查免手工代签**：交付说明原步骤为"先请密钥服务代签 → 复制签名 → 再提交验证"；本次预置三份**免编码坑请求体**（`build-output/demo-files/did/verify-{pass,tamper,revoked}.json`，纯 ASCII，gitignored），编排师**直接提交即可**（SM2 签名随机化只影响新签名形态，**既有签名对同一样本永久有效**）。
2. **`UNAVAILABLE` 属破坏性演示**：需先停 subject 服务；本次已实测并恢复。复看步骤见会话日志 `-1950` 交接提醒⑤。
3. 走查样本编号与既往交付说明一致；三服务以 `127.0.0.1` 回环运行（ADR-016 §2.7），**演示机须为可信机器**。