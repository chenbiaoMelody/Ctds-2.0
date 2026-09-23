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
| `services/did` domain | `DidResolution` / `VerificationLog` / `VerificationOutcome` / `VerificationReason` / `SubjectAdmission` / `SignatureVerifier`（端口）/ `SubjectStatusPort`（端口）+ `DidErrorCodes` 三码 + `DidRepository.insertVerificationLog` |
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
| 行为2-4 解析全文无敏感明文 | B4 | 字段集断言（单元/集成）+ 留痕扫描 `verificationLogContainsNoDataOrSignatureRawBytes`（含反向探针） | S2 |
| 行为3-1 三查全过 → "验证通过" + 留痕 | B5 | `DidVerificationServiceTest.verifyPassesWhenAllThreeChecksPass`、集成 `verifyPassesWithRealSm2Signature`（**真实 SM2 验签**）、`SubjectStatusClientFailureIntegrationTest`（真实网络链路 B9） | S4 |
| 行为3-2 签名不匹配 → "验证不通过"+原因 | B6 | `verifyFailsWithSignatureInvalidWhenDataTampered`、集成 `verifyFailsWhenDataTampered` | S4 |
| 行为3-3 已吊销真签名 → "验证不通过"（原因=吊销） | B7 | `verifyFailsWithRevokedWhenIdentityRevoked`、集成 `verifyFailsWithRevokedWhenSignatureIsCryptographicallyReal` | S4 |
| 行为3-4 留痕三要素 + 无数据原文 | B10 | `verificationLogKeepsThreeElementsAndNoDataRawForEveryAttempt`、集成留痕表断言 + 数据原文扫描 | S4 |
| 行为3-5 验证通过 ≠ 授权 | B11 | 集成响应字段集断言（data 仅 did/result/reason/verifiedAt）+ ADR-017 §2.9 声明 | S4 |
| 行为4-验收4 吊销后解析/验证双向口径 | B2/B7 | 集成 REVOKED 两例（解析可见 + 验证不通过） | S3/S4 |

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