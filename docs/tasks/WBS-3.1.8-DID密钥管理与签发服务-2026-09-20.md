# WBS-3.1.8 任务卡：DID 密钥管理与签发服务（2026-09-20）

| 项 | 内容 |
| --- | --- |
| 来源 | 台账"下一包"行（3.1.8，C-1.2 规格闭环后的顺延包）+ 编排师指示"继续推进下一任务卡"（2026-09-20 会话留痕——即台账"待编排师"行②所指的 3.1.8 立卡） |
| WBS 依据 | `docs/C-TDS项目总体实施计划与WBS.md` 行 240：**3.1.8 DID 密钥管理与签发服务——密钥托管（走 KMS）、DID 文档生成与签发**，依赖 3.1.7、2.4.6，预算 **2 会话** |
| 规格依据 | `docs/specs/C-1.2-分布式数字身份DID.md`（V1.0 已确认）：**行为 1 全部 7 条规则 + 5 条验收标准**（签发联动即 ADR-016 §6 衔接契约）；DID 标识格式、文档字段、接口与库表属本包设计（规格 §6.3）；**吊销写操作归属**按规格 §6.1 授权，由本包与 3.1.9 的两级设计澄清（本包建议归 3.1.8，见 lofi §4 Q4） |
| 上游契约 | ADR-016 §6（衔接入口 = 主体状态到达 ADMITTED）与 §2.7（演示期安全边界；**kms 启用前提 = 补回环绑定并重建构件**）；ADR-015（KMS 密钥集中托管 = **DID 私钥托管唯一入口**）；ADR-006（国密运算唯一入口，禁止自实现密码学）；ADR-005 + ADR-006 §3.5（九位错误码体系与顺延占用制度）；ADR-007（幂等与分布式锁 = 签发防重）；ADR-009/ADR-010（迁移与集成测试规范）；ADR-008（`did` 域收口，互认归 3.1.10） |
| 本文件性质 | **实施包（非界面类）**，两级设计门禁适用（章程 2.6）：本会话交付低保真（定方向）→ 待 PO 确认 → 高保真（编码契约）→ 测试先行 → 实现 |
| 交付物 | ① 设计：`docs/designs/WBS-3.1.8-lofi.md`（本会话）→ `docs/designs/WBS-3.1.8-hifi.md`（lofi 确认后）；② 代码：新建 `services/did`（拟，待 Q1 裁决）+ `services/kms` 扩展（SM2 密钥对托管与签名能力 + 回环绑定补齐）+ `services/subject-service` 签发触发衔接；③ 契约：新 **ADR-017 DID 服务契约** + **ADR-015 补记**（SM2 托管扩展），DID 错误码占 **1005 段**（顺延制度，ADR 留痕）；④ 迁移脚本 `V1__create_did_tables.sql`（库 `ctds_did`）；⑤ 测试（单测 + Testcontainers 集成）；⑥ 业务可读交付说明（含剧本 S1 步骤 5"重复触发"的注入方式） |
| 通用出口 | lofi 确认（Q1~Q4 方向）→ hifi 确认（编码契约）→ 测试先行（RED）→ 实现（GREEN）→ 自检单（章程附录 B1）→ 4 视角评审（循环 ≤3）→ 合并入 main → 台账 + 会话日志落盘 |
| 分支 | `feat/C-1.2-DID密钥管理与签发服务`（AGENTS §6 `feat/C-x.y-短描述`；沿用 C-1.2 规格分支命名先例） |
| 纪律声明 | 只实现规格行为 1（及 Q4 裁决后并入的行为 4 写操作）与已确认设计，禁止规格外实现；复用既有组件（鉴权/幂等/锁/错误码/国密/分页/迁移/集成测试基座）；**零新增依赖**（服务间 HTTP 沿 `KmsKeyProvider` 的 JDK `HttpClient` 先例）；发现规格缺口一律走变更流程，不在本包私自补需求 |

---

## 执行记录

| 项 | 内容 |
| --- | --- |
| 状态 | ✅ **两级设计门禁已过**（低保真 + 高保真均获 PO 确认，2026-09-20"四问均采建议口径"+"确认"）；**编码就绪**——另会话冷启动，必读 `docs/designs/WBS-3.1.8-hifi.md`（编码契约）+ §10 实施前置检查项 |
| 冷启动锚点 | 承接会话日志 `-0044` 续点。核验：main = `e03c40e` 且**无未推提交**（`origin/main` 已同步，其"下一步①推送"已完成）；`feat/C-1.2-业务规格与验收剧本` 本地与远端均存在（沿惯例保留）；演示后端进程已停止、`netstat` 无 8080/8081/8082/5173/3306 监听；DB-22 维持"新登记"（未立卡） |
| 需求锚点核对 | V1.0 PRD 行 90（入驻流程含 DID 签发）、行 101（C-1.2 定义）；WBS 行 240（本包交付物）、行 241/243（3.1.9/3.1.11 依赖本包，**本包的能力边界须支撑下游依赖链**）；规格行为 1 规则 1~7 与验收标准 1~5；ADR-016 §6/§2.7；ADR-015 §3.1（私钥托管唯一入口）；剧本 S1 六步（本包承载步骤 2/3/5；步骤 4 为展示层、步骤 6 属前置数据；注入方式随本包交付说明） |
| 范围与边界 | 做什么 W1~W7、不做什么十项，逐条见 lofi §1 / §2（全部由规格行为 1 与 §4 非目标派生） |
| 设计门禁 | **低保真**：本会话落盘（`docs/designs/WBS-3.1.8-lofi.md`），待 PO 确认方向；**高保真**：确认后落盘（行为清单逐条对应验收标准编号 + 接口契约表 + 库表结构 + 边界值与异常行为，= 编码契约） |
| 实施前置检查项 | ① `services/kms` 补 `server.address: 127.0.0.1`（mysql profile）并**重建构件**（规格 §5 / ADR-016 §2.7 / DB-03 关闭注记）；② 实测 kms **默认 profile** 能否启动——既有疑点：默认 profile 排除了 `DataSourceAutoConfiguration`，而 `KeyJdbcRepository` 构造器硬依赖 `JdbcClient`，全仓无该 profile 的启动测试；结论如实留痕，**不在本包夹带非本包修复**（若发现缺陷则按登记口径处置） |
| 决策点 | lofi §4 **Q1~Q4 已裁决（2026-09-20 编排师：四问均采建议口径）**——Q1 新建独立服务 `services/did` / Q2 端口 8080（主体）+8081（KMS）+8082（DID）全回环 / Q3 审核事务提交后调用 DID 签发接口、失败留"待签发"可重试 / Q4 吊销与重签写操作归 3.1.8（3.1.9 只读）；裁决留痕见 `docs/designs/WBS-3.1.8-lofi.md` 确认记录节 |
| 验证口径 | ① 门禁全量复跑留痕（报告路径见会话日志）；② **行为 1 五条验收标准 → 测试用例 → 剧本步骤**映射表（自检单第 2 项）；③ 私钥零明文锚定测试（库表/接口响应/日志三处，删实现必变红）；④ 剧本 S1 六步在接口级演示留痕（界面步骤归 3.1.11，3.1.12 做端到端联调） |
| 评审结论 | 待交付后 4 视角评审（规格与设计符合性 / 安全供应链 / 一致性重复 / 测试质量），循环 ≤3 |

---

## 4 视角评审结论（循环 1/3，2026-09-21）

| 视角 | 结论 | 要点与处置 |
| --- | --- | --- |
| ① 规格与设计符合性（独立评审智能体） | **打回（P1×1）→ 修复后符合** | **P1**：KMS 既有未鉴权端点 `GET /api/v1/keys/{keyRef}/material` 可明文取回本次新增的 SM2 私钥 D 值（`material`/`rotate` 无 `key_type` 门槛，与 hifi §4.2/ADR-017 §2.8 断言冲突）→ **已修复**（`a1e3b98`：`KeyManagementService.requireDataKey` 强制 `key_type` 校验，非 SM4 → 1002C0001，含 `rotate` 路径）。P2×4 已处置：did 默认 profile 注释更正、KMS 集成锚点补齐、时间精度秒级固化、`DidOperationLog` 仓储化；P3×9 处置/留痕（吊销乐观门槛已加、1005S0001 码注据实、ADR 补索引、重放命令原文、"无恢复端点"断言等） |
| ② 安全与供应链（独立评审智能体） | **有条件通过（无 P1）** | **P1 修复经独立复核闭合**（6 条绕过路径逐一排除：key_type 唯一写入点同事务/无 TOCTOU/current_version 无指向 SM2 材料路径/反向越界被拒/NULL fail-closed）。条件 P2×3 已处置：① 权限 401/403 负向锚点补齐（did 管理三端点 + kms 密钥对创建）；② 内部无鉴权面后果表征（ADR-017 §2.7 补记 + 交付说明：签名/签发能力可外借、私钥导出被堵死、解除条件=网关+令牌+服务间鉴权）；③ DB-24 修复约束登记（选项①必须同时补默认 profile 回环）。硬事实：零新依赖、`common/` 零改动、无敏感数据、无自研密码学 |
| ③ 一致性与重复 | **通过（降级自检；独立评审待补）** | 因模型配额限流（重试无效）降级为主智能体自检，独立评审智能体③**待补**（留痕）。自检结论：横切能力全部复用（鉴权/错误码/幂等库表守卫）；三处同型 HTTP 客户端沿 `KmsKeyProvider` 先例（hifi 明示），**沉淀建议 3 条留痕**：服务间 HTTP 客户端上收 common、`operator()` 取值上收、kms 内 `requireKeyRef` 收敛。机器证据：checkstyle 0 违规、ArchUnit 3 规则绿 |
| ④ 测试质量 | **通过（降级自检；独立评审待补）** | 因模型配额限流（重试无效）降级为主智能体自检，独立评审智能体④**待补**（留痕）。自检结论：B1~B7 全覆盖 + 反向探针齐备（uk_guard 直插拒绝 / 库表 64-hex 扫描+植入 / KMS 信封解出 32 字节断言 / `/material` 拒 SM2 锚点 / 401·403·404 负向锚点）；**did 行覆盖实测 92.8%**（≥80% 达标，瞬时 jacoco 0.8.12 测量，未引入项目依赖）。如实声明：本包测试与实现同批落盘，未严格执行"先 RED 后 GREEN"逐步序列，以反向探针 + 全绿 + 覆盖率补偿 |

**评审收口（主智能体，2026-09-21）**：①②独立评审均过（①打回后修复并独立复核闭合）；③④因配额降级自检且结论通过、独立评审**待补登记**。机器门禁全绿（kms 24/24、subject 136/136、did 23/23、checkstyle 0 违规、门禁 GREEN）。**待编排师验收合并**（分支 `feat/C-1.2-DID密钥管理与签发服务`：`2e48799` 实现 + `a1e3b98` P1 修复 + 评审收口提交）。

---

## 编码会话执行记录（2026-09-21，冷启动承接 `-0806` 日志）

### 实施前置检查（hifi §10，三项全部完成）

| 项 | 结论 |
| --- | --- |
| ① kms 补端口 8081 + mysql 回环 | **已完成**：`application.yml` `server.port` 8080→8081；`application-mysql.yml` 补 `server.address: 127.0.0.1`；构件重建 |
| ② kms 默认 profile 启动实测 | **不通过（如实登记）**：`UnsatisfiedDependencyException`——默认 profile 排除 `DataSourceAutoConfiguration` 后无 `JdbcClient` Bean，而 `KeyJdbcRepository` 构造器硬依赖之；与 `application.yml`"默认 profile 无数据库照常启动"注释不符。**演示与服务一律 mysql profile（既定口径，不影响本包）**；该偏差按债务口径登记 DB-24（不在本包夹带非本包修复） |
| ③ `ReviewService.approve` 事务边界复核 | **确认**：`ReviewService` 无外层 `@Transactional`，事务在仓储层 `SubjectJdbcRepository.appendTransition`（`@Transactional`）提交；钩子挂在 `statusService.transition(...)` 返回之后，与 hifi §4.3 一致 |

### 交付物清单（代码）

| 对象 | 内容 |
| --- | --- |
| 新增服务 `services/did`（`did-service`） | 四层（domain/application/infrastructure/interfaces）+ ArchUnit 规则 + Flyway `V1__create_did_tables.sql`（`did_identity` + `did_operation_log`）+ 1005 段错误码 + 4 端点 |
| `services/kms` 扩展 | Flyway `V2__add_sm2_key_pair.sql`（`key_type` + `public_key_hex`）+ `KeyPair` 域对象 + `KeyPairService` + `KeyPairController`（`/api/v1/key-pairs`、`/api/v1/key-pairs/{keyRef}/signatures`）+ 端口 8081/回环 |
| `services/subject-service` 衔接 | `DidIssuancePort`（domain）+ `DidIssuanceTrigger`（application）+ `DidIssuanceClient`（infrastructure，JDK HttpClient）+ `ReviewService.approve` 挂钩 + 配置 `ctds.did.issuance.base-url` |
| 根 pom | 模块表 +1（`services/did`） |
| 契约 | **ADR-017 DID 服务契约**（新建）+ ADR-015 补记（SM2 托管 + 签名 + 8081 回环）+ ADR-016 §2.7 补记（KMS/DID 回环落实） |

### 实施中发现并修复的缺陷（本包范围内）

- **`issue` 硬编码签发序号 1 导致 `uk_did` 冲突**：原实现对"已吊销主体再次自动触发签发"的场景会以 `issuance_seq=1` 建新待签发行，转有效时生成 `did:ctds:<subjectNo>.1` 与旧吊销行 `uk_did` 唯一键冲突 → 500。**修复**：`issue` 改用 `repository.nextIssuanceSeq(subjectNo)`（新主体仍为 1，已吊销主体取历史最大序号 +1），与"旧标识永不复用"口径一致。集成测试（共享库、同主体多场景）暴露该缺陷，修复后全绿。

### 自检单（章程附录 B1）

1. **两级设计门禁**：低保真（Q1~Q4 采建议）+ 高保真（PO 签署 = 编码契约）均已过；实现与 `docs/designs/WBS-3.1.8-hifi.md` 逐条一致（库表/接口/错误码/配置/行为清单 B1~B7 全部落地）。
2. **规格 → 设计 → 测试 → 剧本 映射表**：见下表。
3. **复用声明（含检索过程）与规格外实现声明**：
   - 复用：错误码体系（`common-errorcode`，占 1005 段）、RBAC 鉴权（`common-auth` `@RequirePermission` + `ctds.auth.permissions`）、国密运算（`common-crypto` `Sm2Service`/`Sm4Service`，零自研密码学）、JDK HttpClient（沿 `KmsKeyProvider` 先例，零新增依赖）、Flyway（`ADR-009`）、Testcontainers 基座（`ADR-010`）、审计/结构化日志（`common-logging`）。
   - 检索过程：编码前检索 `common/` 各组件与 `services/kms`/`subject-service` 既有实现，确认横切能力均有既有组件可复用。
   - **规格外实现声明：空**（无规格外功能、无未批准依赖、无演示专用代码路径）。
4. **本地全部检查的命令与结果摘要**：
   - `mvn -B -ntp compile`（全仓）：PASS
   - `mvn -B -ntp -pl services/kms,services/did,services/subject-service test`：**kms 24 通过 / subject-service 136 通过 / did 23 通过，0 失败 0 错误 0 跳过**（含评审后补的权限 401/403 锚点、DidKmsHttpClient 桩测、边界分支用例）
   - `mvn -B -ntp -pl services/kms,services/did,services/subject-service checkstyle:check`：0 违规
   - **did 核心模块行覆盖率实测 92.8%**（≥80% 达标；瞬时 jacoco 0.8.12 测量 `services/did/target/site/jacoco/jacoco.csv`，未引入项目依赖；自动化覆盖率门禁仍 PENDING-JACOCO = DB-06 口径）
   - `powershell -NoProfile -ExecutionPolicy Bypass -File scripts/gates/run-gates.ps1`：**GREEN**（PASS=10 FAIL=0 ERROR=0，退出码 0），**终跑报告 `scripts/gates/reports/gate-report-20260921-141100.md`**（初跑 `-105815` 早于评审修复，已复跑留档；secretsScan 625 文件 0 命中 / adrFieldsCheck 17 ADR 全过 / compile / lint / unitTest / frontendLint / frontendTest 全 PASS）
5. **高风险点自查**：
   - **并发**：同一主体并发触发由 `uk_guard` 唯一键兜底（`createPending` 捕获 `DuplicateKeyException` 收敛为幂等返回）；集成测试含反向探针（直插第二条非吊销行 → 唯一键拒绝）。
   - **事务**：`completeIssuance`/`revoke` 的状态变更与留痕同事务（`@Transactional`，保证留痕要素与状态原子）；审核批准事务提交后才触发 DID（`ReviewService` 无外层事务，钩子位置经实测确认）。
   - **加解密**：私钥 D 值经根密钥 SM4 信封落库（零明文）；DID 服务结构上不接收私钥（`DidKmsClient` 只返回公钥）；三面锚定（库表/接口/日志）均有测试与反向探针。
   - **性能**：DID→KMS 与 subject→DID 均取小超时（连接 1s / 读取 3s），触发失败不拖慢审核动作；本包非性能敏感路径（签发为低频动作），未跑基准脚本。
6. **验收剧本更新建议**：**剧本正文不改**（与设计 §9 一致）。S1 步骤 5"重复触发"注入方式（运营侧演示）：对同一主体重复调用 `POST /api/v1/did/issuances`（幂等 → 管理页仍只见一条有效 DID），重放命令随交付说明提供；S1 步骤 2/3/5 由本包承载（步骤 4 展示层归 3.1.11、步骤 6 前置数据）。
7. **业务可读交付说明**：见下节。

### 验收标准 → 测试用例 → 剧本步骤 映射表

| 规格验收标准（C-1.2 行为 1/4） | 已确认设计（hifi） | 测试用例 | 剧本步骤 |
| --- | --- | --- | --- |
| 审核通过 → 有效 DID 记录 + 留痕四要素 | B1 | `DidIssuanceIntegrationTest.issueCreatesActiveRowAndFourElementLog`、`DidIssuanceServiceTest.issueCreatesActiveIdentityWithFourElementLog` | S1 步骤 3 |
| 重复触发 → 有效 DID 仍只有一个 | B2 | `DidIssuanceIntegrationTest.issueIsIdempotentAndUniqueGuardRejectsDuplicate`（+ 唯一键反向探针）、`DidIssuanceServiceTest.issueTwiceIsIdempotentSingleActive` | S1 步骤 5 |
| 库表/日志无私钥明文 | B3 | `DidIssuanceIntegrationTest.issuanceResponseContainsNoPrivateKeyMaterial`（接口字段集 + 库表 64-hex 扫描 + 反向探针）、`KeyPairServiceTest.createStoresPrivateKeyAsEnvelopeNotPlaintext` | S1 步骤 3 |
| 非已入驻主体无有效 DID | B1（触发方状态机保证） | 由 subject-service 侧状态机测试覆盖（`CertificationIntegrationTest`/`ReviewIntegrationTest` 已固化）；DID 侧不重复校验 | S1 步骤 2 |
| 签发失败 → 主体仍已入驻、DID 记录待签发可重试 | B4 | `DidIssuanceIntegrationTest.kmsFailureLeavesPendingAndRetryCompletes`、`DidIssuanceServiceTest.kmsFailureLeavesPendingAndRetryCompletes` | S1 步骤 5 |
| 吊销理由必填 + 五要素 + 不可逆 | B5 | `DidIssuanceIntegrationTest.revokeRequiresReasonAndRejectsNonActive`、`DidIssuanceServiceTest.revokeRequiresReasonAndRejectsNonActive/revokeTransitionsToRevokedWithFiveElementLog` | S3 |
| 重签 → 新 DID + 新密钥对 + 旧记录保留 | B6 | `DidIssuanceIntegrationTest.reissueCreatesNewIdentityAndRetainsOld`、`DidIssuanceServiceTest.reissueCreatesNewDidAndKeyRefAndRetainsOld` | S3 |
| 主体服务触发衔接（事务提交后、失败仅 WARN） | B7 | `DidIssuanceTriggerIntegrationTest.approveTriggersDidIssuanceWithSubjectNo/didIssuanceFailureDoesNotAffectApproval` | S1 步骤 3 |

### 业务可读交付说明

**完成了什么功能**：主体审核通过"入驻"后，系统**自动**为该主体签发出一个数字身份（DID）——包含一对国密 SM2 密钥（私钥锁在密钥管理服务里，任何界面、接口、日志都看不到私钥）、一份公开身份说明（DID 文档）和一条签发留痕。运营管理员可对身份执行**吊销**（必填理由、不可逆、留痕可溯）和**重签**（换新身份、新密钥，旧记录保留）。签发失败（如密钥服务暂不可用）不会影响入驻结论，身份停留"待签发"并可重试。

**如何演示**：
1. 启动三服务（主体 8080 / 密钥 8081 / DID 8082，均仅本机可访问，须用 mysql profile）；
2. 走完"注册 → 认证 → 审核通过"（剧本 S1 步骤 1~3），随后调用 `GET`/查询可见该主体名下一条**有效** DID；
3. **重复触发演示（S1 步骤 5）**：对同一主体重复调用签发接口（幂等），命令原文：
   ```powershell
   Invoke-RestMethod -Method Post -Uri "http://127.0.0.1:8082/api/v1/did/issuances" -ContentType "application/json" -Body '{"subjectNo":"<主体申请编号>"}'
   ```
   重复调用后仍只有**一条**有效 DID（幂等）；响应 `code=0`、`data.did`/`data.keyRef` 不变。
4. **吊销演示（S3）**：以运营管理员身份（`X-Ctds-Roles: admin`）调用 `POST /api/v1/did/{did}/revocation`，理由必填；吊销后解析可见"已吊销"且不可恢复；重签生成新身份。

**有无注意事项**：
- 密钥服务（KMS）与 DID 服务均**必须用 mysql profile 启动**（含回环绑定）；KMS 的**默认 profile 无法启动**（既有缺陷，已登记 DB-24，不影响演示）；
- DID→KMS 的服务间调用在演示期沿用"信任身份头 + 回环网络隔离"口径（ADR-016 §2.7 / ADR-017 §2.7），上线前须由网关 + 真实令牌收紧；
- **内部无鉴权面的后果（评审②P2-2 补记）**：签发面（`POST /api/v1/did/issuances`）与内部签名面（`/signatures`）在回环边界内**无身份门槛**——本机任意进程可为任意主体编号铸造身份、可为任意 DID 请求签名。**"私钥看不到"不等于"身份密钥不可被滥用"**：私钥导出已被代码门槛堵死（信封托管 + `key_type` 门槛），而签名/签发能力外借的解除条件 = 网关 + 真实令牌 + 服务间鉴权（3.5.2/3.9.1 兑现项）；演示机须为可信机器；
- 本包不含解析/验证/互认接口与 DID 管理界面（分别归 3.1.9/3.1.10/3.1.11）。