# WBS-3.6.5 高保真设计：适配器-政务共享交换平台（V1.0，2026-10-27）

| 字段 | 内容 |
| --- | --- |
| 版本 | **V1.0（✅ 2026-10-27 编排师一次确认 = Q1~Q8 全采 A + D1 不拆分）——编码契约即时生效**；与 lofi 一并提交、一次确认（章程 2.6.3） |
| 契约来源 | lofi Q1~Q8 建议口径 A + D1 不拆分；规格 `docs/specs/C-6.1-6.3-接入连接器.md` V1.0 行为 11 / 12 / 13 / 14 + §7 Q7-A |
| 交付物 | ① `connector-sdk` 新增 `adapter/gov` 包（政务适配器实现，随 SDK 分发）；② `services/connector-service` 增量：迁移 `V3`（2 表）+ **6 端点**（对外 5 + 内部 1）+ 四层实现 + 测试；③ **ADR-029 连接器源适配契约**（编码段落盘）；④ 本文件 + lofi + 任务卡 |

---

## §1 模块装配契约（Q1-A / Q2-A：零新模块 / 零新第三方）

**模块与依赖（加性，均为既有构件）**

| 位置 | 改动 |
| --- | --- |
| `connector-sdk/pom.xml` | **零改动**（`spring-web`〔RestClient〕/ `jackson-databind` 已在；测试侧 `spring-boot-starter-test` 已在） |
| `services/connector-service/pom.xml` | **+2 依赖**：`com.ctds:connector-sdk`（模块，取 SPI 与政务实现）、`com.ctds:common-crypto`（模块，凭据国密）——根 pom `dependencyManagement` 已覆盖两坐标，**零新第三方坐标** |
| 根 `pom.xml` | **零改动**（无新模块） |
| `gates-config.json` / 门禁脚本 | **零改动**（不列举模块，新增代码默认纳入全仓 compile / unitTest / coverage / sast） |
| `deploy/**` | **零改动**（模拟源为进程内形态，不新增容器 / k8s 清单） |
| 网关与路由 | **零改动**（本卡端点服务直连；网关扩路由归 3.6.8） |

**`services/connector-service/src/main/resources/application.yml` 加性 3 处**

```yaml
ctds:
  auth:
    permissions:
      applicant: connector.register,connector.read,connector.heartbeat,connector.upgrade,connector.cancel,connector.source.manage
      admin: connector.review,connector.read,connector.internal.read,connector.upgrade.confirm,connector.cancel
      connector-internal: connector.internal.read,connector.internal.write
  crypto:
    local:
      key-file: ${CTDS_SM4_KEY_FILE:}      # 凭据国密唯一入口（ADR-006；未配置 = 登记/自检 fail-closed）
  connector:
    source:
      credential-key-ref: ${CTDS_CONNECTOR_SOURCE_KEY_REF:connector-source-credential}
      adapter-timeout: ${CTDS_CONNECTOR_SOURCE_ADAPTER_TIMEOUT:3s}
```

> 说明：`admin` / `connector-internal` 两行**零变更**（运营方读源走既有 `connector.read`）；`applicant` 行仅追加 1 个权限点。

## §2 库表结构（`V3__create_connector_source_tables.sql`）

**`connector_source`**（源接入登记，行为 12-1）

| 列 | 类型 | 说明 |
| --- | --- | --- |
| `id` | BIGINT NOT NULL AUTO_INCREMENT | 技术主键 |
| `source_no` | VARCHAR(16) NOT NULL | **`SR` + 6 位**源编号（登记即签发，终身不复用） |
| `connector_no` | VARCHAR(16) NOT NULL | 所属连接器编号（`CN` + 6 位） |
| `subject_no` | VARCHAR(32) NOT NULL | 所属主体快照（登记时属主，服务端从鉴权上下文取） |
| `source_type` | VARCHAR(24) NOT NULL | `GOV_EXCHANGE` / `DATABASE` / `OBJECT_STORAGE` / `API_GATEWAY` |
| `endpoint` | VARCHAR(512) NOT NULL | 源地址（`mock://` 仅政务源；生产形态 `http(s)://`） |
| `credential_ref` | VARCHAR(48) NOT NULL | 凭据**不透明引用**（`SC` + 32 位 UUID 去连字符，对外唯一凭据标识） |
| `credential_ciphertext` | VARCHAR(2048) NOT NULL | **SM4 信封密文**（`Sm4Service.encryptText`；明文永不落库） |
| `credential_masked` | VARCHAR(32) NOT NULL | 凭据**掩码**（展示 / 留痕唯一形态；见 §7 掩码规则） |
| `created_at` | DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP | 登记时间 |
| `updated_at` | DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP | 最近变更时间 |

索引：`UNIQUE uk_source_no(source_no)` / `UNIQUE uk_connector_type_endpoint(connector_no, source_type, endpoint(128))`（同连接器同类型同地址防重登记）/ `KEY idx_source_connector(connector_no, created_at)`。
**零外键**（跨表只逻辑引用，沿 ADR-009 与 3.6.3 口径）。

**`connector_source_no_seq`**（单行取号，沿 `connector_no_seq` 先例）

```sql
CREATE TABLE connector_source_no_seq (
    id BIGINT NOT NULL, next_no BIGINT NOT NULL, PRIMARY KEY (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '连接器源编号全局序号（1 行原子取号）';
INSERT INTO connector_source_no_seq (id, next_no) VALUES (1, 1);
```

取号：`UPDATE connector_source_no_seq SET next_no = LAST_INSERT_ID(next_no + 1) WHERE id = 1` → `SELECT LAST_INSERT_ID()` → `String.format("SR%06d", n)`。

**自检结论不建表**：自检 / 读取结论落 `connector_action_log`（`action` + `result` + `reason_code` + `detail`），详情读面按 `connector_no + source_no` 取**最近一条** `source.check` 派生（无 N+1：清单不带自检结论、详情 1 次查询）。理由：零新表即可满足行为 13-2"失败留痕"与剧本"自检留痕"，且避免与动作留痕形成第二事实源。

## §3 包结构与类清单

**`connector-sdk`（新增 `adapter/gov`，5 类）**

```
com.ctds.connector.sdk.adapter.gov
├── GovExchangeSourceAdapter.java   # public：implements SourceAdapter（type()=GOV_EXCHANGE；check / read 编排）
├── CredentialResolver.java         # public：@FunctionalInterface String resolve(String credentialRef)
├── GovExchangeMessage.java         # 包级私有：标准请求 / 响应报文 record（sourceNo / endpoint / contractNo / purpose / rows / code / message）
├── GovExchangeClient.java          # 包级私有：mock:// → MockGovExchangeSource；http(s):// → RestClient（超时来自构造参数）
└── MockGovExchangeSource.java      # 包级私有：V1.0 标准报文模拟源（演示期承载；见 §7 路径约定）
```

> **`CredentialResolver` 是 SDK 新增的对外面**（平台注入解密能力，明文只在内存流转）→ 编码段在 **ADR-026 补记**（§7 消费方 + §9 演进），不改变既有 7 个 SPI 类型签名。

**`services/connector-service`（+10 主代码类 / 改 3 类）**

```
com.ctds.connector
├── interfaces/
│   ├── ConnectorSourceController.java          # 新：#1 登记 / #2 变更 / #3 清单 / #4 详情 / #5 自检
│   ├── InternalConnectorSourceController.java  # 新：#6 内部读取
│   └── dto/ConnectorSourceViews.java           # 新：RegisterSourceRequest / AmendSourceRequest / ReadRequest
│                                               #     / SourceView / SourceCheckView / SourceReadView
├── application/
│   ├── ConnectorSourceService.java             # 新：登记 / 变更 / 清单 / 详情 / 自检编排
│   └── ConnectorSourceReadService.java         # 新：读取编排（前置四校验 + 适配器调用 + 留痕）
├── domain/
│   ├── ConnectorSource.java                    # 新：record（11 字段，密文不对外）
│   ├── ConnectorSourceRepository.java          # 新端口：nextSourceNo / insert / update / findBySourceNo
│   │                                           #           / listByConnector / countByConnector / existsDuplicate
│   ├── ConnectorErrorCodes.java                # 改：+1010C0011 / +1010S0004 与两文案
│   └── ConnectorActionLog.java                 # 零改动（复用既有留痕结构）
├── audit/
│   ├── ConnectorAuditActions.java              # 改：+5 动作常量（见 §7）
│   └── ConnectorAuditService.java              # 改：+failure(...)（`FAILURE` 结论；既有 success / denied 零改）
└── infrastructure/
    ├── JdbcConnectorSourceRepository.java      # 新：手写 SQL（沿 3.6.3 仓储形态）
    ├── SourceAdapterRegistry.java              # 新：按 SourceType 索引适配器（未注册 → empty）
    └── CredentialCipher.java                   # 新：Sm4Service 封装（encrypt / decrypt / mask；密钥不可用 → 1010S0004）
```

**改既有文件清点（3 个主代码 + yml + 测试基类；均为增量，零端点语义改动）**：`ConnectorErrorCodes`（+2 码 +2 文案）、`ConnectorAuditActions`（+5 常量）、`ConnectorAuditService`（+1 方法）；另 `application.yml` +3 处、`pom.xml` +2 依赖、`ConnectorTestSupport`（清理 2 表 + 辅助方法）。**3.6.3 / 3.6.4 的 6 个控制器与 21 端点零改动**。

## §4 关键接口签名（`interfaces/dto/ConnectorSourceViews.java`）

```java
public record RegisterSourceRequest(
        @NotNull SourceType sourceType,            // 复用 SDK 枚举（domain 不复制）
        @NotBlank @Size(max = 512) String endpoint,
        @NotBlank @Size(max = 256) @JsonProperty(access = WRITE_ONLY) String credential) {}

public record AmendSourceRequest(
        @NotBlank @Size(max = 512) String endpoint,
        @Size(max = 256) @JsonProperty(access = WRITE_ONLY) String credential) {}   // 空 = 凭据不变

public record ReadRequest(
        @NotBlank @Size(max = 64) String contractNo,
        @Size(max = 128) String purpose) {}

public record SourceView(String sourceNo, String connectorNo, String sourceType, String endpoint,
        String credentialRef, String credentialMasked, String createdAt, String updatedAt,
        CheckSummary lastCheck) {}                  // lastCheck 可空

public record CheckSummary(boolean passed, String failureType, String message, String checkedAt) {}

public record SourceCheckView(String sourceNo, boolean passed, String failureType, String message,
        String checkedAt) {}

public record SourceReadView(String sourceNo, String connectorNo, String contractNo, boolean success,
        String summary, String readAt) {}
```

> `credential` 只在请求出现（`WRITE_ONLY`，响应永不回显）；`SourceView` **不含密文与明文**。`toString` 不覆盖 `credential` 字段的请求体不入日志（控制器不打印请求体）。

## §5 端点契约表（6 个：对外 5 + 内部 1）

| # | 方法 / 路径 | 权限点 | 说明 | 主要错误 |
| --- | --- | --- | --- | --- |
| 1 | `POST /api/v1/connectors/{connectorNo}/sources` | `connector.source.manage` | 源接入登记（四类源均受理） | 400 `1010C0001`（类型 / 地址 / 凭据非法，或重复登记）/ `1010C0003`（非属主 / 连接器不存在）/ `1010C0004`（连接器非注册态）/ 503 `1010S0004` |
| 2 | `PUT /api/v1/connectors/{connectorNo}/sources/{sourceNo}` | `connector.source.manage` | 变更源地址 / 凭据（留痕旧新；凭据掩码） | 400 `1010C0001` / `1010C0003`（源不存在或跨归属，同码同文）/ `1010C0004` / 503 `1010S0004` |
| 3 | `GET /api/v1/connectors/{connectorNo}/sources` | `connector.read` | 源清单（分页，属主或运营方；不含自检结论） | 400 `1010C0001`（分页越界）/ `1010C0003` |
| 4 | `GET /api/v1/connectors/{connectorNo}/sources/{sourceNo}` | `connector.read` | 源详情（含最近一次自检结论，可空） | 400 `1010C0003`（源不存在或跨归属） |
| 5 | `POST /api/v1/connectors/{connectorNo}/sources/{sourceNo}/checks` | `connector.source.manage` | 连通性自检（只读；**200 业务结论**） | 400 `1010C0004`（连接器非注册态）/ `1010C0003` / `1010C0011`（类型未开放）/ 502（适配器非预期异常，中性文案） |
| 6 | `POST /api/v1/connectors/internal/{connectorNo}/sources/{sourceNo}/reads` | `connector.internal.read` | **内部读取**（仅判定放行后调用；返回摘要） | 400 `1010C0001`（`contractNo` 缺失或越界）/ `1010C0004`（连接器非注册态）/ `1010C0003`（源不存在 / 跨归属）/ `1010C0011`（类型未开放） |

> 路径约定沿既有面：业务面 `/api/v1/connectors/{connectorNo}/...`、内部面 `/api/v1/connectors/internal/...`（与占用内部面同前缀，`{connectorNo}` 为单段、`internal` 为具体段，无路由冲突）。

## §6 错误码表（1010 段增量，Q7-A）

| 码 | 类型 | 文案 | 使用面 |
| --- | --- | --- | --- |
| `1010C0011` | 400 | 该源类型的适配承载尚未开放 | 自检 / 读取遇到未注册适配器的源类型（3.6.6 承接） |
| `1010S0004` | 503 | 源凭据保护组件不可用，暂不能登记或自检 | `common-crypto` 密钥不可用（未配密钥文件 / KMS 不可达）；**fail-closed，禁止明文降级** |

复用（零新增）：`1010C0001` 参数不合法（源类型非法 / 地址或凭据越界 / 重复登记）、`1010C0003` 源不存在或无权限（防枚举同码同文）、`1010C0004` 连接器状态不允许。
`1010S0003` 保持 3.6.4 的"段位预留不启用"口径（**零回改 ADR-028**）。

## §7 关键流程与适配器口径

**① 登记（`@Transactional`：取号 + 插入 + 留痕同一事务外的留痕单点）**

`鉴权 → 连接器存在且属主且 REGISTERED → 参数校验（类型枚举 / endpoint 1~512 / credential 1~256；mock:// 仅 GOV_EXCHANGE）→ 重复校验（同连接器+类型+地址）→ CredentialCipher.encrypt（密钥不可用 → 503）→ 取号 SR → 插入 → 留痕 source.register（detail = sourceNo / type / endpoint / 掩码）`。

**② 变更**：`归类校验 → 源归属（sourceNo 必须属于路径连接器，否则 1010C0003）→ endpoint 变更 / credential 可选变更 → 更新 → 留痕 source.amend（detail = 旧 endpoint → 新 endpoint；凭据仅记"已变更 + 掩码"）`。

**③ 自检（无 DB 事务）**

`鉴权 → 连接器 + 属主 + REGISTERED → 源归属 → registry.find(type)（空 → 1010C0011）→ 解密（密钥不可用 → 1010S0004）→ 构造 SourceRegistration(sourceType, endpoint, credentialRef, connectorNo) → adapter.check(registration)（超时 adapter-timeout）→ 留痕（success=true → source.check SUCCESS；false → FAILURE + failureType）→ 返回 200 SourceCheckView`。
非预期异常（适配器内部 / 报文解析失败）→ `502` 中性文案「源连通性探测失败，请稍后重试」+ 留痕 `FAILURE`（不暴露内部；不归为三类业务原因）。

**④ 内部读取（无 DB 事务）**

`服务身份 → 连接器 REGISTERED（否则 1010C0004）→ 源归属（否则 1010C0003）→ registry.find（否则 1010C0011）→ 解密 → adapter.read(registration, SourceReadRequest(contractNo, purpose)) → 留痕 source.read（detail 五要素：operator / connectorNo / sourceNo / contractNo / success + summary；**不含数据本体**）→ 200 SourceReadView`。
适配器返回 `success=false` → **200 业务结论**（读取失败是业务结论，不是接口错误）；**调用前置拒绝时适配器零调用**（可证伪断言见 §9 T8）。
**判定次序**：本端点契约明写"仅使用控制判定放行后调用"，次序与"拒绝不触达源"由交付链（3.6.8）保证；**本卡不做合约态校验、不调判定端点**（ADR-019/020 单点权威）。

**⑤ 政务适配器与模拟源路径约定（Q2-A，测试与演示共用）**

| endpoint | `check()` 结论 | `read()` 结论 |
| --- | --- | --- |
| `mock://gov-exchange/ok` | 通过 | 成功（摘要：模拟交换报文 1 件 / 不含本体） |
| `mock://gov-exchange/bad-credential` | 失败 `INVALID_CREDENTIAL` | 失败（摘要含"凭据无效"） |
| `mock://gov-exchange/unreachable` | 失败 `NETWORK_UNREACHABLE` | 失败（摘要含"网络不可达"） |
| `mock://gov-exchange/forbidden` | 失败 `INSUFFICIENT_PERMISSION` | 失败（摘要含"权限不足"） |
| 其它 `mock://gov-exchange/**` | 通过（视为正常源） | 成功 |
| `http(s)://...` | 真实 HTTP：连接异常 → `NETWORK_UNREACHABLE`；401 → `INVALID_CREDENTIAL`；403 → `INSUFFICIENT_PERMISSION`；2xx → 通过；其它 → 502 非预期 | 真实 HTTP 取数；失败 → `success=false` + 可读摘要 |

> 模拟源口径（诚实声明）：**进程内标准报文模拟器**，仅 `mock://` scheme 生效；`http(s)://` 走真实客户端但 V1.0 **未与真实政务平台联调**，映射规则为本地约定（ADR-029 登记）。界面 / 文档一律标注"模拟"，不虚称已对接真实政务平台。

**⑥ 掩码规则**：凭据明文长度 ≤4 → 全部 `*`（4 个）；>4 → `****" + 末 4 位`。掩码串落库并随留痕输出；**任何日志 / 异常 / 响应不得出现明文**。

**⑦ 审计动作常量（+5）**：`source.register` / `source.amend` / `source.check` / `source.read` / `source.denied`。

## §8 跨域装配改动

**零出站跨域调用**：本卡不新增任何对 subject / did / catalog / contract / delivery 的调用（连接器状态与归属均本地判定）⇒ **无新 `1010S` 出站码**，`ConnectorProperties` 零新增跨域字段。
**入站装配 1 处**：`SourceAdapterRegistry` 由服务内 `@Configuration` 注册（本期仅 `GovExchangeSourceAdapter`），未注册类型 → `Optional.empty()`（对应 `1010C0011`）。
**SDK → 服务方向**：`connector-service` 依赖 `connector-sdk`（库依赖，非服务调用）；SDK 不感知 Spring、不反向依赖任何服务模块。

## §9 测试清单（测试先行；映射规格验收标准）

| 类 | 用例要点 | 映射 |
| --- | --- | --- |
| `GovExchangeSourceAdapterTest`（SDK 单元） | 五类 mock 路径 check 结论；read 摘要不含本体；`toString` / 异常不含凭据 | 行为 13 验收 1~4 / 行为 14 验收 1 |
| `SourceRegistrationMaskingTest`（SDK 单元） | `SourceRegistration.toString()` 对 `credentialRef` 掩码；模型无明文凭据字段 | 行为 12 规则 2 |
| `ConnectorSourceRegistrationApiTest`（T1） | 四类源登记均受理；`credentialMasked` 回显、响应无明文；**DB 直查密文列 ≠ 明文且不出现明文子串**；留痕齐备；重复登记 → `1010C0001` | 行为 11 验收 1 / 行为 12 验收 1 |
| `ConnectorSourceOwnershipApiTest`（T2） | 非属主登记 / 详情 → `1010C0003` 同码同文；跨连接器 `sourceNo` → `1010C0003`；运营方可读清单 | 行为 12 验收 3 |
| `ConnectorSourceAmendApiTest`（T3） | 变更后留痕含旧 / 新 endpoint；凭据仍掩码；变更后自检可用 | 行为 12 验收 2 |
| `ConnectorSourceCheckApiTest`（T4） | 政务 mock ok 通过；三类失败分别对应枚举（`INVALID_CREDENTIAL` / `NETWORK_UNREACHABLE` / `INSUFFICIENT_PERMISSION`）；自检前后模拟源"源端数据变更计数"= 0；留痕 SUCCESS / FAILURE | 行为 13 验收 1~4 |
| `ConnectorSourceCheckUnavailableApiTest`（T5） | DATABASE 登记 200、自检 → `1010C0011`；读取 → `1010C0011` | 行为 11 规则 1 / 规则 3（诚实边界） |
| `ConnectorSourceReadApiTest`（T6） | 合规读取成功 + 摘要不含本体 + 留痕五要素；未登记 `sourceNo` → `1010C0003`；连接器注销后读取 → `1010C0004`（源档案仍可查） | 行为 14 验收 1 / 3 / 行为 11 规则 2 |
| `ConnectorSourceKeyUnavailableApiTest`（T7） | 密钥不可用（覆盖 `KeyProvider` 抛 `KEY_UNAVAILABLE`）→ 登记 / 自检 503 `1010S0004`；**DB 无明文残留** | 行为 12 规则 2（fail-closed） |
| `ConnectorSourceNoTouchTest`（T8） | 前置拒绝四情形（未登记 / 跨归属 / 注销 / 类型未开放）→ 适配器 `check` / `read` 调用次数 = 0 | 行为 14 规则 1（可证伪部分） |

测试基建：`SharedMySqlContainer`（既有）+ `ConnectorTestSupport` 扩展（`@BeforeEach` 增清 2 表 + `registerSource` 辅助）；密钥 = `@TempDir` 生成随机 16 字节 Base64 的 `*.keys`（沿 subject / delivery 测试先例，**不入库、不打印**）；**不新增第三方测试依赖、不新增桩服务**（模拟源在 SDK 内进程内实现）。

## §10 ADR-029 大纲（编码段落盘，七字段齐备 = 门禁 `adrFieldsCheck`）

1. **背景**：规格行为 11~14 与 ADR-026 SPI 契约需要落点；政务源 V1.0 以标准报文模拟源承载。
2. **决策**：适配器实现落 `connector-sdk/adapter/gov`（随连接器分发）；源登记 / 凭据保护 / 自检 / 读放在 `connector-service`；新表 2 个、端点 6 个、权限点 1 个、1010 段 +2 码；`CredentialResolver` 作为 SDK 新增面（ADR-026 补记）；"先判定后取数"次序归 3.6.8。
3. **备选**：独立适配器模块 / 实现入服务 / 落 std-adapter（各自不采理由见 lofi Q1）。
4. **理由**：与规格"随连接器分发"一致、零新模块与零新第三方、3.6.6 可平移复用。
5. **业务影响说明**：四类源可登记；政务源自检与读取可演示（模拟源，如实标注）；凭据只显掩码；未开放类型明确拒绝。
6. **影响范围**：代码（SDK 5 类 / 服务 10 类 + 改 3 类）/ 数据（2 表）/ 错误码（2 个）/ 权限点（1 个）/ 依赖（2 个模块依赖）/ 部署（零变更）/ 文档（ADR-026 补记 + 本三件）。
7. **可替换性**：适配器按 `SourceType` 注册可替换 / 真实政务平台对接 = 替换 `GovExchangeClient` 的 http 实现 + 关闭 mock scheme（零改服务层）；凭据存储可由 KMS 托管替换（`common-crypto` 既有覆盖机制）。

## §11 前置检查项（编码段第一动作，逐项实测留痕）

1. **分支与工作区**：`feat/WBS-3.6.5-适配器-政务共享交换平台` 梢 = 立卡批提交；`main` 与 origin 差异符合预期。
2. **`V3` 未占用**：`services/connector-service/src/main/resources/db/migration` 仅 `V1` / `V2`。
3. **`SR` / `SC` 前缀与 `connector_source*` 表名全仓零冲突**（Java + SQL + docs 检索）。
4. **SDK 依赖可解析**：`mvn -B -ntp -pl services/connector-service -am compile`（改 pom 后）BUILD SUCCESS；`connector-sdk` 无 Spring 运行时依赖泄漏。
5. **`common-crypto` 在 connector-service 可装配**：`ctds.crypto.local.key-file` 有值 → `Sm4Service` 可加解密；无值 → `1010S0004` 路径可触发（T7）。
6. **`GOV_EXCHANGE` 适配器唯一实现**：`SourceAdapter` 实现类全仓仅 1 个（本卡），无第二实现。
7. **mock scheme 约定可达**：五类路径 §7-⑤ 与测试断言逐一对齐；`http(s)://` 分支不发起真实外网调用（单测零外网）。
8. **1010 段下一可用码**：仍为 `1010C0010` / `1010S0003`（本卡取 `1010C0011` / `1010S0004`，留 `1010C0010` 供 3.6.6 使用）。
9. **测试基建基线**：connector-service **113 例全绿**、checkstyle 0；前端 / 其他服务零改动。
10. **演示准备**：`CTDS_SM4_KEY_FILE` 由人侧提供（口令与密钥不由 AI 承接）；政务演示源地址 = `mock://gov-exchange/ok`。

## §12 配置项清单

| 配置键 | 默认 | 说明 | 启动校验 |
| --- | --- | --- | --- |
| `ctds.crypto.local.key-file` | 空（环境变量 `CTDS_SM4_KEY_FILE`） | 凭据国密密钥文件（`*.keys`，gitignored） | 空 = 允许启动（WARN），登记 / 自检 fail-closed 503 |
| `ctds.connector.source.credential-key-ref` | `connector-source-credential` | 凭据加密 keyRef（KMS 可覆盖） | 非空；字符集沿 `common-crypto` 规则 |
| `ctds.connector.source.adapter-timeout` | `3s` | 适配器 `check` / `read` 超时 | 1s~30s |
| `ctds.auth.permissions.applicant` | 见 §1 | 追加 `connector.source.manage` | —— |

## 确认记录（V0.9 → V1.0）

| 项 | 内容 |
| --- | --- |
| 提请确认 | 2026-10-27，lofi Q1~Q8 建议 A + D1 不拆分；请一次确认 |
| **编排师裁定** | ✅ **2026-10-27 编排师会话回复"确认"** = Q1~Q8 全采 A + D1 不拆分 |
| 确认后动作（已执行） | ① ✅ 本文件与 lofi 转 V1.0 + 末节回填；② ✅ 任务卡 §二 / 关闭条件① 更新；③ ✅ 确认批提交并推送 origin；④ 🟡 编码段以 §11 十项为第一动作（测试先行，另会冷启动） |
