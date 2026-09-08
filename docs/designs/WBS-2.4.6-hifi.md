# WBS-2.4.6 common-国密封装 · 高保真设计（定稿 = 编码契约）
- 型态：非界面类（基建组件）
- 对应规格：制度依据 = WBS 2.4.6 产出定义（"SM2/SM4 组件 + KMS 对接 + 合规性测试用例（全平台唯一加解密入口）"，2 天）+ 章程 4.3 与红线（禁止自研密码学）+ ADR-001（BouncyCastle 基线）+ ADR-004（SM3 摘要上链、密钥材料不上链）+ 低保真（已确认 2026-09-08，五问回答：1 认可 / 2 同意纳入 SM3 / 3 选模式 A / 4 可以 / 5 可以）
- 任务卡：WBS 2.4.6 ｜ 工作量：2 天（>1 天，两级分开确认；本文件为定稿确认，PO 签署后才编码）
- 关联设计：低保真 = `docs/designs/WBS-2.4.6-lofi.md`（已确认）；定稿后契约固化进新建 `docs/adr/ADR-006-国密加密封装契约.md`（编码会话提交）

> **PO 阅读指引（业务可读）**：本文件是你签署的"编码契约"——之后实现的每一个对外行为、每一条报错文案、每一个演示用例都以本表为准。你只需核对三处：①"行为清单"是不是你要的功能；②"边界值与异常行为"里的报错文案是否业务可接受；③"演示接入"能不能照着演示。其余表格是 AI 评审链的技术定稿，无需逐字阅读。

## 确认记录（人签署；未签署 = 未确认 = 禁止进入编码）

| 轮次 | 结论（确认/打回） | 确认人（PO） | 日期 | 意见（打回必填） |
| --- | --- | --- | --- | --- |
| 1 | 确认 | po | 2026-09-08:15:53 | |

## 与低保真的差异声明（细化产生的两处澄清，无结构新增）

1. **SM2 密钥走"参数传入"，不进 KeyProvider**：KeyProvider（统一密钥供给接口）只服务 **SM4 数据加解密密钥**（对称密钥是平台的"存密钥"场景）。SM2 属"主体身份"类密钥：公钥本就公开、私钥托管归 3.1.8（业务侧走 KMS）、"数字信封"场景下加密用的是**对方的**公钥（不在本方密钥库）。故 SM2 服务方法直接接收密钥材料参数，组件只做纯运算——业务代码同样"不自研算法、只调组件"，唯一入口红线不破坏；
2. **本组件不内置审计联动**：低保真"日志只记操作结果"由编码规范兑现（异常与日志一律不带明文/密文/密钥内容）；密钥使用审计（谁用哪把密钥做了什么）属 2.6.3"密钥管理实施"范围，避免本任务顺手扩边界。

## 行为清单（逐条对应产出定义与低保真确认，验收以此表为准）

| 编号 | 行为（业务语言） | 对应依据 | 计划测试 |
| --- | --- | --- | --- |
| B1 | SM4 加密统一入口：业务报"明文 + 用哪把密钥（编号）"→ 返回**密文信封**（字节或 Base64 两种形态）；每次加密自带随机初始向量 → **同一明文两次加密结果不同**，都能正确解回 | lofi 做什么-3 + Q5 | Sm4ServiceTest：往返一致 / 随机 IV 两密文互异且互解 |
| B2 | SM4 解密 + 防篡改：信封经本组件解密返回明文；密文被改一个字节、被截断、或拿错密钥 → **明确拒绝**（报"数据校验未通过"），绝不吐乱码"假明文" | lofi 做什么-3 | Sm4TamperTest：篡改任意位 / 截断 / 错密钥三类拒绝用例 |
| B3 | 密文信封格式统一且可识别：信封自带"CTDS 密文"标识 + 格式版本号 + 随机向量 + 完整性标记；非本格式数据 → 报"输入不合法"（与"被篡改"区分口径）；版本字段为将来密钥轮换/算法升级留余地 | lofi 结构组成 | 格式钉桩测试：前缀/版本/长度关系（信封长度 = 5+12+明文长+16） |
| B4 | SM2 密钥对与加解密：一行生成密钥对（公钥/私钥均为十六进制文本，公钥为国标非压缩点格式）；用公钥加密、私钥解密往返一致；密文为国标 C1C3C2 结构 | lofi 做什么-2 | Sm2ServiceTest：生成格式断言 / 加解密往返 / 篡改密文被拒 |
| B5 | SM2 签名验签：私钥对任意字节签名（国标 DER 格式）；公钥验签返回"通过/不通过"——数据或签名被改、用错公钥均为"不通过"，**不报系统错误** | lofi 做什么-2 | Sm2SignTest：往返 / 改数据 false / 改签名 false / 错公钥 false |
| B6 | SM3 国密摘要（Q2 确认纳入）：任意字节/文本 → 64 位十六进制摘要，供存证与文件完整性使用 | Q2 + ADR-004 | Sm3ServiceTest：标准示例值 + 长度/稳定性 |
| B7 | KMS 对接 = 统一密钥供给接口（Q3 模式 A）：`KeyProvider` 接口按密钥编号取 SM4 密钥；默认本地文件实现（启动读入只缓存、文件永不回写）；2.6.3 真实 KMS 以 Bean 覆盖接入、业务零改动；密钥编号查无 → 报"密钥服务暂不可用" | lofi 做什么-4 + Q3A | LocalFileKeyProviderTest（解析/未知编号/坏行 fail-fast）+ AutoConfiguration 覆盖 Bean 测试 |
| B8 | 合规性测试用例（Q4）：SM4 用 GB/T 32907 附录公开测试值（含百万次迭代）、SM2 用 GB/T 32918 附录公开测试值、SM3 用 GB/T 32905 示例值验证算法实现正确；编码时须从标准原文核对数值并在测试注释标注出处（引文核对义务，不符 = 不得合入）；连同 B2/B5 安全用例构成 4.3.3 合规报告基础材料 | lofi 做什么-5 + Q4 | ComplianceVectorTest（独立测试类，逐标准分组） |
| B9 | 统一错误码与对外文案：新模块码段 `1001`（平台域 10 + 模块 01 + 类型 + 序号），四个码一次定稿（见接口契约"错误码表"）；沿用 `BizException` 与全局默认映射（C→400、S→500），**errorcode 组件既有码与行为零改动**；任何报错不含明文/密文/密钥内容 | lofi 做什么-1/6 | 封套断言集成测试（码值+文案+不含敏感内容） |
| B10 | 安全红线兑现：密钥材料不入库、不打印（异常消息与日志均无密钥）；明文/密文不进日志；本地密钥文件由 `.gitignore` 拦截 + gitleaks 门禁兜底；不注册全局 JCE Provider（直用 BouncyCastle 轻量 API，零全局副作用）；组件全部服务无状态、并发安全 | lofi 做什么-6 + 红线 | 密钥文件路径 gitignore 断言（文档级人工核对）+ 并发加解密一致性测试 |
| B11 | example-service 演示（Q5）：新增"保密备注"端点——POST 写入明文（组件 SM4 加密后入库，内存仓储同既有演示模式），GET 按 id 返回解密明文；另留"直接读库存的是密文"演示面；篡改库中密文后 GET → 400"数据校验未通过" | lofi 做什么-7 + Q5 | 集成测试 3 用例：存取往返 / 两次入库密文互异 / 篡改被拒 |
| B12 | 依赖登记与契约固化：`bcprov-jdk18on:1.85.2` 已登记 `docs/dependencies.md`（本设计会话），根 pom `dependencyManagement` 锁版随编码提交；新建 ADR-006 固化信封格式/配置前缀/错误码段/KeyProvider 边界（编码会话提交） | lofi 场景判定 + 缺口声明-1 | 人工核对（文档级） |

## 接口契约

### 模块与包

`common/crypto`（目录=模块=artifactId=`common-crypto`），包 `com.ctds.common.crypto`；依赖 `common-errorcode` + `bcprov-jdk18on`。自动装配模式同前四组件（`AutoConfiguration.imports` 注册）。

### SM4（`Sm4Service`，Bean 注入使用）

| 方法 | 入参 | 出参 | 异常 |
| --- | --- | --- | --- |
| `encrypt(byte[] plaintext, String keyRef)` | 明文字节；密钥编号 | 密文信封 byte[] | 见边界表 |
| `decrypt(byte[] envelope, String keyRef)` | 密文信封 | 明文字节 | 篡改/错密钥 → `DATA_REJECTED` |
| `encryptText(String utf8Text, String keyRef)` | 明文字符串 | Base64 信封字符串 | 同上（字符串便捷形，与字节形同引擎） |
| `decryptText(String base64Envelope, String keyRef)` | Base64 信封 | 明文字符串 | 同 `decrypt` |

- 算法：SM4/GCM/NoPadding（AEAD 自带完整性标记），12 字节随机 IV/次，16 字节标签；
- 信封二进制格式（版本 1）：`CTSE`(4B 魔数) + `0x01`(1B 版本) + IV(12B) + 密文(N) + GCM 标签(16B)；Base64 形 = 同一字节的 Base64；
- 直接调用 BouncyCastle 轻量 API（`SM4Engine`+`GCMBlockCipher`），**不** `Security.addProvider`。

### SM2（`Sm2Service`）

| 方法 | 说明 |
| --- | --- |
| `generateKeyPair()` → `Sm2KeyPair(publicKeyHex, privateKeyHex)` | 曲线 sm2p256v1；公钥 = 非压缩点 hex（`04`+X+Y，130 字符）；私钥 = D 值 hex（64 字符）——国标/DID 生态通用记法 |
| `encrypt(byte[] plain, String publicKeyHex)` / `decrypt(byte[] cipher, String privateKeyHex)` | C1C3C2 模式（GB/T 32918.4 新版默认）；密文以 `0x04` 起、结构 1+64+32+32+N |
| `sign(byte[] data, String privateKeyHex)` → DER 签名 | 用户标识取国标默认值 `1234567812345678`（Z 值按 GB/T 32918.2） |
| `verify(byte[] data, byte[] signature, String publicKeyHex)` → boolean | 不通过 = false，不抛错；仅输入格式坏才抛 `INPUT_INVALID` |

### SM3（`Sm3Service`）

`digestHex(byte[])` / `digestHex(String)` → 64 字符小写 hex。

### KeyProvider（密钥供给抽象，Q3 模式 A 落地件）

```java
public interface KeyProvider {
    byte[] sm4Key(String keyRef);  // 16 字节；查无 → BizException(CRYPTO_KEY_UNAVAILABLE)
}
```

- 默认实现 `LocalFileKeyProvider`：读 `ctds.crypto.local.key-file`（properties 形 `密钥编号=Base64(16字节)`，`#` 注释行）；启动一次性加载为只读缓存，**永不回写**；
- 覆盖方式：业务定义 `KeyProvider` Bean 即替换（`@ConditionalOnMissingBean`）——2.6.3 真实 KMS 实现按此接入，本组件与业务代码零改动。

### 错误码表（`CryptoErrorCodes` 常量类，新模块码段 1001，对外文案为服务端常量、不回显输入）

| 码 | 常量 | 类型→HTTP | 对外文案 | 触发场景 |
| --- | --- | --- | --- | --- |
| `1001C0001` | `CRYPTO_INPUT_INVALID` | C→400 | 加解密输入不合法 | null/空/超长入参、非 CTDS 信封、版本不认识、hex/Base64 格式坏 |
| `1001C0002` | `CRYPTO_DATA_REJECTED` | C→400 | 数据校验未通过，已拒绝 | 密文被篡改/截断/错密钥解密失败（GCM 标签或 SM2 解密校验不过） |
| `1001S0001` | `CRYPTO_KEY_UNAVAILABLE` | S→500 | 密钥服务暂不可用 | keyRef 在密钥源查无（WARN 日志可含 keyRef——业务别名非密钥材料） |
| `1001S0002` | `CRYPTO_OPERATION_FAILED` | S→500 | 加解密操作失败 | 底层算法库未预期异常（只记类别，不记消息内容） |

- 全部经 `BizException` 抛出，沿用 `GlobalExceptionHandler` 默认映射，**errorcode 模块零改动**（无新增公共码、无新增 advice）。

### 配置项全表

| 键 | 默认 | 含义 |
| --- | --- | --- |
| `ctds.crypto.enabled` | true | 组件总开关（关闭 → 不注册任何 Bean，注入失败=启动失败，fail-fast 沿 2.4.5 教训） |
| `ctds.crypto.local.key-file` | （空） | SM4 本地密钥文件路径；**配置了但文件缺失/不可读/有坏行 → 启动失败并指明原因（fail-fast，配置错误不留到运行期）**；未配置 → 启动一次性 WARN"SM4 密钥未配置"，用到时报 KEY_UNAVAILABLE |

### 边界值与异常行为（编码与测试按此表逐行钉死）

| 场景 | 行为 |
| --- | --- |
| plaintext / data / base64 为 null 或空数组（含空串加密） | `INPUT_INVALID`（V1.0 统一拒绝空明文，语义不留歧义；判定留痕） |
| 明文 > 64 MiB | `INPUT_INVALID`（超限防内存打爆；大文件分块加密属 3.5.3 场景再议，登记观察项） |
| keyRef null/空白/超 64 字符 | `INPUT_INVALID` |
| 密钥文件某行值不是 Base64 或长度不是 16 字节 | 启动失败（fail-fast，报错只给行号与原因类别，**不回显值**） |
| 信封魔数/版本不认识、字节数 < 33（装不下最小信封） | `INPUT_INVALID`（与"篡改"区分：前者非我方格式） |
| 信封 ≥33 字节但 GCM 校验不过（含截断、改位、错密钥） | `DATA_REJECTED` |
| SM2 私钥/公钥 hex 格式坏 | `INPUT_INVALID`；密文结构坏/内容被改 | `DATA_REJECTED` |
| SM2 验签：数据/签名被改、公钥不匹配 | 返回 false（非异常） |
| 并发多线程同时加解密同一服务实例 | 结果正确、互不串扰（无状态设计） | 并发用例 |
| 任何异常消息 / WARN 日志 | 不含明文、密文、密钥材料内容；可含 keyRef 与错误类别 |

### 线程安全与性能声明

所有服务无状态（每次运算现场构造 BC 引擎对象，引擎对象非线程安全故**不得**做单例字段——判定留痕）；SM4 加密吞吐基准在编码会话附带记录（1 MiB 级耗时，业务可读：演示量级毫秒内）。

## 实施清单（编码会话按序执行，全部完成 = 可提评审）

1. 根 pom：`dependencyManagement` 锁 `bcprov-jdk18on:1.85.2`；新增模块 `common/crypto`（parent 带 `<relativePath>../../pom.xml</relativePath>`）；
2. 模块骨架：`CryptoErrorCodes` + `Sm4Service/Sm2Service/Sm3Service` + `KeyProvider/LocalFileKeyProvider` + 配置属性类 + 自动装配 + `AutoConfiguration.imports`；
3. SM4 + 信封格式 + 本地密钥源（B1/B2/B3/B7，先写失败测试再实现，测试先行）；
4. SM2 + SM3 + 合规向量测试类 B8（标准原文数值引文核对后落测）；
5. example-service 保密备注演示（B11）+ `.gitignore` 密钥文件拦截复核；
6. 全量门禁（compile/test/checkstyle/gitleaks，run-gates.ps1 GREEN）+ 新建 `ADR-006-国密加密封装契约.md` 固化契约 → 提 4 视角评审。

## 测试映射与门禁声明

- 上表"计划测试"列即测试用例来源；核心模块（章程列 crypto）行覆盖 ≥80% 的**实测数值待 JaCoCo 接入**（沿 2.4.5 口径，交付说明不宣称覆盖数值，只报门禁 GREEN）；
- 禁止空洞断言：每个拒绝用例同时断言错误码与"不含敏感内容"（B9/B10 红线用例）。

## 变更影响声明

- 验收剧本：无（基建组件，演示 = 交付说明用例表：①存取往返 ②两次密文互异 ③篡改被拒 ④密钥 Bean 覆盖演示可选）；追溯矩阵：本任务即契约源，无需回写规格；
- 下游衔接：3.1.8（SM2 密钥对/签名可直接取用）、3.5.3（文件加密分块留观察项）、2.6.3（KeyProvider 覆盖 + 轮换策略）、4.3.3（B8 合规向量测试即报告材料）。

## 观察项登记（不在本任务处理）

1. 大文件流式/分块加密接口（64 MiB 上限的补充方案）→ 3.5.3 任务卡裁决；
2. 密钥轮换后旧密文解密路由（信封 keyRef 内嵌方案）→ 2.6.3 裁决（信封版本字段已预留）；
3. 传输层国密（TLCP）→ 部署基线/2.6.1；
4. 真实 KMS 产品选型 → 2.6.3 启动前 PO 裁决。

## 评审修复记录（编码会话 4 视角会审后增补留痕，沿 2.4.5 先例；均为实现澄清/加固，不新增对外行为）

- **评审①（规格与设计符合性，总判定：有条件通过；P0=0 P1=0）**：
  - ①P2-1：**64 MiB 边界往返不对称已修复**——明文恰为上限时信封长 = 64MiB+33，解密上界原按明文口径拒绝自产数据；修复为信封口径上界（SM4：`MAX+MIN_LEN`；SM2：`MAX+97`，`Inputs.requireSm4EnvelopeLength/requireSm2CipherLength`），并补"明文恰为上限往返"用例（Sm4ServiceTest.roundTripAtExactMaxBoundary）；
  - ①P2-2：密钥文件命名约定落实——`.gitignore` 增加 `*.keys`/`*keys*.properties`/`*keyfile*` 拦截，LocalFileKeyProvider javadoc 写明约定；
  - ①P2-3：**S 码对外文案冲突登记待 PO 裁决**——契约错误码表所载 S 码文案（"密钥服务暂不可用/加解密操作失败"）仅存于日志，出站经 GlobalExceptionHandler 统一替换为"系统繁忙，请稍后重试"（契约 B9 要求 errorcode 零改动，实现忠实执行后者）；请 PO 在验收时确认此口径；
  - ①P3-3：SM4 吞吐基准已实测（1 MiB：加密 ~18.7ms / 解密 ~21.4ms，即 ~53/~47 MiB/s，开发机单线程）——数值记入交付说明与开发日志；
- **评审②（安全与供应链，总判定：需修复后发布；P0=0 P1=0）**：
  - ②P2-1：**字符串便捷入口 64MiB 上界旁路已修复**——encryptText/decryptText 先做文本长度校验再转字节/解码（Inputs.requireText 增上界 = 明文上限），补"超长文本先拒、非法 keyRef 拒"用例；
  - ②P2-2：**合规向量溯源留痕补齐**——三重独立实现（OpenSSL 3.5.5 / BouncyCastle 1.85.2 / Python gmssl）交叉计算的命令与结果快照记入编码会话开发日志（26-09-08 编码日志），与 ADR-006 §7 呼应；
  - ②P2-3：bcprov 以 **compile 传递依赖**（非 optional）为**有意决策留痕**：common-crypto 即"全平台唯一加解密入口"，引入即使用，凡引必带算法库；2.4.5 的 resource-server 用 optional 是因 auth 有"只服务侧不用网关侧"的消费面，二者场景不同；后续若出现"引 crypto 但不用 SM2/SM4"的消费方再改 optional（评审③后确认无此类消费方）；
  - ②P2-4：密钥文件误提交双防线补齐——`.gitignore` 增 `*keys*.properties`/`*keyfile*`；门禁 secretsScan 无引号密钥行正则属门禁配置（红线 3：门禁配置变更须走架构变更流程），以观察项登记待流程放行，不擅改 gates-config.json；
  - ②P3-1：keyRef 字符集限 `[A-Za-z0-9._-]`（Inputs 常量单点，防日志伪造）；
  - ②P3-3：LocalFileKeyProvider javadoc 已补命名约定；
  - ②P3-2/P3-4/P3-5/P3-6/P3-7：登记观察项（缺失文件异常含路径仅运维面可接受、SM2 附录向量 4.3.3 补齐、本地密钥文件演示级保护、common-errorcode 传递 web 栈属既有设计、S 型异常堆栈无敏感数据）。
- **评审③（一致性与重复，总判定：有条件通过；P0=0）**：
  - ③P1：**SM3 空输入语义统一并留痕**——两个重载均放行空输入（SM3 空消息摘要有标准定义，摘要与加解密不同，不适用边界表"空数组拒绝"），测试补"空串与空字节结果一致"；本契约新增此边界（B6"任意字节"口径）；
  - ③P2-1：hifi B8 与 ADR-006 口径同步——SM2 附录向量因标准原文网络不可达推迟至 4.3.3 补齐（ADR-006 §7 已登记，本条为 hifi 侧留痕）；
  - ③P2-2：enabled 开关两种语义声明——crypto 型"全禁用 fail-fast"与 auth/audit 型"部分禁用"均为有意设计（fail-fast 沿 2.4.5 评审教训，见 ADR-006 §6），公共文档后续统一收敛；
  - ③P3-1：keyRef 正则与"密钥服务暂不可用"文案已收敛到 Inputs 单点（LocalFileKeyProvider 改调 Inputs.keyUnavailable()）；
  - ③P3-2：DemoKeyProviderConfig 引用应用层 KEY_REF 常量——演示期接受，登记观察项（2.6.3 真实 KMS 实现时 KEY_REF 下沉 domain）；
  - ③P3-3：ADR-006 错误码位宽表述"8 位"更正为"9 位"。
- **评审④（测试质量）**：结论见本会话评审处置（hifi 记录随编码会话日志一并留痕）。
