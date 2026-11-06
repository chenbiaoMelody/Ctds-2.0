# WBS-3.8.2 存证服务与链对接 · 高保真设计（V0.9，待一次确认 → V1.0 即编码契约）

| 项 | 内容 |
| --- | --- |
| 低保真 | `docs/designs/WBS-3.8.2-lofi.md`（决策点 Q1~Q9 + D1） |
| 上游规格 | `docs/specs/C-8.1-8.2-存证与审计.md` **V1.1**（B1~B4；§2 术语"存证记录 / 内容摘要 / 上链 / 核验"） |
| 上游契约 | ADR-004（网关抽象 + 白名单五项）、ADR-005（ApiResult + 9 位错误码）、ADR-006/015（SM3 唯一入口 / KMS）、ADR-007（幂等）、ADR-009（迁移）、ADR-016 §2.7（回环绑定）、ADR-018（门禁） |
| 性质 | 非界面类；本文件 = 编码契约（端点 / 表 / 摘要 / 状态机 / 错误码 / 测试锚点） |
| 版本 | **V1.0（2026-11-05 编排师一次确认 = 编码契约即时生效）**：Q1~Q9 均采建议口径 A + D1 不拆分（确认留痕见任务卡 §二）；V0.9 = 立卡段草案 |

## 1. 端点表

### 1.1 内部写面（服务身份，不经统一网关）

| 方法 | 路径 | 权限 | 语义 |
| --- | --- | --- | --- |
| POST | `/api/v1/evidence/internal/records` | `evidence.internal.write` | 创建存证（幂等）；**恒 200**（上链失败也 200，`status=FAILED` 体现 fail-visible） |

请求体（全部必填）：

| 字段 | 规则 |
| --- | --- |
| `stageType` | 封闭枚举 `AUTHORIZATION` / `CONTRACT` / `DELIVERY` / `USAGE` |
| `businessNo` | `^[A-Za-z0-9_.:-]{6,64}$`（与上游单据号逐字一致，不做 trim / 大小写转换） |
| `operationType` | 封闭枚举，且必须与环节匹配：AUTHORIZATION → `SPACE_MEMBER_ADMITTED` / `SPACE_POLICY_INHERITED` / `DATA_AUTH_CONTRACT_EFFECTIVE`；CONTRACT → `CONTRACT_EFFECTIVE`；DELIVERY → `DELIVERY_CREATED`；USAGE → `USAGE_ALLOWED` |
| `subjectDid` | `^did:ctds:[A-Za-z0-9._:-]{6,96}$`（服务内算摘要后**不落原值**） |
| `occurredAt` | ISO-8601 带偏移（如 `2026-11-05T21:30:00+08:00`）；转 UTC 毫秒；拒绝 > 当前时间 +5 分钟或 < 2020-01-01 |
| `actorSubjectNo` | `^[A-Za-z0-9_.-]{6,32}$`（写留痕的操作人） |

响应体：

```json
{ "evidenceNo": "EV20261105000001", "stageType": "DELIVERY", "businessNo": "DL000031",
  "operationType": "DELIVERY_CREATED", "status": "ON_CHAIN", "chainTxId": "LTX000000000001",
  "duplicate": false, "occurredAt": "2026-11-05T13:30:00.000Z" }
```

### 1.2 对外读面（经统一网关 `connector-evidence`，权限 `evidence.governance`）

| 方法 | 路径 | 语义 | 主要失败 |
| --- | --- | --- | --- |
| GET | `/api/v1/evidence/records` | 分页清单：`stageType` / `businessNo` / `status` / `from` / `to` / `pageNum`（≤10000）/ `pageSize`（≤100） | 400 `1012C0002`（无任何过滤条件 / 时间窗 >90 天 / 分页越界） |
| GET | `/api/v1/evidence/records/{evidenceNo}` | 详情 + 状态流转与核验留痕（按时间正序） | 404 `1012C0001` |
| POST | `/api/v1/evidence/records/{evidenceNo}/verify` | 核验三态；**200** 返回结论（不一致与未上链都不是 HTTP 错误） | 404 `1012C0001`；503 `1012S0001`（已上链但链不可达，见 §10 E2） |
| POST | `/api/v1/evidence/records/{evidenceNo}/retry` | 重试上链 | 409 `1012C0003`（已上链）；503 `1012S0001`（仍不可达，记录保持 FAILED） |
| GET | `/api/v1/evidence/overview` | 计数：`onChainCount` / `pendingCount` / `failedCount` / `lastFailedAt` | — |

核验响应：

```json
{ "conclusion": "MISMATCH", "evidenceNo": "EV20261105000001", "businessNo": "DL000031",
  "status": "ON_CHAIN", "expectedDigest": "<重算 64hex>", "actualDigest": "<账本 64hex>",
  "localDigest": "<记录 64hex>", "chainTxId": "LTX000000000001",
  "verifiedBy": "S20260922000001", "verifiedAt": "2026-11-05T13:35:00.000Z" }
```

`conclusion` 值域：`PASS` / `MISMATCH` / `NOT_ON_CHAIN`；`NOT_ON_CHAIN` 时 `actualDigest=null` 且 `status` 为 `PENDING` 或 `FAILED`。

## 2. 数据模型（Flyway V1，`ctds_evidence`）

```sql
CREATE TABLE evidence_no_seq (
  seq_date CHAR(8) NOT NULL COMMENT 'yyyyMMdd',
  next_val INT NOT NULL COMMENT '当日已用最大序号',
  PRIMARY KEY (seq_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE evidence_record (
  id BIGINT NOT NULL AUTO_INCREMENT,
  evidence_no VARCHAR(32) NOT NULL,
  stage_type VARCHAR(24) NOT NULL,
  business_no VARCHAR(64) NOT NULL,
  operation_type VARCHAR(40) NOT NULL,
  subject_did_digest CHAR(64) NOT NULL COMMENT 'SM3(did)，不存 DID 原值',
  occurred_at DATETIME(3) NOT NULL COMMENT '业务事件时点（UTC）',
  content_digest CHAR(64) NOT NULL,
  status VARCHAR(16) NOT NULL COMMENT 'PENDING/ON_CHAIN/FAILED',
  chain_tx_id VARCHAR(64) NULL,
  chain_block_no BIGINT NULL,
  retry_count INT NOT NULL DEFAULT 0,
  last_error_summary VARCHAR(200) NULL COMMENT '原因摘要，不含内部实现与堆栈',
  created_at DATETIME(3) NOT NULL,
  updated_at DATETIME(3) NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_evidence_no (evidence_no),
  UNIQUE KEY uk_evidence_idem (stage_type, business_no, operation_type),
  KEY idx_evidence_status (status, occurred_at),
  KEY idx_evidence_business (business_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE evidence_record_action_log (
  id BIGINT NOT NULL AUTO_INCREMENT,
  evidence_no VARCHAR(32) NOT NULL,
  action VARCHAR(32) NOT NULL COMMENT 'CREATED/CHAIN_SUCCEEDED/CHAIN_FAILED/RETRY_SUCCEEDED/RETRY_FAILED/VERIFIED',
  from_status VARCHAR(16) NULL,
  to_status VARCHAR(16) NULL,
  actor_subject_no VARCHAR(32) NOT NULL,
  reason_code VARCHAR(16) NULL COMMENT '1012 段码值或 NONE',
  detail VARCHAR(512) NULL COMMENT '结论/原因摘要，禁原文与个人信息',
  created_at DATETIME(3) NOT NULL,
  PRIMARY KEY (id),
  KEY idx_log_evidence (evidence_no, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE evidence_chain_block (
  block_no BIGINT NOT NULL AUTO_INCREMENT,
  chain_tx_id VARCHAR(64) NOT NULL,
  payload_digest CHAR(64) NOT NULL,
  payload_json VARCHAR(512) NOT NULL COMMENT '白名单五项，固定键序',
  prev_block_hash CHAR(64) NOT NULL,
  block_hash CHAR(64) NOT NULL,
  created_at DATETIME(3) NOT NULL,
  PRIMARY KEY (block_no),
  UNIQUE KEY uk_chain_tx (chain_tx_id),
  UNIQUE KEY uk_chain_payload (payload_digest)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

- 取号（Q4-A）：`INSERT INTO evidence_no_seq(seq_date,next_val) VALUES(?,1) ON DUPLICATE KEY UPDATE next_val = LAST_INSERT_ID(next_val + 1)` → `SELECT LAST_INSERT_ID()`；首日插入时 `LAST_INSERT_ID()` 需按既有取号实现同款处理（编码段以 subject / contract 取号类为模板，hifi §7-3 实测）。
- **不可改删的结构性保证**：仓储端口（`EvidenceRepository`）只暴露 `insert` / `updateStatus` / `findByNo` / `findByIdempotentKey` / `page`（返回含 total 的切片）/ `countByStatus`，另加三个**只读 / 追加型**辅助方法 `appendAction` / `lastFailedAt` / `findActions`（编码段勘误 E1：留痕追加、概览最近失败时点、详情时间线所需；只增不减，**无 update 内容、无删除方法**）；`evidence_chain_block` 只有 insert 与按 txId / 载荷摘要查询。
- 列注释与迁移头沿 ADR-009 与既有 V1 迁移模板（charset / collation / 注释风格一致）。

## 3. 摘要规范化与上链载荷（封闭，可复算）

```
subjectDidDigest = SM3("CTDS-DID-1|" + subjectDid)

digestInput = "CTDS-EV-1|" + stageType + "|" + businessNo + "|" + operationType
            + "|" + occurredAtUtcIso + "|" + subjectDidDigest
contentDigest = SM3(digestInput)          // 64 位小写 hex，调用 common Sm3Service
```

- `occurredAtUtcIso` 固定形态 `yyyy-MM-dd'T'HH:mm:ss.SSS'Z'`（UTC、三位毫秒）；
- 字段间分隔符固定 `|`，UTF-8；字段值**不做 trim、不改大小写、不做 Unicode 归一**；
- 入参只有上表六个业务字段——**没有自由文本字段**，原文 / 个人信息 / 密钥材料结构性无法进入摘要；
- 不加盐（Q5-A）；盐方案与 KMS 依赖登记为演进项，本卡不引入。

上链载荷（本地账本与未来真链同构，固定键序、无空白）：

```json
{"businessNo":"DL000031","occurredAt":"2026-11-05T13:30:00.000Z","operationType":"DELIVERY_CREATED","subjectDidDigest":"<64hex>","contentDigest":"<64hex>"}
```

- 五项 = 内容摘要 + 四项元数据（业务单据号 / 时间戳 / 操作类型 / 主体 DID 摘要），与 ADR-004 §3-3 及规格 §2 术语逐字对齐；存证编号 / 环节类型 / 状态 / 交易标识**不入载荷**；
- 载荷由服务端按固定顺序手工拼装（不经 Jackson 动态序列化），保证 `payload_digest` 与 `payload_json` 可逐字节复算。

本地账本哈希链：

```
chainTxId   = "LTX" + 12 位 blockNo 补零
blockHash   = SM3(blockNo + "|" + payloadDigest + "|" + payloadJson + "|" + prevBlockHash + "|" + createdAtUtcIso)
prevBlockHash = 创世块 = 64 个 "0"；其后 = 上一条 block_hash
payloadDigest = contentDigest 原值
```

> `created_at` 由应用侧 Clock 生成后写入（不用数据库默认值），保证 `block_hash` 可复算；`Clock` 注入（沿策略域"注入时钟"教训）。

## 4. 状态机、事务与幂等

| 当前 | 事件 | 结果 | 留痕 action |
| --- | --- | --- | --- |
| — | 创建（首次） | `PENDING` → 同步 submit → `ON_CHAIN` / `FAILED` | `CREATED` +（`CHAIN_SUCCEEDED` / `CHAIN_FAILED`） |
| — | 创建（重复三元组） | 返回原记录，`duplicate=true`；**零新行、零留痕** | — |
| `PENDING` | 重试 | `ON_CHAIN` / 保持 `PENDING` 并转 `FAILED` | `RETRY_SUCCEEDED` / `RETRY_FAILED` |
| `FAILED` | 重试 | `ON_CHAIN`（`retry_count+1`）/ 保持 `FAILED`（`retry_count+1`，更新原因摘要） | `RETRY_SUCCEEDED` / `RETRY_FAILED` |
| `ON_CHAIN` | 重试 | 409 `1012C0003`，零副作用 | — |

- 事务边界：① 记录 insert（独立事务提交，保证"链失败也有记录"）；② 账本 insert（独立事务，`uk_chain_payload` 保证重放幂等）；③ 状态更新 + 留痕（同一事务）；三段分离 = 规格 B2-1 解耦与 fail-visible 的结构性承载；
- 崩溃窗口：②成功 / ③未执行 → 记录停在 `PENDING`，重试走 ②（唯一键命中 → 取回既有回执）→ ③，不产生第二份账本；
- **状态写入失败口径（P2-3 修复批定稿）**：`try` 只包 `gateway.submit`；② 成功后 ③ 写入失败**不回写 `FAILED`**（不伪造链结果），记录保持原状态并按 500 `1012S0002` 上抛；恢复路径 = 手动重试命中 `uk_chain_payload` 取回原回执后再推进 ③；
- 幂等：`uk_evidence_idem` 兜底并发（`DuplicateKeyException` → 回查返回既有记录，`duplicate=true`）；
- 重试与核验均不修改记录内容列（SQL 层只有 `status/chain_tx_id/chain_block_no/retry_count/last_error_summary/updated_at` 可更新）。

## 5. 错误码 1012 段（本卡定稿，Java + docs 双查零占用实测）

| 码值 | HTTP | 业务含义 | 对外文案（不暴露内部实现） |
| --- | --- | --- | --- |
| `1012C0001` | 404 | 存证记录不存在 | 存证记录不存在 |
| `1012C0002` | 400 | 存证参数不合法（环节 / 操作类型 / 单据号 / 时间 / 主体 / 分页 / 时间窗） | 请求参数不合法，请检查后重试 |
| `1012C0003` | 409 | 当前存证状态不允许该操作（如已上链再重试） | 当前存证状态不支持该操作 |
| `1012S0001` | 503 | 存证链不可达（上链 / 重试 / 取链上记录失败） | 存证链暂不可用，请稍后重试 |
| `1012S0002` | 500 | 存证取号或落库失败（非业务可修复） | 存证服务暂不可用，请稍后重试 |

- 类形态沿 `MeteringErrorCodes`：`ErrorCode` + `_MESSAGE` 常量成对，类头注释登记段位占用与空位；
- HTTP 映射集中在一个 `EvidenceExceptionHandler`（`@RestControllerAdvice`），响应经 `ApiResult` 统一信封；
- **不新增 1013 段**，1012 段内 C0004~C0009 / S0003~S0009 留空（类头登记）。

## 6. 适配器契约与实现

```java
public interface EvidenceGateway {
    ChainReceipt submit(ChainPayload payload);          // 不可达抛 EvidenceChainUnavailableException
    Optional<ChainEntry> findByTxId(String chainTxId);  // 不可达抛链异常（→503）；查无此块 = 空
}
```

- `ChainPayload` = `payloadJson` + `payloadDigest`（摘要即 contentDigest）；`ChainReceipt` = `chainTxId` + `blockNo`；
- `LocalLedgerEvidenceGateway`（`@Component`，`ctds.evidence.chain.provider=local`，`matchIfMissing=true`）：同库写 `evidence_chain_block`，`uk_chain_payload` 命中时返回既有回执（幂等）；
- 配置：
  - `ctds.evidence.chain.provider`（默认 `local`；未知值 → 启动 fail-fast）
  - `ctds.evidence.chain.enabled`（默认 `true`，env `CTDS_EVIDENCE_CHAIN_ENABLED`）——`false` 时 `submit` 一律抛"不可达"（**演示期构造链故障的唯一手段，无运行期开关**）
- Q1 若确认真链条件就绪：新增 `FiscoBcosEvidenceGateway`（`provider=fisco-bcos`）并附依赖审批留痕；未就绪则登记跟踪项，**不写假实现、不虚报**；
- 适配器层不出现业务判断；换链只改适配器与 provider 配置。

## 7. 前置检查清单（编码段第一动作，逐项实测留痕）

| 序号 | 检查项 | 判定 |
| --- | --- | --- |
| 1 | 根 pom `<modules>` 实测 19 个 → 注册后 20 个 | 计数实测 |
| 2 | 新服务脚手架模板（kms 五层包 + parent 2.0.0-SNAPSHOT + `relativePath=../../pom.xml` + 默认 profile 不启数据源） | 实读 kms |
| 3 | 取号实现模板（subject / contract 取号类）：`LAST_INSERT_ID` 首日行为 | 实读 + 单测 |
| 4 | `SharedMySqlContainer` 单例 + 每测试类独立库用法 | 实读既有测试 |
| 5 | 网关路由 fail-fast 约束（仅单 `Path=` 谓词 + `filters` 为空）与路由计数（实测 11 → 12） | 实读 `GatewayRouteConfig` |
| 6 | 权限点全景实测 **55 个**，`evidence.*` 权限点零占用 | grep 实测 |
| 7 | 1012 段 Java + docs 双查零占用 | grep 实测 |
| 8 | 错误码 handler 模板（`MeteringExceptionHandler` 形态） | 实读 |
| 9 | `@RequirePermission` + 内部服务身份头（`X-Ctds-Subject` / `X-Ctds-Roles`）写法 | 实读内部端点 |
| 10 | k8s deployment/service 模板 + `kustomization.yaml` 登记（实测 8 服务对 → 9） | 实读 |
| 11 | 门禁 `coreModules` = 5 项（**不含 evidence**）→ 本卡覆盖率口径 = 整体 70%（自设目标 ≥80%）；`gates-config.json` 零改动 | 实读 config |
| 12 | 前端零改动基线四步（test / typecheck / lint / build） | 复跑兜底 |

## 8. 部署与配置

- `services/evidence-service/src/main/resources/application.yml`：8080→`8090`、`server.address: 127.0.0.1`（ADR-016 §2.7）、`ctds.auth.permissions` 映射、`ctds.evidence.chain.*`；
- `application-mysql.yml`：`jdbc:mysql://localhost:3306/ctds_evidence`、口令 `${CTDS_DB_PASSWORD:}`（零入库）、Flyway 开启；
- 网关：`ctds.gateway.routes` 追加第 12 条 `connector-evidence`（`Path=/api/v1/evidence/**` → `${CTDS_EVIDENCE_BASEURL:http://127.0.0.1:8090}`，**不带 delivery-face**）；`deploy/k8s/gateway-deployment.yaml` 增 `CTDS_GATEWAY_EVIDENCE_BASEURL`；
- 部署清单：`deploy/k8s/evidence-deployment.yaml` + `evidence-service.yaml` + `kustomization.yaml` 两行；
- 数据库初始化：`ctds_evidence` 建库与最小授权（写：evidence 三表 + 取号表；读：同库），口令由人侧注入（红线 7）。

## 9. 测试锚点（T1~T16，映射规格 AC 与剧本步骤）

| 编号 | 断言要点 | 映射 |
| --- | --- | --- |
| T1 | 同三元组重复创建 → 1 行、`duplicate=true`、零新留痕 | B2-2 / S1-6 |
| T2 | 第五类环节 / 环节与操作类型错配 → 400 `1012C0002` | B1-2 / S1-2（值域封闭） |
| T3 | 摘要可复算：同输入同摘要；任一字段变化摘要必变（双向） | B1-1 / §2 术语 |
| T4 | 上链成功 → `ON_CHAIN` + `chain_tx_id` + 账本恰 1 行 + `prev_block_hash` 与上一条 `block_hash` 相等 | B1-1 / S2-1 |
| T5 | 链关闭 → 记录仍存在且 `FAILED` + 原因摘要可读（业务不回滚） | B2-1 / B4-1 / S2-4 |
| T6 | 重试成功 → `ON_CHAIN`，`CHAIN_FAILED` 与 `RETRY_SUCCEEDED` **两条留痕都在** | B4-3 / S2-7 |
| T7 | 核验三态：`PASS` / `MISMATCH`（改账本摘要后）/ `NOT_ON_CHAIN`（失败态） | B3-1~B3-3 / S2-1·3·5 |
| T8 | 核验留痕：核验人 / 时点 / 对象 / 结论落库且核验前后记录与业务数据逐字一致 | B3-3 / B3-4 / S2-8 |
| T9 | 无修改 / 删除 / 手工填 txId 入口（架构断言：仓储端口方法清单 + 端点表核对） | B2-3 / B4-4 / S2-9 |
| T10 | 非治理档 → 403；不存在编号 → 404 同码同文 | B5-4 口径 / S2-9 |
| T11 | 异常概览计数与记录状态逐项一致，`lastFailedAt` 可读 | B4-2 / S2-6 |
| T12 | 载荷仅五项：无 DID 原值、无原文、无密钥材料（负向字符串扫描） | B1-4 / S1-7 |
| T13 | 编号唯一 + 当日序列递增 + 不回收（同日 3 条编号互异） | B1-1 / S1-8 |
| T14 | 边界：`businessNo` 5/6/64/65 位、非法字符；`occurredAt` 未来 +6 分钟拒绝；`pageSize=101` 拒绝；无过滤条件拒绝 | B1-1 / B6-2 口径 |
| T15 | 崩溃窗口重放：账本已写、状态未更 → 重试命中 `uk_chain_payload` 返回原回执，不产生第二块 | B2-2 / B2-3 |
| T16 | 卸载/禁用适配器后 `submit` 抛不可达且被映射为 `FAILED`（不吞异常、不 5xx 到业务方） | B4-1 |
| T17 | 幂等唯一键兜底：预检漏过 + `insert` 冲突 → 回查原记录；1 行 / 1 块 / 2 条留痕 / `duplicate=true` + 同编号同 txId | B2-2 |
| T18 | 核验遇链不可达：本地 `ON_CHAIN` + 链关闭 → 503 `1012S0001` + 常量文案 + 记录不变 + 无 `VERIFIED` 留痕 | B3-1 / B4-1 / §10 E2 |
| T19 | 状态写入失败：链成功 + ③ 失败 → 500 `1012S0002` + 记录保持原状态 + 无 `CHAIN_FAILED` 留痕 + 账本 1 块 | B2-1 / §4 |

> T17~T19 为 2026-11-05 评审循环 1 修复批补入的实现锚点（对应 `EvidenceIdempotencyRaceIntegrationTest` / `EvidenceVerifyChainUnavailableIntegrationTest` / `EvidenceStateWriteFailureIntegrationTest`）；T1~T16 编号与口径不变。

- 单元（适配器 / 摘要 / 取号 / 状态机）+ 集成（Testcontainers MySQL，每类独立库）；集成测试禁用真链，全部走本地适配器；
- 行覆盖自设目标 ≥80%（门禁整体 70%，`coreModules` 不含 evidence，配置零改动）。

## 10. 剧本映射、诚实边界与勘误

**剧本承载（C-8.1 V1.1 → 本卡）**

| 剧本步骤 | 本卡承载方式 |
| --- | --- |
| S1-1~S1-5（四环节真业务触发） | **不承载**（归 3.8.3）；本卡以内部写面构造四类环节记录，验证 B1-1/B1-3/B1-4 结构性（单据号逐字透传） |
| S1-6（重复触发不增条） | 内部写面直调（T1） |
| S1-7（白名单负向） | 详情读面 + 载荷字符串扫描（T12） |
| S1-8（编号唯一与六要素） | 清单 / 详情读面（T13） |
| S2-1~S2-9（核验 / 构造 / 重试 / 概览 / 越权） | 全部承载（接口路径）；S2-2 与 S2-4 构造口径见 lofi §3.4 |

**诚实边界**

1. Q1 采 A 时，本卡不声称"真实区块链已对接"；演示链 = 平台内不可变哈希链账本（append-only + 前序哈希），真实节点连通归部署期（跟踪项登记）；
2. "待上链"为崩溃窗口态，正常演示不可构造，剧本按 S2-5 允许的说明口径记录；
3. 自建链公信力边界不变（ADR-004 §4-1，V1.5 增强）；
4. 本卡不实现 B5~B8（3.8.4），overview 仅为最小只读计数承载。

**勘误（编码段回填区，2026-11-05）**：

| 编号 | 设计原文 | 实现落点与理由 |
| --- | --- | --- |
| E1 | §2 仓储端口"只暴露 insert / updateStatus / findByNo / findByIdempotentKey / page / countByStatus" | 补 `appendAction`（留痕追加）/ `lastFailedAt`（overview 最近失败时点）/ `findActions`（详情时间线）三个只读或追加方法，`page` 返回 `EvidencePage(list,total)`。**不新增任何改内容或删除方法**；T9 以源码断言承载（端口无 delete/remove/purge、状态 UPDATE 只碰六列、账本实现无 UPDATE / DELETE、接口层无 PUT/PATCH/DELETE） |
| E2 | §6"`enabled=false` 时 submit 一律抛不可达" | 实现同时让 `findByTxId` 抛不可达。**核验口径（P1-1 / P2-4 修复批定稿）**：仅当记录本地状态为 `ON_CHAIN` 时才查链——查链失败 → 503 `1012S0001`（`EvidenceExceptionHandler` 统一映射），且**不留 `VERIFIED` 留痕、记录保持不变**；本地非 `ON_CHAIN`（`PENDING` / `FAILED`）直接 `NOT_ON_CHAIN`，不查链（链关闭不影响该结论，与 `verifyFailedRecordReportsNotOnChain` 一致）；**账本可达但查无此块**（`findByTxId` 返回空，即账本缺块）→ `MISMATCH`（`actualDigest` 为空），不判 503（循环 2 复审 P3-1 澄清） |
| E3 | §2 取号样例 `INSERT ... VALUES(?,1)` + Last insert id | `seq_date` 为 `CHAR(8)`（yyyyMMdd），实现按**字符串**绑定业务日；若绑定 `java.sql.Date` 会被 MySQL 隐式转为 `yyyy-MM-dd`（10 字符）触发 "Data too long"（编码段实测） |
| E4 | §4 事务边界①"记录 insert（独立事务）" | 实现为 **record insert + CREATED 留痕同一事务**（`EvidenceRegistrationWriter`），保证留痕无空窗；账本写入（②）与状态 + 流转留痕（③）仍为独立事务 |
| E5 | §1.1 `actorSubjectNo` 6~32 位 | 治理动作（核验 / 重试）的操作人同样走该形态校验后落 `evidence_record_action_log.actor_subject_no`（列长 32），非法身份 → 1012C0002 |

**设计-实现一致性承诺**：实现与已确认本文件不一致 = 打回项（章程 2.6）。
