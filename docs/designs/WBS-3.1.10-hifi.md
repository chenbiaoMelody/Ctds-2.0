# WBS-3.1.10 跨空间身份互认接口 · 高保真设计（编码契约）

- 低保真：`docs/designs/WBS-3.1.10-lofi.md`（Q1~Q7 裁决口径生效后本文才成立）
- 规格锚点：`docs/specs/C-1.2-分布式数字身份DID.md` 行为 5（规则 1~4 / 验收标准 4 条）
- 契约锚点：`docs/adr/ADR-008-标准适配层契约.md`（收口纪律 + 域清单 + 1003 段）、`docs/adr/ADR-017-DID服务契约.md`（§2.9 解析/验证契约、§2.2 应用时钟、后续包约束）
- 上游能力：3.1.8（签发/吊销/KMS 代签）、3.1.9（解析/验证 + 留痕表 `did_verification_log`）

## 确认记录（人签署；未签署 = 未确认 = 禁止进入编码）

| 项 | 内容 |
| --- | --- |
| 编码契约生效 | **已确认（2026-09-24 23:47）**：编排师会话回复"确认"（与 lofi Q1~Q7 同批一次确认，章程 2.6.3）——本文件 §1~§10 即编码契约，含接口契约表、边界值表、测试锚点 T1~T12 与实施前置检查项 |
| 确认后状态 | **可进入编码**（测试先行 RED → 实现 GREEN；编码会话第一步 = §10 前置检查项六项，逐项实测留痕） |

---

## 1. 行为清单（逐条对应验收标准编号）

| 编号 | 行为 | 对应规格条目 |
| --- | --- | --- |
| B1 | 来访主张（对端空间标识 + 对端 DID + 原文 + 签名）经互认通道取数后执行**三查**（签名 / 对端状态 / 对端绑定），全过 = 通过 | 行为 5 规则 1、规则 2；验收标准 1 |
| B2 | 对端 DID 状态为"已吊销"→ 不通过，原因 = 状态核验失败 | 行为 5 验收标准 2 |
| B3 | 签名与原文不匹配（被篡改）→ 不通过，原因 = 签名核验失败 | 验收标准 2（剧本 S4 步骤 3） |
| B4 | 对端样例不存在（互认通道未返回该 DID）→ 不通过，原因 = 未登记 | 行为 5 规则 2（三查口径的对端映射） |
| B5 | 每次**业务结论**（通过/不通过/不可用）写留痕四要素（对端空间标识 / DID / 时间 / 结果，失败另含原因），**不保存原文** | 行为 5 规则 2（留痕口径同行为 3） |
| B6 | **出向验证**：本空间 DID 经模拟对端回放结论（有效→通过 / 已吊销→不通过），结论与本空间当前状态一致 | 行为 5 规则 1、验收标准 3；剧本 S4 步骤 4~5 |
| B7 | 提供**预置样例清单**（三态：有效 / 对端已吊销 / 签名被篡改），执行人免手输 | 行为 5 规则 4；剧本附录 A 组 E1 取用口径 |
| B8 | 互认能力未开放时接口明确返回"该标准互联功能尚未开放"，不返回伪结果（交付后由真实实现替代；占位答复契约保留在 std-adapter） | 行为 5 规则 3、验收标准 4 |
| B9 | 业务服务不得自实现互认协议逻辑——互认协议实现唯一落点 = `std-adapter` 的 `did` 域（替换占位而非叠加） | 行为 5 规则 3；ADR-008 §3.1/§3.4/§5.3 |

## 2. 接口契约表（`services/did` 扩展，前缀 `/api/v1/did-interop`）

| 方法 | 路径 | 权限 | 入参 | 出参（data） | 主要错误 |
| --- | --- | --- | --- | --- | --- |
| POST | `/api/v1/did-interop/inbound-verifications` | **无**（对外验证能力；演示期回环边界，登记 ADR-017 补记） | `{peerSpace, did, data: Base64, signature: Base64}` | `{peerSpace, did, result: PASS\|FAIL\|UNAVAILABLE, reason: null\|SIGNATURE_INVALID\|REVOKED\|SUBJECT_BINDING_FAILED\|NOT_REGISTERED\|BINDING_UNAVAILABLE, verifiedAt}` | 1005C0004（入参不合法）、1005S0002（内部错误） |
| POST | `/api/v1/did-interop/outbound-verifications` | **无**（同上） | `{did}`（本空间 DID） | `{peerSpace, did, result, reason, verifiedAt}` | 1005C0004（DID 非法）、1005S0002 |
| GET | `/api/v1/did-interop/samples` | **无**（只读演示样例） | 无 | `[{sampleId, peerSpace, peerSpaceName, did, scenario: VALID\|PEER_REVOKED\|TAMPERED, data, signature, expectedResult, expectedReason}]` | 1005S0002 |

- 统一封套 `ApiResult`；时间戳秒级 ISO-8601（**应用时钟**，沿 ADR-017 §2.2 与 DB-22 教训）。
- `reason` 枚举**复用 3.1.9 五值口径**（不新增枚举）：`SIGNATURE_INVALID` / `REVOKED` / `SUBJECT_BINDING_FAILED` / `NOT_REGISTERED` / `BINDING_UNAVAILABLE`；互认场景语义映射见 §6。
- `result=UNAVAILABLE` 为**系统态诚实表征**（互认通道不可用），不等于"验证不通过"（不冒充业务结论）。

## 3. 库表结构（`ctds_did` 库，Flyway `V3__create_interop_log.sql`）

```sql
CREATE TABLE did_interop_log (
  id          BIGINT       NOT NULL AUTO_INCREMENT,
  direction   VARCHAR(16)  NOT NULL COMMENT 'INBOUND 来访 / OUTBOUND 出向',
  peer_space  VARCHAR(64)  NOT NULL COMMENT '对端空间标识（演示期 = 模拟对端）',
  did         VARCHAR(128) NOT NULL COMMENT '被验证的 DID（对端 DID 或本空间 DID）',
  result      VARCHAR(16)  NOT NULL COMMENT 'PASS / FAIL / UNAVAILABLE',
  reason      VARCHAR(32)  NULL     COMMENT 'SIGNATURE_INVALID / REVOKED / SUBJECT_BINDING_FAILED / NOT_REGISTERED / BINDING_UNAVAILABLE',
  occurred_at DATETIME     NOT NULL COMMENT '应用时钟（秒级，DB-22 教训：不由 DB 生成）',
  PRIMARY KEY (id),
  KEY idx_interop_did_time (did, occurred_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='跨空间互认留痕（行为 5 规则 2：不保存业务数据原文）';
```

- 不建外键、不存 `data`/`signature` 原文；不改动 `did_verification_log`（3.1.9 契约不变）。

## 4. 错误码（**不新增**，复用既有；登记 ADR-017 补记）

| 码 | 常量 | 语义 | HTTP | 归属 |
| --- | --- | --- | --- | --- |
| 1005C0004 | `DID_VERIFICATION_INPUT_INVALID` | 互认入参不合法（peerSpace/did/data/signature 缺失或格式非法、data 超限） | 400 | 复用（3.1.9） |
| 1005S0002 | `DID_VERIFICATION_INTERNAL_ERROR` | 互认内部错误（样例/留痕读写异常等非输入类故障） | 500 | 复用（3.1.9） |
| 1003C0001 | `NOT_IMPLEMENTED` | 该标准互联功能尚未开放（骨架期占位答复，行为 5 验收标准 4） | 400 | 复用（std-adapter） |

- **不新增码的理由**：互认的输入面与 3.1.9 验证端点同构（文本 + 签名），复用 `1005C0004` 语义完整；对端样例不存在属**业务结论**（`FAIL` + `NOT_REGISTERED`）而非输入错误。
- 对外文案为服务端常量，禁止拼接域信息/用户输入（章程 4.3）。

## 5. 配置项与服务间衔接

| 项 | 内容 |
| --- | --- |
| 新增配置 | **零新增**（模拟对端空间标识与显示名落在样例资源 `std/did-interop-samples.json`：`linjiang` / "临江数据空间"） |
| 装配点（std-adapter） | **新增** `DidInteropStandardConfig`（`@Configuration` + `@Bean @ConditionalOnMissingBean(DidInteropStandardApi.class)`）提供互认域真实实现；使用方 `@Import` 显式引入（不向所有引入 std-adapter 的服务注入无用 Bean） |
| 装配点（did 服务） | `DidInteropConfig`：`@Import(DidInteropStandardConfig.class)` + 注册 `LocalDidStatusPort` 实现（`LocalDidStatusAdapter` 进程内委托既有解析能力，**不新增 HTTP 自调用**） |
| 装配点（example-service） | `StdAdapterPlaceholderConfig`：删除 `did` 域占位 Bean 注册，改 `@Import(DidInteropStandardConfig.class)`；演示壳服务未提供 `LocalDidStatusPort` → 出向语义在该服务内为"不可用"（互认端点由 did 服务承载）；占位类已删除（ADR-008 §3.4 替换示范点） |

> **装配形态修正（编码会话，2026-09-25；设计自冲突处置）**：本表原稿"装配点（did 服务）新增配置类注册 `MockDidInteropStandardApi` Bean"与 §7 T11"`MockDidInteropStandardApi` 不出现在 did 服务源集"**互斥**。处置 = 取严、T11 原文不改：互认域真实实现只由其唯一落点（std-adapter）的装配类注册，使用方经 `@Import` 引入；本表按此回写，差异同时登记 `ADR-008` §9。守卫经反向探针实测可证伪（did 服务源集出现实现类名 → `InteropSeamTest` 红灯）。
| 占位类处置 | `PlaceholderDidInteropStandardApi` **删除**（§1.2 替换规则：删除占位而非并存；含 std-adapter 内相关测试同步调整） |
| 既有能力复用 | 验签 = `common-crypto` `Sm2Service.verify`（SM2，公钥 130 hex 非压缩点）；留痕仓储 = JDBC 既有写法；错误码 = `common-errorcode`；无新增依赖 |

## 6. 边界值与异常行为

| 场景 | 期望 |
| --- | --- |
| `peerSpace` 空/空白 | 1005C0004，**不留痕**（输入类） |
| `did` 空/空白 | 1005C0004，不留痕 |
| `data` 空或非法 Base64 | 1005C0004，不留痕 |
| `signature` 空或非法 Base64 | 1005C0004，不留痕 |
| `data` 解码后 > 1 MB | 1005C0004，不留痕（沿 3.1.9 口径） |
| 出向验证 `did` 格式非法 | 1005C0004，不留痕 |
| 对端样例不存在（DID 未预置或空间标识不匹配） | `FAIL` + `NOT_REGISTERED`，**留痕** |
| 验签不通过 | `FAIL` + `SIGNATURE_INVALID`，留痕 |
| 对端状态 = 已吊销 | `FAIL` + `REVOKED`，留痕 |
| 对端绑定 = 未绑定 | `FAIL` + `SUBJECT_BINDING_FAILED`，留痕 |
| 互认通道不可用（端口不可达/异常/返回未知） | `UNAVAILABLE` + `BINDING_UNAVAILABLE`，**留痕**（系统态诚实表征，不冒充"不通过"） |
| 出向验证本空间 DID 有效 | `PASS`，留痕（direction=OUTBOUND） |
| 出向验证本空间 DID 已吊销 | `FAIL` + `REVOKED`，留痕（双向口径，行为 5 验收标准 3） |
| 出向验证本空间 DID 未登记 | `FAIL` + `NOT_REGISTERED`，留痕 |
| 内部错误（样例加载失败等） | 1005S0002，**不留痕**（沿 3.1.9 §6 例外口径） |
| 留痕内容 | 仅四要素 + 原因；**库表与日志 0 命中原文**（反向扫描锚点） |

## 7. 测试锚点（先行 RED → 实现 GREEN）

| 编号 | 锚点 | 类型 |
| --- | --- | --- |
| T1 | 来访 · 有效样例 → `PASS` + 留痕四要素齐备 | 集成 |
| T2 | 来访 · 对端已吊销样例 → `FAIL` + `REVOKED` | 集成 |
| T3 | 来访 · 篡改样例 → `FAIL` + `SIGNATURE_INVALID` | 集成 |
| T4 | 来访 · 未预置 DID → `FAIL` + `NOT_REGISTERED` | 集成 |
| T5 | 来访 · 入参非法四型（空 data / 非法 Base64 / 空 peerSpace / data>1MB）→ 1005C0004 且**无留痕行** | 集成 |
| T6 | 通道不可用（注入端口抛错）→ `UNAVAILABLE` + `BINDING_UNAVAILABLE`，**不返回 `FAIL`** | 单元 + 集成 |
| T7 | 出向 · 有效 / 已吊销 / 未登记 三态 → `PASS` / `FAIL+REVOKED` / `FAIL+NOT_REGISTERED` | 集成 |
| T8 | 样例清单端点 → 三态齐全；`VALID`/`PEER_REVOKED` 用样例公钥**真实验签通过**，`TAMPERED` 验签失败 | 单元 + 集成 |
| T9 | 留痕原文零命中：写入后按 `data` 原文扫描库表与日志 → 0 命中 | 反向探针 |
| T10 | 收口守卫：`std-adapter` 的 `did` 域 `status().implemented() == true`，且 `PlaceholderDidInteropStandardApi` 类**不存在**（防"替换未做/占位并存"回退） | 单元（守卫） |
| T11 | 收口守卫：did 服务互认链路对协议实现的依赖**仅为 `DidInteropStandardApi` 接口类型**（`MockDidInteropStandardApi` 不出现在 did 服务源集） | 单元（守卫） |
| T12 | example 探活：`GET /api/v1/std-capabilities` 中 `did` 域 `implemented=true`（替换生效，行为 5 验收标准 4 的"未开放"语义由骨架期转为已开放） | 集成 |

## 8. 演示口径（剧本 S4 承载，界面归 3.1.11）

- 走查材料：`build-output/demo-files/did-interop/`（gitignored）——三态来访请求体 + 出向验证请求体 + 样例清单取用说明；执行人复制即用，**无需手输 DID 与签名**（剧本附录 A 取用口径）。
- S4 步骤 1~3 = 来访三态（有效 / 对端已吊销 / 签名被篡改）；步骤 4~5 = 出向验证（蓝天 DID 有效→通过；云栖 DID 已吊销→不通过）。
- **界面形态归 3.1.11**：本包交付后剧本需一次界面核对修订（剧本维护说明已预留该机制），修订不改变业务判定标准。
- 交付说明须明示：本包为"业务口径互认 + 模拟对端"，非信通院协议实现（真实协议与对端对接归 C-9.1 / 规范文本入库后）。

## 9. 变更影响与登记

| 影响面 | 登记动作 |
| --- | --- |
| ADR-008 | 变更补记：`did` 域方法冻结（业务口径方法声明 + 占位替换 + 1003 段不变） |
| ADR-017 | 补记：互认三端点契约 + `did_interop_log` + 诚实边界（演示期无鉴权） |
| 错误码 | 零新增（复用清单见 §4），ADR 补记中登记"复用而非新增"的裁定 |
| 库表 | `ctds_did` V3（新增表，不改既有表） |
| 依赖 | 零新增 |
| 门禁 | 零配置改动 |
| 演示构件 | did / example-service 需重新打包（探活语义变化） |
| 剧本 | S4 界面核对修订（3.1.11 交付后一次完成） |

## 10. 实施前置检查项（编码会话第一步，逐项实测留痕）

1. 冷启动：任务卡 → lofi/hifi 确认记录 → 台账 → 立卡日志 `-2344`（或之后最新日志）；
2. 演示环境三服务（8080/8081/8082 回环）+ Docker 容器状态核验；
3. `Sm2Service.verify` 签名/公钥编码口径实测（130 hex 非压缩公钥、签名 Base64 口径）；
4. 样例生成：一次性程序（**不入库**）生成对端密钥对与三态样例 → 私钥仅落 gitignored 目录并废弃 → 样例 JSON 入库 + 生成步骤留痕；
5. 替换前基线：example 探活 `did` 域当前输出（`implemented=false`）留痕；
6. 确认 `did` 服务既有解析能力可作为 `LocalDidStatusPort` 实现来源（进程内委托，不新增 HTTP 调用）。

### §10 实测留痕（编码会话，2026-09-25）

| 检查项 | 实测结果 |
| --- | --- |
| ① 冷启动读序 | `AGENTS.md` → 停机交接日志 `-0022` → 设计确认日志 `-2347` → 任务卡 → lofi/hifi 确认记录 → 台账快照 → ADR-008/ADR-017 → 规格 C-1.2 行为 5 → 剧本 S4 + 附录 A 组 E1 逐份读取；分支核验 `feat/C-1.2-跨空间身份互认接口` = `f246b25` = 远端（同步）、工作树干净 |
| ② 演示环境 | Docker 三容器 `sc-mysql` / `sc-minio` / `sc-redis` 均 Up；回环 8080 = subject-service、8081 = kms、8082 = did，`/actuator/health` 均 200；**example-service 未在运行**（其默认端口 8080 已由 subject-service 占用）→ 探活基线以集成测试实测承载（⑤） |
| ③ SM2 签名/公钥口径 | `mvn -pl common/crypto test -Dtest=Sm2ServiceTest` → **8/8 GREEN**；口径确认：`verify(data, signature, publicKeyHex)`、公钥 130 hex（`04` 前缀非压缩点）、私钥 64 hex、签名 DER 字节（传输层 Base64）；`Sm2Service` Bean 由 `CryptoAutoConfiguration` 提供 |
| ④ 样例生成 | 一次性生成器（临时测试类，运行后即删）经 `Sm2Service` 生成三对密钥 → 三态样例：VALID 与 PEER_REVOKED **真实验签通过**、TAMPERED **验签失败**（生成期断言自检通过）；样例 JSON 入库 `std-adapter/src/main/resources/std/did-interop-samples.json`；**私钥只落 gitignored 的 `build-output/demo-files/did-interop/peer-private-keys.json` 并即时删除**（红线 7）；走查材料（三态来访请求体）落同目录 `requests/` |
| ⑤ 替换前探活基线 | `mvn -pl services/example-service test -Dtest=StdCapabilitiesIntegrationTest` → **2/2 GREEN**（替换前基线）：`did` 域 `implemented=false` + 定稿文案"该能力域尚未开放：政务 CA 接入与跨空间身份互认接口将由后续工作包实现（WBS 3.1.4 / 3.1.10）" |
| ⑥ `LocalDidStatusPort` 来源 | 确认 = `DidResolutionService.resolve(did)`（`ResolutionResult(did, DidStatus, document)`；未登记抛 `1005B0003`）；`DidStatus` = PENDING_ISSUE / ACTIVE / REVOKED，**非 ACTIVE 即状态核验失败**（沿 3.1.9 口径）；端口实现 `LocalDidStatusAdapter` 进程内委托，无 HTTP 自调用 |
