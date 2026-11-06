# WBS-3.8.3 全链路存证埋点 · 高保真设计（V0.9，待一次确认 → V1.0 即编码契约）

| 项 | 内容 |
| --- | --- |
| 低保真 | `docs/designs/WBS-3.8.3-lofi.md`（决策点 Q1~Q10 + D1） |
| 上游规格 | `docs/specs/C-8.1-8.2-存证与审计.md` **V1.1**（B1-1~B1-4 / B2-1 / B2-2 / Q10；§2 术语"四环节 / 内容摘要"） |
| 上游交付 | WBS-3.8.2：`POST /api/v1/evidence/internal/records` + 六类操作类型 + 三元组幂等 + 1012 五码（契约冻结，本卡零改动） |
| 上游契约 | ADR-004（白名单五项）、ADR-005（ApiResult + 9 位错误码）、ADR-007（幂等）、ADR-016 §2.7（回环绑定）、ADR-018（门禁） |
| 性质 | 非界面类；本文件 = 编码契约（客户端契约 / 事件源 / 字段规则 / 事务 / 配置 / 测试锚点） |
| 版本 | **V1.0（2026-11-06 编排师一次确认 = 编码契约即时生效）**：Q1~Q10 均采建议口径 A + D1 不拆分（确认留痕见任务卡 §二）；V0.9 = 立卡段草案 |

## 1. 存证提交客户端契约（Q1 = A）

### 1.1 模块与文件

```
common/evidence-client/
  pom.xml                                    # parent ctds-platform 2.0.0-SNAPSHOT, relativePath=../../pom.xml
  src/main/java/com/ctds/common/evidence/
    EvidenceEvent.java                       # record(String stageType, String businessNo, String operationType,
                                             #        String subjectDid, Instant occurredAt, String actorSubjectNo)
    EvidenceRecorder.java                    # 提交入口（afterCommit + HTTP + 失败降级）
    EvidenceClientProperties.java            # @ConfigurationProperties("ctds.evidence.client")
    EvidenceClientAutoConfiguration.java     # @AutoConfiguration + @EnableConfigurationProperties
  src/main/resources/META-INF/spring/
    org.springframework.boot.autoconfigure.AutoConfiguration.imports   # com.ctds.common.evidence.EvidenceClientAutoConfiguration
```

- 根 pom：`<modules>` 增 `common/evidence-client`（**20→21**）；`<dependencyManagement>` 增 `common-evidence-client`（`${project.version}`）；
- 依赖：`spring-boot-starter`、`spring-tx`（`TransactionSynchronizationManager`）、`jackson-databind`、`slf4j-api`；测试 `spring-boot-starter-test`。**零新第三方依赖**（均为 Boot BOM 已管理构件）；
- 三个服务 pom 增 `common-evidence-client`（`space-service` / `contract-service` / `delivery-service`）。

### 1.2 公开 API

```java
public final class EvidenceRecorder {
    /** 业务事务内调用：有事务 → 提交后发送；无事务 → 立即发送；任何失败不抛出。 */
    public void record(EvidenceEvent event);
}
```

- 不提供 `recordAndWait` / 返回值：埋点不是业务判定输入；
- 不在客户端做去重（去重由存证侧 `uk_evidence_idem` 承担，重放返回 `duplicate=true`）；
- 不缓存、不重试、不排队（Q7 = A）。

### 1.3 发送契约

| 项 | 口径 |
| --- | --- |
| 方法 / 路径 | `POST ${base-url}/api/v1/evidence/internal/records` |
| 请求头 | `Content-Type: application/json`；`X-Ctds-Subject: ${spring.application.name}`；`X-Ctds-Roles: evidence-internal` |
| 请求体 | `{"stageType":"...","businessNo":"...","operationType":"...","subjectDid":"did:ctds:...","occurredAt":"2026-11-06T14:12:00Z","actorSubjectNo":"..."}`（**固定六键，无附加字段**） |
| 时点格式 | `Instant.toString()`（UTC、ISO-8601、`Z` 结尾）；由各域 `LocalDateTime.atZone(clock.getZone()).toInstant()` 换算 |
| 超时 | connect **2s** / read **3s**（可配置） |
| 成功判定 | HTTP 200 且响应 `code == "0"`；其余（非 200 / 网络异常 / JSON 解析失败）一律视为失败 |
| 失败处置 | `log.warn("存证提交失败: stage={}, businessNo={}, operationType={}", ...)`（**不含 DID / 原文 / 堆栈**），**不抛出、不影响业务响应** |
| 关闭 / 未配置 | `enabled=false` 或 `base-url` 空白 → 不发请求，`log.warn` 一次（每次调用一条，含环节 / 单号） |

### 1.4 事务语义（Q7 = A）

```
record(event)
  ├─ 无事务（或同步未激活） → 立即 send
  └─ 有事务 → TransactionSynchronizationManager.registerSynchronization(afterCommit → send)
              ├─ 事务提交 → send（异常在回调内被捕获）
              └─ 事务回滚 → 不 send（零存证）
```

- **禁止在业务事务内直接发 HTTP**：避免"存证已发、业务回滚"的假证据；
- `afterCommit` 回调内必须 `try/catch(Throwable)`，Spring 会把回调异常抛给调用方（事务已提交，业务不能因此失败）；
- 业务方法返回后再调用（无事务场景，如交付创建）与 afterCommit 语义等价（落库已提交）。

### 1.5 配置

```yaml
ctds:
  evidence:
    client:
      enabled: true                       # 默认 true
      base-url: ${CTDS_EVIDENCE_BASEURL:} # 默认空 = 跳过 + WARN；部署清单显式注入
      roles: evidence-internal            # 存证服务 yml 中映射到 evidence.internal.write 的角色名
      connect-timeout: 2s
      read-timeout: 3s
```

- 身份头 `X-Ctds-Subject` 取 `spring.application.name`（`space-service` / `contract-service` / `delivery-service`），`X-Ctds-Roles` 固定 `evidence-internal`（与 `evidence-service/application.yml:32` 的角色映射一致）；
- 内部调用走回环 / 集群内网（ADR-016 §2.7），不经统一网关。

## 2. 事件源与字段规则（编码契约）

> 所有 hook 都在**上游权威写点成功之后**；失败 / 拒绝 / 回滚 / 幂等重放路径**不得**调用。

| # | 环节 / 操作类型 | 上游落点（绝对路径:行号） | businessNo | subjectDid 来源 | actorSubjectNo | occurredAt |
| --- | --- | --- | --- | --- | --- | --- |
| E1 | AUTHORIZATION / `SPACE_MEMBER_ADMITTED` | `services/space-service/.../application/SpaceAdmissionService.java:271-279`（`activate`，审批 `:175` / 确认 `:138` 共用） | `SPACE{spaceId}:ADMISSION{admissionId}` | 被准入成员：`admission.subjectNo()` → `DidInternalClient.activeDid(...)` | `operator`（审批人 / 确认人） | `now`（`LocalDateTime.now(clock)`，与成员行 `joined_at` 同源） |
| E2 | AUTHORIZATION / `SPACE_POLICY_INHERITED` | `services/space-service/.../application/SpacePolicyService.java:119-159`（`submitOverride`，`POLICY_OVERRIDE` 成功分支） | `SPACE{spaceId}:POLICY-AL{actionLogId}` | 操作人：`subject` → `DidInternalClient.activeDid(...)` | `subject`（空间管理员） | `LocalDateTime.now(clock)`（与留痕 `created_at` 同源） |
| E3 | AUTHORIZATION / `DATA_AUTH_CONTRACT_EFFECTIVE` | `services/contract-service/.../application/ContractCommandService.java:268-325`（`sign` 后 `signAndSync` 返回 `EFFECTED`） | `contractNo`（逐字透传） | `contract_attestation.requester_did`（需方） | `operatorNo`（后签一方） | `contract_attestation.attested_at` |
| E4 | CONTRACT / `CONTRACT_EFFECTIVE` | 同 E3（**双写，Q5 = A**） | `contractNo` | 同 E3 | 同 E3 | 同 E3 |
| E5 | DELIVERY / `DELIVERY_CREATED` | `services/delivery-service/.../application/DeliveryCommandService.java:88-196`（`persistence.persist` 成功、审计 `:193` 之后） | `deliveryNo`（`DL%06d`，逐字透传） | `contract-summaries` 响应的 `requesterDid`（本卡新增字段） | `requester`（交付发起人） | 落库行的 `created_at`（`repository.findByDeliveryNo(...)`） |
| E6 | USAGE / `USAGE_ALLOWED` | `services/contract-service/.../application/PolicyExecutionService.java:68-127`（`insertAllowed` 返回的放行行 id；`:91` 与 `:125` 两分支） | `USG{usageLogId}`（12 位零填充，如 `USG000000001024`） | `contract_attestation.requester_did`（按 `contractNo` 只读） | `request.requesterNo()` | `now`（与放行留痕 `occurred_at` 同源） |

**字段规则**

- `stageType` / `operationType` 只用 3.8.2 已定六类，不新增值；环节与操作类型按 3.8.2 强绑定；
- `businessNo` 不做 trim / 大小写转换 / Unicode 归一（3.8.2 `EvidenceRules` 原样透传）；
- `subjectDid` 必须是 `did:ctds:` 开头的 ACTIVE DID；取不到 → **跳过该条 + WARN**，不使用 `subjectNo` 冒充；
- `occurredAt` 不晚于当前时间 +5 分钟、不早于 2020-01-01（3.8.2 既有守卫）；
- `actorSubjectNo` 取业务操作人（审批人 / 确认人 / 后签方 / 交付发起人 / 使用请求方），格式 `^[A-Za-z0-9_.-]{6,32}$`。

**零事件清单（负向，必须用例覆盖）**

| 场景 | 原因 |
| --- | --- |
| 空间红线放宽拒绝 / 同值幂等 / 权限拒绝 / 状态门槛 | 无成功业务动作（Q10） |
| 合约首签未生效 / 重复签署 / 状态冲突 | 只有双签 `EFFECTED` 才是"合约生效" |
| 交付参数 / 形态 / 准入 / 判定失败；幂等重放 | 无新建交付记录 |
| 使用判定拒绝（状态失效 / 越界 / 配额耗尽） | Q10：拒绝调用不产生存证 |
| 任意业务事务回滚 | afterCommit 不执行 |

## 3. 各服务改动清单

### 3.1 空间服务（`services/space-service`）

| 文件 | 改动 |
| --- | --- |
| `application/SpaceEvidencePublisher.java`（新增） | `memberAdmitted(spaceId, admissionId, subjectNo, operator, at)` / `policyEffective(spaceId, actionLogId, operator, at)`；内部解析 DID → `EvidenceRecorder.record`；DID 不可用 → WARN + 跳过 |
| `infrastructure/DidInternalClient.java`（新增） | `GET ${ctds.space.did.base-url}/api/v1/did/internal/subjects/{subjectNo}/current`；头 `space-service` / `space-internal`；`status == ACTIVE` 才返回 DID，其余 `Optional.empty()`；未配置 / 不可达 → 空 + WARN |
| `application/SpaceAdmissionService.java` | `activate` 成功后调 `publisher.memberAdmitted(...)`（入参含 `space.id()`、`admission.id()`、`admission.subjectNo()`、`operator`、`now`） |
| `application/SpacePolicyService.java` | `submitOverride` 成功后取 `actionLogId` 调 `publisher.policyEffective(...)`；红线拒绝 / 同值分支不调用 |
| `domain/SpaceRepository.java` / `infrastructure/SpaceJdbcRepository.java` | **新增**两个返回留痕 id 的方法：`long insertSpaceOverrideWithLogId(SpacePolicy, SpaceActionLog)` / `long updateSpaceOverrideValueWithLogId(long, long, String, SpaceActionLog)`（内部复用既有写入逻辑与 `GeneratedKeyHolder`）；既有 `insertSpaceOverride` / `updateSpaceOverrideValue` 保留并委托（返回 entryId / void），**既有测试契约零破坏**；私有 `insertLogWithinTransaction` 改为返回 `long`（既有调用作为语句忽略返回值即可编译）。**不改表结构** |
| `application.yml` | `ctds.space.did.base-url: ${CTDS_SPACE_DID_BASEURL:}`；`ctds.evidence.client.*` |
| `pom.xml` | 增 `common-evidence-client` |

### 3.2 合约服务（`services/contract-service`）

| 文件 | 改动 |
| --- | --- |
| `application/ContractEvidencePublisher.java`（新增） | `contractEffective(Contract, ContractAttestation, operatorNo)`：先发 `CONTRACT/CONTRACT_EFFECTIVE`；若 `contract.templateNo()` 对应模板类型 = `PUBLIC_DATA_AUTHORIZATION`，再发 `AUTHORIZATION/DATA_AUTH_CONTRACT_EFFECTIVE`（Q5）；`usageAllowed(contractNo, requesterNo, usageLogId, at)`：查需方 DID → 发 `USAGE/USAGE_ALLOWED` |
| `application/ContractCommandService.java` | `sign`：`signAndSync` 返回 `EFFECTED` 后取 `repository.findAttestation(contract.id())`，调 `publisher.contractEffective(...)`；`FIRST_SIGNED` / 异常不调用 |
| `application/PolicyExecutionService.java` | 两个 allow 分支：`long usageLogId = logRepository.insertAllowed(...)` 后调 `publisher.usageAllowed(...)`；拒绝分支不调用 |
| `domain/UsageLogRepository.java` / `infrastructure/JdbcUsageLogRepository.java` | `insertAllowed` 返回 `long`（`GeneratedKeyHolder`）；`insertDenied` 保持 `void`；既有调用作为语句不受影响，测试替身补返回值（预计 ≤2 处） |
| `application/ContractQueryService.java`（或新增只读查询） | 新增 `Optional<String> requesterDid(String contractNo)`：`contract_attestation.requester_did` 只读；DTO / 出站契约不变 |
| `application/ContractEvidencePublisher.java` | 模板类型经 `ContractTemplateRepository.findByNo(templateNo).map(ContractTemplate::type)` 判定；不新增表列 |
| `application.yml` / `pom.xml` | `ctds.evidence.client.*`；增 `common-evidence-client` |

### 3.3 交付服务（`services/delivery-service`）

| 文件 | 改动 |
| --- | --- |
| `application/DeliveryEvidencePublisher.java`（新增） | `deliveryCreated(Delivery delivery, String requesterDid)`；DID 空 → WARN + 跳过 |
| `application/DeliveryCommandService.java` | 真实新建成功分支（`persist` + `audit.jsonOnly` 之后）取落库行，调 `publisher.deliveryCreated(...)`；幂等重放 / 失败分支不调用 |
| `domain/ContractSummary.java` / `infrastructure/ContractInternalClient.java` | 增 `requesterDid` 字段并解析（缺失容忍为 null） |
| `application.yml` / `pom.xml` | `ctds.evidence.client.*`；增 `common-evidence-client` |

### 3.4 合约服务内部摘要（供交付取 DID）

- `interfaces/dto/InternalContractSummaryViews.Summary` 增 `String requesterDid`（**向后兼容**：只增字段，不改既有字段名与语义）；
- `application/InternalContractSummaryService.summary`：`repository.findAttestation(contract.id()).map(ContractAttestation::requesterDid).orElse(null)`；
- 消费方仅 `delivery-service`（实读确认）；无其他调用方受影响。

### 3.5 DID 服务

- `services/did/src/main/resources/application.yml` 的 `ctds.auth.permissions` 增 `space-internal: did.internal.read`（仅只读；不外借 `did.admin`）；
- 不新增端点、不改 `CurrentDidView`、不改表。

## 4. 事务、幂等与重复

| 场景 | 行为 |
| --- | --- |
| 业务成功 + 事务提交 | afterCommit 发 1 次；存证侧生成 1 条记录 |
| 业务失败 / 事务回滚 | 0 次调用，0 条记录 |
| 同一业务动作重复触发（重复交付 / 重复签署 / 重复准入） | 业务侧幂等门槛拦住，不产生第二次调用；即使调用，存证侧三元组唯一键返回 `duplicate=true`，**零新行、零新留痕** |
| 同一空间策略条目再次覆盖 | 每次成功覆盖产生新的 `space_action_log.id` → 新 `businessNo` → 新存证（一次业务动作一条） |
| 同一合约多次使用放行 | 每次放行产生新的 `contract_usage_log.id` → 新 `USG...` → 新存证（使用按次） |
| 存证服务返回 `duplicate=true` | 客户端视为成功（幂等语义），不再重试 |
| 存证服务不可达 / 5xx | 客户端 WARN，业务成功；**无补偿队列**（跟踪项） |

- 客户端不产生"待发送表"；不引入 MQ / outbox / 定时任务（Q7）；
- 不修改 evidence 的三段事务、状态机与错误码（3.8.2 冻结）。

## 5. DID 解析与权限

| 域 | 来源 | 失败处置 |
| --- | --- | --- |
| 合约 / 使用 | 同库 `contract_attestation.requester_did`（`findAttestation` / 只读查询） | 空 → 跳过该条 + WARN；业务不失败 |
| 交付 | 合约内部摘要新增 `requesterDid` | 空 → 跳过该条 + WARN |
| 空间（成员 / 策略） | `did` 内部端点 `GET /api/v1/did/internal/subjects/{subjectNo}/current`（`did.internal.read`） | 未配置 / 不可达 / 非 ACTIVE / `NONE` → 跳过该条 + WARN |

- 空间客户端只读三个字段（`subjectNo` / `did` / `status`），不落库、不缓存；
- 服务身份头：`X-Ctds-Subject: space-service` / `X-Ctds-Roles: space-internal`；
- DID 明文只在回环 / 集群内网传输，存证侧只落 SM3 摘要（3.8.2 口径）；日志不打印 DID。

## 6. 配置与部署

| 对象 | 改动 |
| --- | --- |
| 根 `pom.xml` | `<modules>` + `common/evidence-client`；`<dependencyManagement>` + `common-evidence-client` |
| `space-service/application.yml` | `ctds.evidence.client.*` + `ctds.space.did.base-url: ${CTDS_SPACE_DID_BASEURL:}` |
| `contract-service/application.yml` | `ctds.evidence.client.*` |
| `delivery-service/application.yml` | `ctds.evidence.client.*` |
| `did/application.yml` | `space-internal: did.internal.read` |
| `deploy/k8s/space-deployment.yaml` | `CTDS_EVIDENCE_BASEURL=http://ctds-evidence-service:8090`；`CTDS_SPACE_DID_BASEURL`（did 未在 k8s 清单内，按部署环境注入；未注入 = 空间授权存证跳过 + WARN，登记跟踪项） |
| `deploy/k8s/contract-deployment.yaml` / `delivery-deployment.yaml` | `CTDS_EVIDENCE_BASEURL=http://ctds-evidence-service:8090` |
| 迁移 | **零 Flyway 迁移**；**零表结构变更** |

## 7. 前置检查清单（编码段第一动作，逐项实测留痕）

| 序号 | 检查项 | 判定 |
| --- | --- | --- |
| 1 | 根 pom `<modules>` 实测 20 → 注册后 21；`dependencyManagement` 增条目 | 计数实测 |
| 2 | `common/logging` 自动装配范式（`AutoConfiguration.imports` + `@AutoConfiguration`） | 实读 |
| 3 | `spring-tx` 是否已在三服务类路径（starter-jdbc 传递） | `mvn dependency:tree` 实测 |
| 4 | `TransactionSynchronizationManager` afterCommit / rollback 行为 | 单测实测 |
| 5 | JDK `HttpClient` 范式（超时 / 身份头 / 错误解析） | 实读 `ContractInternalClient` |
| 6 | `ApiResult.code == "0"` 成功判定 | 实读 `ApiResult` |
| 7 | `JdbcClient` + `GeneratedKeyHolder` 取 `space_action_log.id` / `contract_usage_log.id` | 集成测试实测 |
| 8 | `contract_template.template_type` 读取路径（`ContractTemplateRepository.findByNo`） | 实读 + 测试 |
| 9 | `InternalContractSummaryViews.Summary` 消费方仅 delivery | grep 实测 |
| 10 | `did` 权限映射与 `CurrentDidView` 状态值（ACTIVE / REVOKED / PENDING_ISSUE / NONE） | 实读 + 桩测试 |
| 11 | 门禁 `coreModules` = 5 项、`gates-config.json` 零改动（ConfigSha256 基线） | 实读 |
| 12 | 基线复跑：space / contract / delivery / evidence 40 例 / gateway 47 例 / 前端四步 | 编码段记录 |

## 8. 测试锚点（T1~T26，映射规格 AC 与剧本步骤）

| 编号 | 断言要点 | 映射 |
| --- | --- | --- |
| T1 | 无事务调用 → 立即发送 1 次；请求体恰六键、无附加字段 | B1-1 / §1.3 |
| T2 | 事务内调用 → 提交前 0 请求、提交后 1 请求 | B2-1 / §1.4 |
| T3 | 事务回滚 → 0 请求 | B2-1 / §4 |
| T4 | 存证服务 500 / 网络异常 → 不抛出、WARN、业务返回不变 | B2-1 |
| T5 | `base-url` 空 / `enabled=false` → 0 请求、不抛出 | Q8 |
| T6 | 六字段缺一 / 非法值 → 存证侧 400（客户端不预校验，仅服务端守卫） | B1-1 |
| T7 | 请求体字符串扫描：不含数据原文、个人信息、密钥材料、DID 以外的自由文本 | B1-4 |
| T8 | afterCommit 回调内异常被捕获（业务响应不受影响） | §1.4 |
| T9 | 空间审批通过 → 1 条 `AUTHORIZATION/SPACE_MEMBER_ADMITTED`，`businessNo=SPACE{id}:ADMISSION{id}`、DID 正确 | B1-1 / S1-2 |
| T10 | 空间邀请确认 → 1 条同类事件；重复确认被状态门槛拦住、0 新事件 | B1-3 / S1-6 |
| T11 | 空间策略覆盖（首覆盖 / 再覆盖）→ 各 1 条 `SPACE_POLICY_INHERITED`，`businessNo` 互异（取自留痕 id） | B1-1 / S1-2 |
| T12 | 空间红线拒绝 / 同值幂等 → 0 事件 | Q10 / S1-5 |
| T13 | 空间 DID 服务不可达 / 主体无 ACTIVE DID → 业务成功、0 事件、WARN | Q6 / §5 |
| T14 | 空间存证服务不可达 → 业务成功（准入 / 覆盖均成功）、0 事件 | B2-1 / S2-4 |
| T15 | 公共数据授权类合约双签 → 2 条（`AUTHORIZATION/DATA_AUTH_CONTRACT_EFFECTIVE` + `CONTRACT/CONTRACT_EFFECTIVE`），同 `contractNo`、同 `occurredAt` | Q5 / S1-2·3 |
| T16 | 非公共数据授权类合约双签 → 1 条 `CONTRACT/CONTRACT_EFFECTIVE` | B1-2 / S1-3 |
| T17 | 合约首签 → 0 事件；重复签署 → 0 新事件 | Q10 / S1-6 |
| T18 | 使用放行（空策略分支 + 配额分支各一）→ 各 1 条 `USAGE/USAGE_ALLOWED`，`businessNo=USG...`、DID 正确 | B1-1 / S1-5 |
| T19 | 使用拒绝（状态失效 / 越界 / 配额耗尽）→ 0 事件；拒绝留痕仍存在 | Q10 / S1-5 |
| T20 | 合约 / 使用侧存证服务不可达 → 签署 / 放行仍成功 | B2-1 |
| T21 | 交付创建 → 1 条 `DELIVERY/DELIVERY_CREATED`，`businessNo=DL...`、`requesterDid` 来自合约摘要 | B1-3 / S1-4 |
| T22 | 交付幂等重放 → 仍 1 条（0 第二次调用） | B2-2 / S1-6 |
| T23 | 交付失败（判定拒绝 / 形态不符）→ 0 事件 | Q10 |
| T24 | evidence-service：六类操作类型各 1 例正向 200 + 六要素可读（闭合 DB-113 P3-4） | B1-2 / DB-113 |
| T25 | evidence-service：同一三元组重复 → `duplicate=true`、零新行（回归） | B2-2 |
| T26 | 端到端契约：以客户端同一份六键 JSON 投递内部写面 → 200 + 状态 / 编号可读 | B1-1 / §1.3 |

- 单元（`common/evidence-client`：afterCommit / 失败降级 / JSON）+ 集成（三服务 Testcontainers MySQL + JDK `HttpServer` 桩；每服务独立库）；
- 既有用例零回归：space / contract / delivery 原有用例（含桩）应保持全绿；evidence 40 例、gateway 47 例零回归；
- 行覆盖：`common/evidence-client` 自设 ≥80%；三服务沿用整体 70% 口径（门禁不变）。

## 9. 剧本映射、诚实边界

**剧本承载（C-8.1 V1.2 → V1.3）**

| 剧本步骤 | 本卡承载方式 |
| --- | --- |
| S1-1（基线） | 3.8.2 `GET /api/v1/evidence/overview`（不变） |
| S1-2（授权） | 真实业务：空间成员准入 / 策略生效 / 公共数据授权类合约生效（自动触发）；业务单据号按 Q2 |
| S1-3（合约） | 真实业务：合约双签生效（自动触发）；`contract_no` 逐字一致 |
| S1-4（交付） | 真实业务：交付创建（自动触发）；`delivery_no` 逐字一致 |
| S1-5（使用） | 真实业务：放行自动触发（`USG...`）；拒绝调用 0 事件；剧本括号说明按 Q3 改写 |
| S1-6（幂等） | 重复交付 / 重复放行 → 存证不增条（T10 / T22） |
| S1-7（白名单） | 请求体与存证载荷负向扫描（T7 + 3.8.2 T12） |
| S1-8（编号） | 3.8.2 读面（不变） |
| S2-1~S2-9 | 3.8.2 全承载（不变） |

**V1.3 修订点（仅操作路径与口径说明，业务判定与步骤编号一字不改）**

1. S1-2~S1-5 的"操作"列补"由业务系统自动触发"与对应业务入口（空间准入 / 策略覆盖 / 合约双签 / 交付创建 / 使用放行）；
2. S1-2 括号补"空间准入键 = `SPACE{空间id}:ADMISSION{准入单id}`（可反查 `space_action_log`）"；
3. S1-5 括号由"计量流水源单号"改为"使用放行留痕单号 `USG{12 位}`"（Q3）；
4. 演示前提补"evidence-service 8090 与 did-service 8082 已启动；未启动时业务仍成功但无存证（WARN）"。

**诚实边界**

1. **无 outbox / 无补偿队列**：调用侧不可达时该条存证丢失（仅 WARN），新登记跟踪项；不强称"零丢失"；
2. **DID 缺失即跳过**：空间 DID 服务未配置 / 主体无 ACTIVE DID、交付摘要无 `requesterDid` 时，不伪造主体标识，相应存证缺失可查；
3. **策略"继承"语义**：以"空间策略覆盖生效"承载，不是"空间启用时批量继承快照"（Q4）；
4. **公共数据授权类合约双写**：同一合约生效产生授权 + 合约两条，按"每环节每动作一条"解释 B1-1（Q5）；
5. **使用事件源**：以放行留痕为准，不是计量流水（Q3）；"不双计"仍由计量域结构保证（API 腿网关唯一源 / FILE 腿批量采集 / 拒绝零计量）；
6. **真链未接入**：本地不可变哈希链账本（DB-115），不得对外表述为"已对接真实区块链"；
7. **默认空地址 = 关闭**：部署未注入 `CTDS_EVIDENCE_BASEURL` 时全链路无存证（WARN），部署清单必须显式配置；
8. **不做拒绝存证**：Q10 口径，拒绝留痕仅在业务域与审计调阅（3.8.4）可见。

**勘误（编码段回填区，V0.9 为空）**

| 编号 | 设计原文 | 实现落点与理由 |
| --- | --- | --- |
| — | — | 编码段按实测回填（E1~En），实现与已确认本文件不一致 = 打回项（章程 2.6） |

## 10. 体量与拆分

- 预估 **1400~1800 行**（非文档口径；`common/evidence-client` 约 420 / 空间约 340 / 合约约 380 / 交付约 200 / 配置部署约 80 / evidence 测试补强约 80）；
- **D1 不拆分**：四环节同一验收链（剧本 S1 一次跑通），拆则中间态不可验收；超 400 行按服务包先例登记制豁免。
