# WBS-2.6.3 密钥管理实施 · 高保真设计（定稿 = 编码契约）

- 对应规格：`docs/specs/C-2.6.3-密钥管理实施.md` V1.0 ｜ 低保真：`docs/designs/WBS-2.6.3-lofi.md`（同轮确认）
- 本文件为编码契约：实现与本文不一致 = 打回项（章程 2.6）

## 确认记录（人签署；未签署 = 未确认 = 禁止进入编码）

| 轮次 | 结论（确认/打回） | 确认人（PO） | 日期 | 意见（打回必填） |
| --- | --- | --- | --- | --- |
| 1 | 确认 | 项目主导者（兼任 PO；会话选项回复"确认通过，开始编码"，表格由 AI 按该声明代录留痕） | 2026-09-13 | 无 |

## 1. 密文信封格式 v2（密钥轮换载体；v1 不变）

| 字段 | v1（既有，不动） | v2（本次新增） |
| --- | --- | --- |
| 布局 | `CTSE`(4B) + `0x01`(1B) + IV(12B) + 密文 + 标签(16B) | `CTSE`(4B) + `0x02`(1B) + **keyVersion(4B 大端)** + IV(12B) + 密文 + 标签(16B) |
| 头部长 | 17B（最小信封 33B） | 21B（最小信封 37B） |
| 用途 | 本地文件实现（无版本概念），行为零变化 | KMS 托管密钥：加密写当前版本号，解密按版本取密钥 |

- `Sm4Service.encrypt`：KeyProvider 支持**版本化**（实现 `VersionedKeyProvider`）→ 产出 v2 信封（写 `currentVersion(keyRef)`）；否则产出 v1（既有路径逐字节不变，既有测试全数保留）；
- `Sm4Service.decrypt`：按版本字节分流——`0x01` → 既有路径（`sm4Key(keyRef)`）；`0x02` → `sm4Key(keyRef, version)`；非两种版本 → `1001C0001`（与现状语义一致）；
- ADR-006 注记（随本任务提交）：v2 版式定义即 ADR-006 §3.2/§5.3 预留的兑现，v1 语义与既有密文兼容性不变。

## 2. `common-crypto` 契约（新增部分）

```java
/** 版本化密钥供给（2.6.3）：KMS 托管密钥实现本接口；本地文件实现不实现（保持 v1 语义）。 */
public interface VersionedKeyProvider extends KeyProvider {
    /** 按编号+版本取密钥材料（16 字节，新副本）。版本不存在 → 1001S0001。 */
    byte[] sm4Key(String keyRef, int version);
    /** 该编号当前版本号；编号不存在 → 1001S0001。 */
    int currentVersion(String keyRef);
}
```

`KmsKeyProvider`（`common/crypto` 内，JDK 17 内置 HttpClient，零新依赖）：

| 配置键（`ctds.crypto.kms.*`） | 类型/默认 | 语义 |
| --- | --- | --- |
| `base-url` | String / 未设置 | 设置即启用（KMS 模式），注册 `KmsKeyProvider` 覆盖本地实现；未设置沿用 `LocalFileKeyProvider`（ADR-006 覆盖式接入） |
| `connect-timeout` | Duration / 3s（`@DurationUnit(SECONDS)`） | 建连超时 |
| `read-timeout` | Duration / 5s（`@DurationUnit(SECONDS)`） | 响应超时 |

错误语义（复用既有码，不新增）：KMS 连接失败/超时/5xx/版本或编号不存在/响应体不合法 → 一律 `1001S0001`（密钥服务暂不可用，fail-fast 不降级）；密钥材料只进内存，禁入日志/异常。

## 3. KMS 服务（`services/kms`，包 `com.ctds.kms`）

### 3.1 错误码（模块位 `02`，1002 段；ADR-015 留痕）

| 码 | 语义 |
| --- | --- |
| `1002C0001` | 输入不合法（keyRef 格式、请求体缺失等） |
| `1002B0001` | 密钥编号已存在 |
| `1002B0002` | 密钥编号或版本不存在 |
| `1002S0001` | KMS 服务内部错误（含根密钥未配置等启动期问题另行 fail-fast） |

### 3.2 数据库（Flyway V1，ADR-009 规范）

```sql
kms_key        (key_ref VARCHAR(64) PK, status VARCHAR(16), current_version INT NOT NULL, created_at DATETIME)
kms_key_version(key_ref VARCHAR(64), version INT, material_cipher VARCHAR(128) NOT NULL, -- 根密钥 SM4 信封的 Base64
                created_at DATETIME, PRIMARY KEY(key_ref, version))
kms_key_audit  (id BIGINT AUTO_INCREMENT PK, action VARCHAR(16), key_ref VARCHAR(64),
                old_version INT, new_version INT, operator VARCHAR(64), occurred_at DATETIME)
```

`status` ∈ `ENABLED`/`DISABLED`；密钥材料落库**只有密文信封**（根密钥加密），无明文列。

### 3.3 REST 接口（ADR-005：`/api/v1`、ApiResult、code 字符串）

| 方法/路径 | 权限 | 请求 | 响应 data | 说明 |
| --- | --- | --- | --- | --- |
| POST `/api/v1/keys` | `kms:admin` | `{keyRef}` | `{keyRef, version:1, status}` | 创建密钥（生成 16B 随机材料为 v1）；重复编号 → `1002B0001` |
| POST `/api/v1/keys/{keyRef}/rotations` | `kms:admin` | `{operator}` | `{keyRef, oldVersion, newVersion}` | 轮换：新增版本 +1 并写审计；编号不存在 → `1002B0002` |
| GET `/api/v1/keys/{keyRef}` | 认证 | — | `{keyRef, status, currentVersion}` | 元数据（无材料） |
| GET `/api/v1/keys/{keyRef}/material` | 认证（内部供给） | — | `{keyRef, version, material(Base64)}` | 当前版本材料；给 `KmsKeyProvider` |
| GET `/api/v1/keys/{keyRef}/versions/{version}/material` | 认证（内部供给） | — | 同上（历史版本） | 给解密旧密文用 |
| GET `/actuator/health` | 匿名 | — | — | 存活探测 |

权限断言经 `common-auth` `AccessControl.require("kms:admin")`（未认证→UNAUTHORIZED、无权限→FORBIDDEN+DENIED 审计）；`operator` 取已认证主体（请求体不再收 operator，防伪造——修正点：审计操作者以 `AuthContext` 当前身份为准）。

### 3.4 根密钥与启动

- 配置 `ctds.kms.root-key` ← 环境变量 `CTDS_KMS_ROOT_KEY`（Base64 解码后须 16 字节）；缺失/坏值 = **启动失败**（fail-fast，沿 2.4.6 密钥文件同款语义）；根密钥禁入代码/配置文件字面量/日志；
- 密钥材料加密：每条材料独立 SM4-GCM 信封（随机 IV），加解密一律走 `common-crypto`。

## 4. 测试计划（先行，映射规格验收标准）

| 用例组 | 映射规格 | 要点 |
| --- | --- | --- |
| `CipherEnvelopeV2Test`（common-crypto） | 行为 2 | v2 布局逐字节断言（21B 头/版本字节/4B 大端版本号）；v1 布局既有断言不动 |
| `Sm4ServiceRotationTest` | 行为 2 GTT-1 | 版本化桩：v1 密钥加密→轮换（currentVersion=2）→旧密文仍解密、新密文用 v2 信封+新密钥 |
| `Sm4ServiceV1RegressionTest` | 最小实现 | 本地桩下信封仍为 v1、既有行为零变化 |
| `KmsKeyProviderTest`（JDK HttpServer 桩，零新依赖） | 行为 1 GTT-1/2 | 正常供给/404→1001S0001/连接拒绝→1001S0001/响应不合法→1001S0001 |
| `CryptoAutoConfigurationTest` 增补 | ADR-006 覆盖式接入 | 配 base-url → KmsKeyProvider Bean 覆盖；不配 → LocalFileKeyProvider（松弛绑定键名用例，防静默失效） |
| `kms` 服务单元+MockMvc | 行为 1/2 全部 GTT | 创建/轮换/审计四要素/未授权拒绝/`1002B0001/0002`；权限用 `AccessControl` 桩；有状态库用例按 ADR-010/Testcontainers 方法级容器 |
| 门禁 secretsScan | 行为 3 GTT | 增强规则对本仓库全量绿灯（无误报）；临时样本文件人工验证红灯后删除 |

## 5. 实施顺序（测试先行）

1. common-crypto：信封 v2 + VersionedKeyProvider + Sm4Service 分流 + 配置与 KmsKeyProvider（测试先行）；
2. services/kms：迁移 SQL → 领域/应用 → 接口层（测试先行）；
3. `scripts/gates/gates-config.json` secretsPatterns 增强 + 门禁全量跑绿；
4. ADR-015（KMS 形态裁决留痕）+ ADR-006 变更注记 + 等保清单三行刷新 + 依赖无新增（`docs/dependencies.md` 不动）。

## 6. 交付后注意事项（写进交付说明）

- 组件→KMS 内网明文传输为 G-01 已知缺口，不虚报已加密；演进建议：信封加密模式（密钥不出 KMS）+ 供给结果短 TTL 缓存；
- 存量 v1 信封与轮换互斥（重加密迁移后再轮换）；
- `LocalFileKeyProvider` 仅限演示环境，生产部署基线须配置 `ctds.crypto.kms.base-url`。
