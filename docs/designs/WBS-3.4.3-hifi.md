# WBS-3.4.3 高保真设计：合约协商与电子签署服务（hifi）

| 项 | 内容 |
| --- | --- |
| 版本 | **V1.0（编码契约，2026-10-05 编排师"都按建议"一次确认生效）**——Q1~Q9 采建议口径 A + D1 不拆分（确认记录落本文件末节与 lofi）；实现与本文逐条一致（章程 2.6.3 / AGENTS §5 自检项 1）；此前 V0.9 草案随立卡批 `44d469c` 落盘 |
| 低保真 | `docs/designs/WBS-3.4.3-lofi.md` V0.9（方向，同批确认） |
| 规格锚点 | 规格 C-4.1~4.3 V1.0 行为 2 / 3 / 6 / 7 + 行为 4 本卡承载面 + §未定义项四项；错误码 1008 段续延 |
| 技术栈 | JDK 17 + Spring Boot 3.5.x + `spring-boot-starter-jdbc` + Flyway + MySQL 8（ADR-001 冻结栈）；零新依赖（client = JDK HttpClient；加解密经 `common-crypto` 平台组件） |

---

## 1. 模块骨架与落位（既有 `contract-service` 宿主内新增）

```
services/contract-service/
  src/main/java/com/ctds/contract/
    application/
      ContractCommandService.java      # 发起/提案/确认/签署/拒签/协商终止/合意解除/强制终止 编排（门槛单点 + 留痕）
      ContractQueryService.java        # 参与方读面 + 治理读面 + 可见性判定；loadEffectiveStrategy（供 3.4.5）
      ContractNoAllocator?（并入仓储）  # 取号 = 仓储方法 nextContractNoSeq（@Transactional 连接绑定，沿 V1.2⑨）
    domain/
      Contract.java                    # 合约聚合（逐列对应 contract 表；状态迁移 Guard 方法）
      ContractStatus.java / TerminationType.java / PartyRole.java
      ClauseValues.java                # 条款值值对象（槽位值校验：按锁定框架；含 UsageControlPolicy）
      UsageControlPolicy.java          # 策略条款值对象（五要素 + 显式"无使用限制"；最小门槛校验——Q7-A）
      ContractCanonicalizer.java       # 规范化序列化（固定字段序 + 槽位键排序）+ SM3 哈希（common-crypto Sm3Service）
      ContractClauseVersion.java / ContractSignature.java / ContractAttestation.java
      ContractActionLog.java / ContractAction.java
      ContractRepository.java          # 仓储接口（条款版本行无 UPDATE 内容方法——快照不可变编译期保证）
      CatalogProductPort.java / CatalogProduct.java（三态：found / NOT_FOUND / UNAVAILABLE）
      DidPort.java / DidBinding.java / DidVerifyResult.java（解析 / 代签 / 验签三能力）
    infrastructure/
      JdbcContractRepository.java
      CatalogProductClient.java        # JDK HttpClient（服务身份 contract-service / contract-internal）
      DidClient.java                   # JDK HttpClient（解析/验签公开无鉴权；代签带服务身份头）
      ContractBeanConfig.java（追加）   # Clock 等既有 Bean；加密配置属性绑定（CtProperties 沿 CertificationProperties 先例）
    interfaces/
      ContractCommandController.java       # W5~W11 参与方写面
      ContractQueryController.java         # R6~R9 参与方读面（R9 核验为 POST 动作）
      ContractGovernanceController.java    # W12 + R10/R11 治理面
      dto/                                 # 请求/响应 DTO（记录类；出站字段集显式锚定）
  src/main/resources/
    application.yml                      # 追加 catalog/did base-url、deal-key-ref、crypto 密钥源
    db/migration/V2__create_contract_negotiation_tables.sql
  src/test/java/com/ctds/contract/...    # 见 §7 测试计划
```

> **命名勘误口径**：3.4.2 已占用 `ContractTemplate*` / `TemplateQueryService` / `ContractTemplateRepository` / `ContractBizException` / `ContractErrorCodes` / `SubjectAdmissionClient` / `ContractBeanConfig` / `ContractExceptionHandler` 与三个模板控制器——本卡一律用上表新名，无重名冲突；`ContractExceptionHandler.MAPPED_CODES` 与 `statusOf` 随本卡新码同步扩展（一致性测试同步）。

## 2. 端点契约表（ADR-005：资源复数命名 / 动词子资源 / 分页 / 写操作幂等；编号续 3.4.2）

### 2.1 参与方写面（注解 `@RequirePermission("contract.deal")` = 功能第一道门槛；**参与方与资格判定在应用服务单点**——注解层拒绝无法写规格要求的留痕，沿 hifi V1.1 §2.1 口径）

| # | 端点 | 请求体 | 成功响应 | 规则/幂等口径 |
| --- | --- | --- | --- | --- |
| W5 | `POST /api/v1/contracts` 发起合约（需求方） | `{productId, templateNo, templateVersionNo, clauseValues}` | 201 `{contractNo, status:"NEGOTIATING", clauseVersionNo:1, templateNo, templateVersionNo, productId}` | 幂等键 = `requesterNo + productId + templateNo + templateVersionNo + requestFingerprint`（**服务端派生**：`requestFingerprint` = 条款值存储形态稳定哈希，由控制器预计算并注入命令，客户端不传——勘误①；同键重放返回首次结果、合约数不变；沿 3.4.2 V1.1 §2.1 口径） |
| W6 | `POST /api/v1/contracts/{contractNo}/proposals` 提案/反提案 | `{clauseValues}` | 201 `{contractNo, clauseVersionNo: Vn+1, previousVersionNo: Vn}` | 仅 NEGOTIATING + 参与方；交替提案（当前版本提案方不得连续提案）；新版本行 + 变更明细（from→to）+ 留痕 from=Vn→to=Vn+1；指针前移带 `status=NEGOTIATING AND current_clause_version=Vn` 前置（未命中 → 事务回滚 + 1008C0019）；并发撞 uk_contract_version → 1008C0019（勘误②） |
| W7 | `POST /api/v1/contracts/{contractNo}/confirmations` 确认当前条款版本 | 空 | 200 `{contractNo, clauseVersionNo, confirmations:{provider, requester}, status}` | 仅 NEGOTIATING + 参与方；每方对当前版本各一次（重复确认 → 1008C0013 + 留痕）；**双方齐 → 锁定**（生成规范化原文 + 内容哈希）并转 PENDING_SIGNATURE；**确认时策略门槛**（至少一项或显式声明 → 1008C0015） |
| W8 | `POST /api/v1/contracts/{contractNo}/negotiation-terminations` 协商终止 | 空 | 200 `{contractNo, status:"TERMINATED", terminationType:"NEGOTIATION_TERMINATED"}` | 仅 NEGOTIATING + 参与方（任一方）；终态不可逆 |
| W9 | `POST /api/v1/contracts/{contractNo}/signatures` 电子签署 | `{did}` | 201 `{contractNo, partyRole, status, effectiveAt}` | 仅 PENDING_SIGNATURE / PARTIALLY_SIGNED + 参与方 + 资格 + 本角色未签；DID 归属与状态校验（1008C0018）；签署内容 = 锁定版本内容哈希；后签 → EFFECTIVE（生效时间 = 本签时间）+ 存证事件 |
| W10 | `POST /api/v1/contracts/{contractNo}/signature-refusals` 拒签终止 | 空 | 200 `{contractNo, status:"TERMINATED", terminationType:"SIGNATURE_REFUSED"}` | **未双签生效前**（PENDING_SIGNATURE / PARTIALLY_SIGNED）任一方（含已签方撤回——Q4-A）；生效后 → 1008C0013 |
| W11 | `POST /api/v1/contracts/{contractNo}/release-consents` 合意解除确认 | 空 | 200 `{contractNo, status, releaseConsents:{provider, requester}}` | 仅 EFFECTIVE + 参与方；每方一次；双方齐 → COMPLETED（终态，留痕保留） |

### 2.2 参与方读面（注解 `@RequirePermission("contract.read")`；参与方判定在应用服务）

| # | 端点 | 响应 | 边界 |
| --- | --- | --- | --- |
| R6 | `GET /api/v1/contracts/mine?status=&role=&pageNum=&pageSize` | 分页列表（合约编号/产品名/交易对手主体编号/本方角色/状态/当前条款版本/生效时间/更新时间） | 恒仅本主体参与的合约；`role` = PROVIDER/REQUESTER 可选过滤 |
| R7 | `GET /api/v1/contracts/{contractNo}` | 详情：产品与定价快照 / 双方 / 模板引用（含版本号）/ 状态与终止信息 / 当前条款值全文（含**策略全文**——剧本 C-4.3 S1-2 策略视图承载）/ 逐方签署状态（DID/签署时间；签名值不出站）/ 内容哈希与生效时间 / 存证事件摘要 | 参与方限定；非参与方与"不存在"**同码同文案逐字**（1008C0012）+ 越权留痕；响应字段集显式锚定（无数据本体） |
| R8 | `GET /api/v1/contracts/{contractNo}/clause-versions`（**不分页**——版本数有界，沿"协商链全量呈现"语义；勘误③） | 条款版本历史（版本号/条款值全文/变更明细"从何值→到何值"/提案方/时间/逐方确认时间） | 参与方限定（协商详情默认可见对象） |
| R9 | `POST /api/v1/contracts/{contractNo}/signature-verifications` 签署核验（**无请求体**——核验结论由服务端经 did 三查得出，不得由调用方自报；`{contractNo, results:[…]}` 为**响应体**，勘误④） | `{contractNo, results:[{partyRole, did, result: PASS|FAIL|UNAVAILABLE, reason, verifiedAt}]}`（响应含合约号——勘误④响应列同步） | **合约双方与平台运营方**（规格行为 3 规则 4）；逐方经 did 三查验签；FAIL → 异常处置留痕（VERIFY_SIGNATURE_FAILED）；did 不可达 → 1008S0002（不冒充结论） |

### 2.3 治理面（注解 `@RequirePermission("contract.governance")`，admin 档）

| # | 端点 | 说明 |
| --- | --- | --- |
| W12 | `POST /api/v1/contracts/{contractNo}/force-termination` 强制终止 | 请求 `{reason}`（必填 1~512；缺失/超长 → 1008C0008）；仅 EFFECTIVE；操作者资格 ADMITTED 同受 Q8-A fail-closed 校验（运营方资格异常时强制终止被拒——可用性非安全性，评审循环 2 登记）；非 admin 调用 → 1008C0011 + 拒绝留痕（**应用层判定**——注解 `contract.governance` 之外的越权腿，见 §权限注）；成功 → TERMINATED(GOVERNANCE_FORCE_TERMINATED) + 留痕含理由与操作者 |
| R10 | `GET /api/v1/contracts/governance?status=&pageNum=&pageSize` | 全量合约治理列表（含协商中/已终止）；**每次调用写查看留痕** |
| R11 | `GET /api/v1/contracts/governance/{contractNo}` | 治理详情（合约全文 + **协商过程**（版本链）+ 签署状态 + 存证事件）；**每次调用写查看留痕**（实际登录主体——沿 DB-29/目录域先例） |

> **权限注**：yml 权限映射 = `provider: contract.template.read,contract.read,contract.deal`；`admin: contract.template.read,contract.template.manage,contract.read,contract.deal,contract.governance`。W5~W11 注解 `contract.deal`（admin 亦持——使"非参与方 admin 尝试业务写"能进应用层并被**拒绝留痕**，而非注解层静默 403）；W12 注解 `contract.governance` 仅 admin 持——**非 admin（含 provider）在注解层 403 无留痕**，与规格行为 7 规则 4 的拒绝留痕义务的承载点说明：规格该条判定对象为"非运营方主体直接调用终止他人合约"，由**已认证且持 deal 的参与方/入驻主体经 W10/W8 等对"他人合约"的越权腿**承载（应用层 1008C0012 + DENIED_ACCESS 留痕，见 T7/U 系列）；普通档（无任何角色）与无头一律注解层 401/403、零 action_log 行（沿 3.4.2 T3 V1.2⑧ 先例登记）。

### 2.4 供 3.4.5 的应用层方法（**无 HTTP 端点**，同宿主直调）

| # | 方法（`ContractQueryService`） | 语义 |
| --- | --- | --- |
| QC1 | `loadEffectiveStrategy(contractNo)` | 返回 `ContractStrategySnapshot{contractNo, status, effectiveAt, strategy(解密值对象\|null)}`——生效中合约 = 策略 + 生效时间；未生效 = 空策略；已终止/已完结 = 状态可判（引擎据状态拦截"合约终止 → 策略同步失效"——剧本 C-4.3 S3-7 联动口径，引擎判定归 3.4.5） |

## 3. 错误码表（1008 段续延：`ContractErrorCodes` 追加常量 + `ContractExceptionHandler` 集合/映射同步；C 客户端 / S 系统）

| 码位 | HTTP | 文案/语义 | 落点 |
| --- | --- | --- | --- |
| `1008C0010` | 404 | `产品不存在或未在架，无法发起合约`（不存在/未上架/已下架/已注销**同码同文**——防枚举，沿目录域 1007C0011 口径） | W5 |
| `1008C0011` | 403 | `无权操作该合约`（治理越权等）+ 拒绝留痕 | W12 非 admin；治理类越权 |
| `1008C0012` | 404 | `合约不存在或不可见`（不存在 / 非参与方读写**同码同文逐字**——防枚举）+ 越权留痕 | R6~R8 / 治理外一切非参与方定位 |
| `1008C0013` | 409 | `合约当前状态不允许该操作`（状态门槛：重复确认/重复签署/终态/生效后单方变更/拒签适用面等）+ 留痕（reason 尾号 C0013） | W6~W11 状态门槛 |
| `1008C0014` | 400 | `条款值与模板框架不符`（缺必填槽位/未知槽位键/非字符串/超长——明细入服务端日志、响应仅常量文案） | W5/W6 |
| `1008C0015` | 400 | `策略条款不符合使用控制约定`（至少一项要素或显式"无使用限制"；基础取值非法：次数非正整数/期限倒置/文本空——Q7-A） | W5/W6 提交时；W7 确认时门槛 |
| `1008C0016` | 400 | `合约双方须为不同主体，不可对自己提供的产品发起合约` | W5（剧本 S1-6） |
| `1008C0017` | 409 | `所选模板不可用于发起合约`（不存在/已停用/版本无效**出站统一**，明细入日志——**承接-1"停用模板发起被拒"兑现码**） | W5（QV1 三态） |
| `1008C0018` | 400 | `签署身份不可用，无法签署`（DID 未登记/已吊销/非签署方归属；**同码承载验签异常留痕 reason**——同码双语境沿 1008C0003 先例） | W9；R9 异常留痕 reason_code |
| `1008C0019` | 409 | `合约正在被其他操作修改，请重试`（并发兜底：uk_contract_no / uk_contract_version 撞键转译 + **提案版本指针条件更新未命中**（`status=NEGOTIATING AND current_clause_version=Vn`）——实现按 `DuplicateKeyException` **异常类型**捕获、不解析驱动消息；uk_contract_party 腿在 `FOR UPDATE` 串行化 + 前置存在性判定下不可达，登记不实现（勘误⑤，hifi §10-2 同步） | W5/W6/W9 并发兜底 |
| `1008C0008` | 400 | 通用参数不合法（治理理由缺失/超长、请求体不可读等；追加常量 `CONTRACT_PARAM_INVALID` 复用同码同文——**不新增码位**） | W12 等 |
| `1008S0002` | 503 | `DID 服务暂不可用，请稍后重试`（解析/代签/验签不可达——不冒充签署结论） | W9 / R9 |
| `1008S0003` | 503 | `目录服务暂不可用，请稍后重试`（产品事实不可达——不冒充产品状态） | W5 |

> 拒绝/异常留痕理由 = 上表码位尾号（`ContractErrorCodes.tailOf`，DB-31 口径：直接存码位尾号，不设第二套枚举；复用 3.4.2 既有工具方法）。既有 1008C0001~C0009 / S0001 全部保持原语义零变更。

## 4. 表结构与约束（V2 迁移；命名与审计字段沿 catalog / 模板先例；机密列 `*_cipher` 沿 subject 证照先例）

```sql
-- 1. 合约主表：一行 = 一份合约（发起即协商中；产品/定价/模板版本为发起时快照）
CREATE TABLE contract (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  contract_no VARCHAR(32) NOT NULL COMMENT '合约编号 CO+6位全局序号（序号表原子取号；不可变）',
  product_id BIGINT NOT NULL COMMENT '合约对象产品 id（catalog data_product.id，逻辑引用不建外键）',
  product_name VARCHAR(128) NOT NULL COMMENT '产品名快照（发起时）',
  provider_subject_no VARCHAR(32) NOT NULL COMMENT '提供方主体编号快照（= 发起时产品属主；行为 2 规则 1）',
  requester_subject_no VARCHAR(32) NOT NULL COMMENT '需求方主体编号（发起人；行为 2 规则 1）',
  template_no VARCHAR(32) NOT NULL COMMENT '模板编号（3.4.2 QV1/QV2 校验后锁定）',
  template_version_no INT NOT NULL COMMENT '模板版本快照（引用式锁定：3.4.2 版本行不可变保证稳定性——承接-2）',
  pricing_model VARCHAR(16) NOT NULL COMMENT '定价档快照（FREE/PER_CALL/MONTHLY/REVENUE_SHARE）',
  price_amount DECIMAL(12,2) NULL COMMENT '定价数值快照（档位语义随 pricing_model；免费恒 NULL）',
  current_clause_version INT NOT NULL DEFAULT 1 COMMENT '当前条款版本指针（协商每轮前移）',
  status VARCHAR(32) NOT NULL COMMENT 'NEGOTIATING/PENDING_SIGNATURE/PARTIALLY_SIGNED/EFFECTIVE/COMPLETED/TERMINATED',
  termination_type VARCHAR(32) NULL COMMENT 'TERMINATED 时必填：NEGOTIATION_TERMINATED/SIGNATURE_REFUSED/GOVERNANCE_FORCE_TERMINATED',
  termination_reason VARCHAR(512) NULL COMMENT '强制终止理由（必填场景；业务文本，不含条款原文——沿目录域强制下架理由先例）',
  terminated_by VARCHAR(24) NULL COMMENT '终止操作者（强制终止 = 运营方；两方终止分支 = 发起终止方）',
  effective_at DATETIME NULL COMMENT '生效时间 = 最后一签时间（行为 3 规则 2）',
  ended_at DATETIME NULL COMMENT '已完结/已终止时间（终态时点）',
  release_consent_provider_at DATETIME NULL COMMENT '合意解除·提供方确认时间',
  release_consent_requester_at DATETIME NULL COMMENT '合意解除·需求方确认时间',
  created_by VARCHAR(24) NOT NULL COMMENT '发起操作者（= requester_subject_no）',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_contract_no (contract_no),
  KEY idx_provider (provider_subject_no, created_at),
  KEY idx_requester (requester_subject_no, created_at),
  KEY idx_status (status, created_at)
) COMMENT='合约主表（发起即协商中；产品/定价/模板版本快照；状态机六态+终止分支类型）';

-- 2. 条款版本表：一行 = 一轮条款版本（行级版本化；版本行不可变 = 快照载体；无 updated_at）
-- 条款值/变更明细/规范化原文均为 CAT-04 L3 合约文本 → SM4 密文列（common-crypto 唯一入口，ADR-006）
CREATE TABLE contract_clause_version (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  contract_id BIGINT NOT NULL COMMENT '合约 id（同库逻辑引用不建外键）',
  version_no INT NOT NULL COMMENT '版本号（合约内递增，从 1 起）',
  clause_values_cipher LONGBLOB NOT NULL COMMENT '条款值密文 SM4{JSON: {slots:{槽位键:值}, strategy:{五要素|noRestrictionDeclared}}}',
  changes_cipher LONGBLOB NULL COMMENT '变更明细密文 SM4{JSON:[{slot, from, to}]}（V1 = NULL；"从何值→到何值"承载——留痕表只记版本号）',
  canonical_cipher LONGBLOB NULL COMMENT '规范化原文密文 SM4{canonical text}（双方确认齐锁定时写入 = 签名哈希输入原件）',
  content_hash VARCHAR(64) NULL COMMENT '内容哈希（SM3 hex，锁定时写入；不可逆摘要明文落库——登记口径）',
  proposed_by VARCHAR(24) NOT NULL COMMENT '提案方主体编号',
  proposed_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  confirmed_provider_at DATETIME NULL COMMENT '提供方确认时间（逐版本）',
  confirmed_requester_at DATETIME NULL COMMENT '需求方确认时间（逐版本）',
  UNIQUE KEY uk_contract_version (contract_id, version_no)
) COMMENT='条款版本表（行级版本化、不可变；文本密文列 L3；锁定时固化规范化原文与哈希）';

-- 3. 签署记录表：一行 = 一方签署（签署记录 L3 → 签名值密文；哈希为不可逆摘要明文）
CREATE TABLE contract_signature (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  contract_id BIGINT NOT NULL,
  contract_no VARCHAR(32) NOT NULL COMMENT '冗余编号（读面直取，减少 join）',
  party_role VARCHAR(16) NOT NULL COMMENT 'PROVIDER/REQUESTER',
  subject_no VARCHAR(32) NOT NULL COMMENT '签署方主体编号',
  did VARCHAR(128) NOT NULL COMMENT '签署 DID（did:ctds:{subjectNo}.{seq}，校验归属后落库）',
  content_hash VARCHAR(64) NOT NULL COMMENT '所签内容哈希（= 锁定版本 content_hash）',
  signature_cipher LONGBLOB NOT NULL COMMENT 'SM2 DER 签名（Base64）的 SM4 密文——签署记录 L3 存储保密',
  signed_at DATETIME NOT NULL COMMENT '签署时间（最后一签 = 合约生效时间）',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_contract_party (contract_id, party_role)
) COMMENT='签署记录表（每方一行不可变；签名值密文 L3；双签生效判定依据）';

-- 4. 存证事件表：一行 = 一次生效存证（合约哈希 + 双方签署要素；链上对接归 3.8.x，预留埋点口径）
CREATE TABLE contract_attestation (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  contract_id BIGINT NOT NULL,
  contract_no VARCHAR(32) NOT NULL,
  content_hash VARCHAR(64) NOT NULL COMMENT '合约哈希（= 锁定版本 SM3；3.8.3"合约"环节埋点消费）',
  provider_subject_no VARCHAR(32) NOT NULL, provider_did VARCHAR(128) NOT NULL,
  provider_signed_at DATETIME NOT NULL,
  requester_subject_no VARCHAR(32) NOT NULL, requester_did VARCHAR(128) NOT NULL,
  requester_signed_at DATETIME NOT NULL,
  attested_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '存证登记时间（= 双签生效时点）',
  UNIQUE KEY uk_attestation_contract (contract_id)
) COMMENT='存证事件表（合约哈希+双方签署要素；V1.0 落库留痕，链上对接归 3.8.x）';

-- 5. 合约统一留痕表：四要素 + 理由码 + from→to（版本号或状态）；不含敏感原文（值级明细在版本密文列）
CREATE TABLE contract_action_log (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  contract_no VARCHAR(32) NULL COMMENT '合约编号（定位前拒绝/不存在场景为 NULL）',
  version_no INT NULL COMMENT '动作涉及条款版本号（有则记）',
  action VARCHAR(32) NOT NULL COMMENT 'CREATE发起/PROPOSE提案/CONFIRM确认/TERMINATE_NEGOTIATION协商终止/SIGN签署/REFUSE_SIGN拒签终止/ATTEST存证/RELEASE_CONSENT合意解除确认/FORCE_TERMINATE强制终止/GOVERNANCE_VIEW治理查看/VERIFY_SIGNATURE_FAILED验签异常/DENIED_ACCESS越权拒绝（值域封闭 12 值）',
  actor_subject_no VARCHAR(24) NOT NULL COMMENT '操作者主体编号（治理查看 = 实际登录主体）',
  reason_code VARCHAR(16) NULL COMMENT '拒绝/异常理由码尾号（如 C0012/C0013/C0018/S0002——DB-31 口径）',
  from_value VARCHAR(64) NULL COMMENT 'PROPOSE: 旧版本号；SIGN/TERMINATE_*: 旧状态；RELEASE_CONSENT: 状态',
  to_value VARCHAR(128) NULL COMMENT 'PROPOSE: 新版本号；SIGN: 新状态；终止: TERMINATED；RELEASE_CONSENT: EFFECTIVE/COMPLETED；CONFIRM: 确认版本号',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY idx_log_contract (contract_no, created_at)
) COMMENT='合约统一留痕表（只插不改；不含敏感原文；拒绝与验签异常同表）';

-- 6. 合约编号序号表：全局 1 行原子自增（不按日重置；CO+6 位）
CREATE TABLE contract_no_seq (
  id TINYINT PRIMARY KEY, next_no INT NOT NULL
) COMMENT='合约编号序号表（全局 1 行；初始 next_no = 1）';
```

> 跨表引用一律同库逻辑引用不建外键（沿 catalog / 模板先例）；排序规则落 MySQL 8 默认（无应用层归一化判重需求，除外无第二套口径）；`contract_clause_version` 无 UPDATE 内容路径（仓储接口无该方法——编译期保证快照不可变）；**V1 迁移四表与种子零改动**。

## 5. 状态机与事务

| 用例 | 事务（同库 `@Transactional`） | 并发与兜底 |
| --- | --- | --- |
| W5 发起 | 门槛链（§5.1）→ 取号（`nextContractNoSeq`，`@Transactional` 连接绑定——沿 V1.2⑨）→ INSERT contract + INSERT 版本 V1（条款值密文）+ INSERT 留痕 CREATE（同事务） | 幂等键命中重放返回首次；撞 `uk_contract_no` → 1008C0019（按 `DuplicateKeyException` 异常类型转译——勘误⑤循环 2 正文闭合，§10） |
| W6 提案 | 状态/参与方/交替门槛 → INSERT 版本 Vn+1（密文 + 变更明细）+ UPDATE `current_clause_version` + INSERT 留痕 PROPOSE（from=Vn→to=Vn+1） | 并发撞 `uk_contract_version` → 1008C0019 |
| W7 确认 | 事务内 FOR UPDATE 读回合约行**状态与当前版本指针**（勘误⑦）→ 复判确认命令版本 = 锁下指针（不等 → STATE_CONFLICT → 1008C0013 + 留痕——确认×提案交错拒绝，评审循环 2 P0）→ 条件更新确认列（`UPDATE ... SET confirmed_x_at=? WHERE contract_id=? AND version_no=? AND confirmed_x_at IS NULL`，影响行数=1 才算本次确认）→ 复查双方齐 → 锁定时计算 canonical + content_hash 写回该行（`content_hash IS NULL` 条件封口 + 影响行数校验——勘误⑧）+ UPDATE contract.status=PENDING_SIGNATURE（带 `AND current_clause_version=?` 前置 + 影响行数校验——纵深防御，勘误⑧）+ INSERT 留痕 CONFIRM | 同伴重复确认 → 影响行数 0 → 1008C0013 + 留痕；双方同时确认 → 行锁串行化，后到者读回最新状态与指针后同事务复查 → 恰一次锁定；确认×提案 latch 交错 → 指针复判不等拒绝、恢复后按当前版本重确认可锁定（T10 交错用例锚定） |
| W9 签署 | **did 调用在事务外先行**（解析 → 归属/状态校验 → 代签）→ 事务内 FOR UPDATE 读回状态 → 条件更新 `UPDATE contract SET status=? WHERE id=? AND status=?`（影响行数=1）→ INSERT signature（密文）→（后签）INSERT attestation + `effective_at=now` + INSERT 留痕 SIGN（+ATTEST） | 双方同时签署：FOR UPDATE 行锁串行化，后到者读回最新状态（对方已签 → 以 PARTIALLY_SIGNED 进入本次签署）→ 双签生效；撞 `uk_contract_party` 腿在行锁串行化 + 本角色未签前置判定下**不可达**、不转译（勘误⑤）；状态转移影响行数 ≠ 1 = 行锁串行化下不可达断言腿（回滚 fail-stop——勘误⑧） |
| W8/W10/W11/W12 | 状态门槛 → 条件更新（同款影响行数判定——≠1 为行锁串行化下不可达断言腿，勘误⑧）→ 留痕（W11 每方一条 RELEASE_CONSENT；第二方 to=COMPLETED + 写 ended_at） | 并发终止/解除互斥靠行锁 + 条件更新 |
| 拒绝/异常留痕 | 独立事务写入（主链回滚不影响留痕——沿 3.4.2 / catalog 先例） | — |

**5.1 发起门槛链（W5，顺序固定）**：① 需求方 ADMITTED（SubjectAdmissionPort 三态：NOT_ADMITTED → 1008C0003 发起语境文案；UNAVAILABLE → 1008S0001）；② 产品事实（CatalogProductPort：NOT_FOUND / status≠LISTED → 1008C0010 同码同文；UNAVAILABLE → 1008S0003）；③ 需求方 ≠ 提供方（→ 1008C0016）；④ 模板 QV1 `validateForInitiation`（无效三态 → 1008C0017——**承接-1**）+ QV2 `loadFramework`；⑤ 条款值校验（§6.3）；⑥ 锁定快照写库。**下架不联动**：既有合约零副作用路径（无产品状态回写；效力不变由"无联动"天然成立，测试锚定）。

## 6. 加密、哈希与存证（L3 承载口径）

**6.1 存储保密（CAT-04 L3，规格行为 7 规则 4 + 分级规范 §5 矩阵）**：条款值 / 变更明细 / 规范化原文 / 签名值四类密文列，加解密只发生在应用层，经 `common-crypto` `Sm4Service`（ADR-006 唯一入口）；密钥源 = 本地密钥文件或 KMS 托管二选一（`ctds.crypto.local.key-file` / `ctds.crypto.kms.base-url`，沿 subject 先例）；keyRef = `ctds.contract.deal-key-ref`（默认 `contract-deal-text`）；未配置密钥源 → 加密操作 fail-closed（`1001S0001/S0002` 语义沿组件原样）。**哈希（SM3 hex）为不可逆摘要、明文落库**（登记口径：摘要不可反推原文，且为验签与 3.8.x 埋点直读所需）。

**6.2 规范化与哈希（`ContractCanonicalizer`）**：输入 = `contractNo + productId + productName + providerSubjectNo + requesterSubjectNo + templateNo + templateVersionNo + pricingModel + priceAmount(plain 字符串) + clauseValues.slots(按槽位键排序) + strategy`；输出 = 固定字段序紧凑 JSON（UTF-8）→ `Sm3Service.digestHex`；**链上/库内一致性**：锁定写入 `canonical_cipher`（原文可审计）+ `content_hash`；T5 断言"解密原文重算哈希 = 落库哈希"。数值（次数）以 JSON number 承载，规范化不依赖用户键序（槽位键排序 + 策略固定字段序）。

**6.3 条款值校验（`ClauseValues`）**：按锁定模板版本的槽位框架（3.4.2 QV2 `loadFramework` 全文）——必填槽位齐备、无未知槽位键、值为字符串、单值 ≤2000 字符、总量 ≤32768 字节（超限 → 1008C0014）；**策略条款（`UsageControlPolicy`，Q7-A 最小门槛）**：五要素（次数 quota{enabled,maxCount}/期限 term{enabled,startDate,endDate}/用途 purpose{enabled,text}/域内 territory{enabled,text}/禁止再分发 noRedistribution{enabled}）+ `noRestrictionDeclared` 显式声明；**提交时**校验基础取值（次数为正整数、期限起止有序且格式合法、启用要素文本非空）→ 1008C0015；**确认锁定**时校验"至少一项启用 **或** 显式声明"（行为 4 规则 2）→ 1008C0015；**完整 DSL 语法与空间策略模型形态对齐归 3.4.4**（本结构为承载口径，交接登记 §10）。

**6.4 签署时序（W9）**：① 读合约与锁定版本（状态门槛/参与方/资格/本角色未签）；② `DidPort.resolve(did)`：未登记/非 ACTIVE/`document.controller ≠ 操作者主体` → 1008C0018；③ `DidPort.sign(did, contentHash)`（服务身份头 `contract-service`/`contract-internal` 调 did 演示签名入口；入口关闭/未启用 → 按 did 侧 1000C0003 答复转 1008S0002 不可用语义，不冒充）；④ 事务条件更新 + 签名密文落库 +（后签）存证事件 + 留痕。**验签（R9）**：`DidPort.verify(did, base64(contentHash), base64(signature))` 三查；逐方结果出站；FAIL/ UNAVAILABLE 写 VERIFY_SIGNATURE_FAILED（reason = C0018 / S0002 尾号）；did 整体不可达 → 1008S0002。

## 7. 测试计划（Testcontainers 实跑；Skipped 0 口径；映射任务卡 §三与 C-4.2 剧本 19 判定点）

| 锚 | 覆盖 | 关键断言 |
| --- | --- | --- |
| T1 迁移与结构探针 | Q1 | 6 表齐 + 唯一键/索引 + 序号表初始 next_no=1 + 四类 `*_cipher` 密文列（四列·两张表：contract_clause_version 三列 + contract_signature 一列）为 LONGBLOB（勘误⑥，循环 2 计数勘正） |
| T2 发起门槛与幂等 | 行为 2 规则 1/2/3；剧本 S1-1/4/5/6/7 | 成功（协商中 + V1 + 快照 + 定价快照 + 留痕 CREATE）；未入驻统一文案；产品不存在/DRAFT/DELISTED/CANCELLED 同码同文；提供方本人 → C0016；**停用模板 → C0017（承接-1）**；模板不存在/版本无效 → C0017；条款值不符框架 → C0014；策略取值非法 → C0015；catalog 不可达 → S0003 不冒充；幂等重放合约数不变返回首次 |
| T3 快照稳定性（承接-2） | C-4.1 S2-3/S2-5 | 直调 3.4.2 修订端点出新版本 → 草案详情仍按锁定版本；停用模板 → 草案可继续提案/确认/签署；同时新发起被拒（C0017） |
| T4 协商链 | 行为 2 规则 4/5；剧本 S1-2/3/8 | 反提案版本 +1 + 变更明细 from→to + 留痕版本号 from→to；交替违反 → C0013 + 留痕；逐方确认 + 重复确认拒绝；双方齐 → 待签署 + 哈希生成（canonical 密文非空）；协商终止 → 终态 + 留痕 |
| T5 签署与存证 | 行为 3 规则 1/2/5/6；剧本 S2-1/2 | DID 未登记/已吊销/非本主体 → C0018；先签 → 部分签署 + 签名密文 + 留痕；后签 → 生效 + `effective_at` = 后签时间 + 存证事件（哈希/双方要素）+ ATTEST 留痕；**哈希重算一致**（解密 canonical 重算 == content_hash）；did 不可达 → S0002 |
| T6 终止与解除 | 行为 3 规则 3；行为 6 规则 1/2/4；剧本 S2-4/5、S3-3/4/5 | 部分签署态拒签终止成功（含已签方撤回）；生效后拒签/单方动作 → C0013 + 留痕（S2-5）；合意解除逐方确认 → 已完结 + 留痕（第二方 to=COMPLETED）；强制终止（缺理由 C0008 / 成功含理由与操作者 / 非 admin 越权腿 → C0012 或 C0011 + 留痕）；终态一切写动作 → C0013 |
| T7 可见性与治理 | 行为 7 规则 1/2/3；剧本 S3-1/2/4 | 非参与方读/写与"不存在"**逐字同形**（1008C0012 对照）+ DENIED_ACCESS 留痕；治理列表/详情 → GOVERNANCE_VIEW 留痕（实际登录主体）；直调变体同拒；无头 401 / 普通档 403 注解层、零 action_log 行（沿 3.4.2 T3 口径） |
| T8 策略门槛与失效锚 | 行为 4 规则 1/2/4/5/6；剧本 C-4.3 S1-2/3/4/5 | 确认时缺策略且无声明 → C0015（S1-4）；次数负数/期限倒置提交拦截（S1-3）；显式"无使用限制"对照组可锁定；生效后单方变更类动作 → C0013（S1-5）；策略全文双方可查、非参与方拒绝（S1-2） |
| T9 密文落库探针 | 行为 7 规则 4/5 | JDBC 直读四类密文列**不含明文片段**（槽位值/签名 Base64 逐串反查）；API 解密读取正确；留痕表字段集锚定（无敏感原文） |
| T10 并发兜底 | §5 | 提案并发（两线程同版本）→ 一成一败（C0019/状态重判）；双方同时签署（latch 握手）→ 终局一致（部分签署 + 生效恰一次，或败者 C0019）；**确认×提案 latch 交错**（确认线程停在资格窗口内 + 对方先确认 V1 再提案 V2）→ 锁下指针复判拒绝（C0013 + 留痕）、恢复后按当前版本重确认可锁定——不可签死状态不可达（循环 2 P0 锚，先红后绿）；**确认锁定窗口内提案** → 指针/状态条件更新未命中腿杀手（事务回滚 + C0019，合约不受扰动） |
| T11 读面与字段集 | 行为 7 规则 4；剧本 S2-3 | R6 恒仅本人参与；R7 字段集锚定（含策略全文、无数据本体、签名值不出站）；R8 版本历史逐版全文；R9 验签 PASS（对照：篡改哈希 → FAIL + 留痕） |
| T12 下游接口 | 移交-5 | `loadEffectiveStrategy`：生效中 → 策略 + 生效时间；未生效 → 空策略；已终止 → 状态可判（策略失效判定归 3.4.5） |
| U 单元锚 | — | `ClauseValues`/`UsageControlPolicy` 校验矩阵；`ContractCanonicalizer` 稳定性（同值不同键序 → 同哈希；数值/日期边界）；状态机 Guard 全转移表；`CatalogProductClient`/`DidClient` 三态（JDK HttpServer 桩，沿 SubjectAdmissionClientTest 先例）；错误码↔出站处理器一致性测试扩展（新码同步登记） |

> 既有服务改动随卡测试：**catalog**（内部端点权限矩阵：contract-internal 过 / 业务角色拒；字段集锚定；不存在转译；原始状态值含 DELISTED 等）；**did**（演示签名入口权限点矩阵：admin 过 / contract-internal 过 / 其他拒；开关默认关闭语义与既有 1000C0003 不变）。集成测试密钥源 = 临时密钥文件注入（沿 subject 测试先例），did/catalog/subject 端口 `@MockitoBean` 或 HttpServer 桩。

## 8. 数据分级落级表（hifi 定稿 + 分级规范 §6.1 回写同步行）

| 表 | 级别 | 依据 |
| --- | --- | --- |
| contract / contract_no_seq | **L1** | 运营状态元数据与序号（产品名/定价随目录公开口径，沿 3.3.5 复核结论）；不含条款全文 |
| contract_clause_version | **CAT-04 L3** | 合约文本（条款值/变更明细/规范化原文）——**SM4 密文列**承载 |
| contract_signature | **CAT-04 L3** | 签署记录——签名值密文；哈希为不可逆摘要明文 |
| contract_attestation | **CAT-04 L3** | 签署存证（哈希 + 签署要素） |
| contract_action_log | **CAT-06 L3** | 安全审计数据（沿审计明细/空间/资源/模板留痕先例）；零敏感原文（列名与理由码口径） |

> 回写分级规范 §6.1 补六行（同步动作非需求变更，沿 3.2.2 Q9 / 3.3.2 Q8 / 3.4.2 先例）；**L3 读取审计诚实登记**：治理查看留痕（行为 7 规则 2）本卡已落；其余读取的集中审计随 G-07 缺口维持"待生效"（分级规范 §5 依赖提示，登记事实不虚报）。

## 9. 配置与部署

- `application.yml` 追加：`ctds.contract.catalog.base-url: ${CTDS_CONTRACT_CATALOG_BASEURL:}`、`ctds.contract.did.base-url: ${CTDS_CONTRACT_DID_BASEURL:}`、`ctds.contract.deal-key-ref: ${CTDS_CONTRACT_DEAL_KEY_REF:contract-deal-text}`、`ctds.crypto.local.key-file: ${CTDS_SM4_KEY_FILE:}`（KMS 托管走 `CTDS_CRYPTO_KMS_BASEURL`，组件原生支持）；权限映射追加（§2.3 注）；未配置 base-url = 对应通道不可用（服务可独立启动，fail-closed 不冒充）；
- **既有服务改动清单（最小必要，随卡留痕）**：
  1. **catalog-service**：新增 `InternalProductController`（`GET /api/v1/catalog/internal/data-products/{productId}`，`@RequirePermission("catalog.internal.read")`；应用服务方法走 `ProductCatalogQueryService` 分层）+ yml 1 行 `contract-internal: catalog.internal.read` + 不存在复用 1007C0011（零新增 catalog 码）+ 随卡测试；**ADR-016 §6 衔接补记**（目录服务内部只读端点 + contract 第 6 服务消费登记 + 最小暴露字段集）；
  2. **did-service**：`DidDemoSignatureController` 注解权限点 `did.admin` → **`did.demo.signature`**；yml = `admin: did.admin,did.demo.signature` + `contract-internal: did.demo.signature`（2 行）；随卡测试；**ADR-017 补记**（演示签名入口服务间消费登记 + 最小权限收敛；生产禁用边界与"签名能力可外借"诚实边界沿既有登记不变）；
  3. subject / space / kms / example：零改动；
- deploy/k8s：`contract-deployment.yaml` 追加 `CTDS_CONTRACT_CATALOG_BASEURL` / `CTDS_CONTRACT_DID_BASEURL` 两 env + 注记（演示期需配置密钥源 env 与 `CTDS_DID_DEMO_SIGNATURE_ENABLED=true`；口令类一律激活期注入，不入仓库——红线 7）。

## 10. 备忘（设计与实现对照义务）

1. **F10（Q9-A）**：规格 §6-1 单行补引 `DB-03`（关闭状态 + 长期解除条件），随本卡设计确认批次落稿；未确认前不触碰规格正文；
2. **P3-N1（跟踪-6）**：W5/W6 的并发兜底 = 撞唯一索引（`uk_contract_no` / `uk_contract_version`）按 `DuplicateKeyException` **异常类型**捕获转译 + W6 指针条件更新未命中转译（**不解析驱动异常消息**，故换驱动回归面小于原登记口径）；`uk_contract_party`（W9）经 `FOR UPDATE` 串行化 + 本角色未签前置判定后**不可达**，登记不实现转译；换驱动场景仍列入回归清单：迁移/升级 MySQL 驱动或连接池时须回归 T10 并发腿（沿 3.4.2 独立复审 P3-N1 口径；勘误⑤）;**跨域口径分歧登记（勘误⑤循环 2 闭合）**：3.4.2 模板域并发兜底**真按约束名区分**（`ContractTemplateAppService` 解析约束名分流 C0005/C0009），本域按**异常类型**捕获不区分——两域口径并存，各自登记换驱动回归清单（模板域回归面含约束名解析、合约域不含），收敛评估随收敛卡复核；
3. **诚实边界（沿既有登记，不重复展开）**：V1.0 签署 = 演示期平台代签入口（真实 SM2、私钥不出 KMS、不可抵赖）；生产须主体侧签名通道（3.5.2/3.9.1 解除条件）；第三方电子签资质不在 V1.0（规格 §6-3）；
4. **O1/O2 观察项不在本卡落笔**（随本域交付期剧本修订经 PO 批准统一处理）；
5. **性能敏感路径豁免**：合约域为低频业务对象，无网关/检索/计量类热路径（AGENTS §4 基准义务不触发）；
6. **密码学零自实现**：SM2/SM3/SM4 全部经 common-crypto / KMS（红线 7）；本卡不新增密码学入口；
7. **下游衔接**：3.4.4（策略 DSL 形态对齐与完整校验——本卡承载结构为最小口径，若其定稿演进须由其设计并做兼容映射，禁止两处并行定义）；3.4.5（QC1 读取方法 + 终止/完结 → 策略失效的引擎侧判定）；3.4.7（界面入口占位核对修订）；3.8.3（存证事件埋点消费本卡 attestation 口径）；
8. **剧本承载**：C-4.2 三幕 19 判定点（T2~T7/T11 锚）+ C-4.1 S2-3/S2-5（T3 锚，承接两项）+ C-4.3 S1-2/3/4/5（T8 锚）；C-4.3 S3-7（强制终止 → 策略失效）的引擎侧判定归 3.4.5；
9. 本卡零门禁配置改动、零 ADR 正文改动（仅 §6 补记两处，沿衔接登记先例）；跨服务只读 client 副本计数 +2（跟踪-7）。

---

## 确认记录

> **确认留痕（2026-10-05 22:3x，编排师会话回复"**都按建议**"）**：**Q1~Q9 均采建议口径 A + D1 不拆分**——本文件转 **V1.0（编码契约）**（正文口径全部生效），lofi 同批转 V1.0，实现进入编码阶段（测试先行）；Q9-A 规格 §6-1 单行补引 `DB-03` 同批落稿（F10 兑现）。

> **草案留痕（2026-10-05 22:1x，立卡批 `44d469c`）**：V0.9 随立卡批提交；踏勘口径（did 演示签名入口现状 `did.admin` + 开关默认关闭、catalog 无内部端点、L3 存储加密要求、contract-service 既有类名占用清单）已并入正文与 Q5/Q6/Q1。

---

## 勘误记录（2026-10-06 4 视角评审修复批 R1 勘误①~⑦ + 修复批 R2 增补⑧并重写⑦；正文以「勘误①~⑧」标注同步处）

> **性质与依据**：4 视角评审（规格与设计符合性 / 安全与供应链 / 一致性与重复 / 测试质量）对编码批 `d9b8488` 与本文逐条比对，发现**契约表述**与实现/事实不一致之处（实现侧为主链路正确，勘误为契约与事实对齐）；本记录**业务判定零变更**（无新增/删除功能行为），仅口径与登记对齐。变更走任务卡 §四状态行与台账同步留痕。

| # | 勘误对象 | 原文口径 | 订正口径 | 依据 |
| --- | --- | --- | --- | --- |
| ① | §2.1 W5/W6 请求体 | 含客户端字段 `requestFingerprint` | **服务端派生**（控制器按条款值存储形态稳定哈希预计算注入命令；客户端不传）；§2.1 请求体列已去该字段 | `DealRequests.java` / `ContractCommandController.java`（幂等键成分不可由客户端拼装） |
| ② | §2.1 W6 并发口径 | 仅"撞 uk_contract_version → 1008C0019" | 补：版本指针前移带 `status=NEGOTIATING AND current_clause_version=Vn` 前置，未命中 → 事务回滚 + **1008C0019** | 修复批实现（评审 P1-① 后半：指针更新缺前置条件） |
| ③ | §2.2 R8 | 端点带 `?pageNum=&pageSize=` | **不分页**（版本数有界，协商链全量呈现） | `ContractQueryController.clauseVersions`（实现未分页，契约笔误） |
| ④ | §2.2 R9 | `{results:[…]}` 列在**请求体**列 | **无请求体**；`{contractNo, results:[…]}` 为**响应体**（核验结论由服务端经 did 三查得出，不得由调用方自报——与"不冒充结论"口径一致） | `ContractQueryController.verify` / `DealViews.Verification` |
| ⑤ | §3 `1008C0019` + §10-2 | 三个唯一键"按约束名转译" | 按 `DuplicateKeyException` **异常类型**捕获（不解析驱动消息）+ W6 指针条件更新未命中；**uk_contract_party 腿不可达**（`FOR UPDATE` 串行化 + 本角色未签前置判定），登记不实现转译 | 修复批复核（评审视角一 P1-② 判为文档勘正、视角二 P2-3）；**循环 2 闭合**：§5 W5/W9 正文与代码注释三处同批订正 + 跨域口径分歧登记（§10-2） |
| ⑥ | §7 T1 | "四个 `*_cipher` 列" | **四类密文列·四列·两张表**（clause_values / changes / canonical 三列落 contract_clause_version + signature 一列落 contract_signature），T1 断言覆盖四类无漏测 | V2 迁移 DDL 与 T1 断言；循环 2 计数勘正（R1 订正"五类…四张表列"有误） |
| ⑦ | §5 W7 确认语义 | 未明示确认对象 | **确认对象机制（R2 修后实况重写）**：W7 无版本入参，确认命令携带应用层所读版本号；仓储在 `FOR UPDATE` 行锁下读回合约行**状态与当前版本指针**并复判——命令版本 ≠ 锁下指针（确认×提案交错，版本已被并发提案前移）→ STATE_CONFLICT → 1008C0013 + 拒绝留痕；纵深防御：锁定转态句带 `AND current_clause_version=?` 前置。已确认版本的不可改写与"锁定对象 = 当前版本"由 T10 锚定（`content_hash IS NULL` 封口 + 锁定值不可改写 + 代签入参 = 锁定哈希 + 交错拒绝） | **R1 版表述"readStatusForUpdate 行级锁下读回当前指针"在 R1 时点与实现不符**（实现仅读状态），评审循环 2 三视角推演 + 编排师逐行核验证伪并确证"确认×提案交错发散可达"；修复批 R2 落实（t10 交错用例先红后绿：红相 = 交错确认 200 待签署而指针已前移，绿相 = 409 拒绝 + 恢复锁定） |
| ⑧ | §5 全部写面影响行数判定腿 + `insertLog` 调用契约 | confirm 转态/锁定、release COMPLETED 未取影响行数；sign/terminate ISE 全仓无 catch 出站 500 与 §5"再失败 → C0019"表述张力 | **定性登记：行锁串行化下不可达断言腿**——confirm/sign/terminate/releaseConsent 的影响行数 ≠ 1 腿在 `FOR UPDATE` 同事务先行复判后不可达，命中只能是实现缺陷 → 抛 `IllegalStateException` 整事务回滚 fail-stop（出站 500），**不转译 1008C0019**（诚实失败优于吞错，红线"禁止吞异常"）；confirm 转态/锁定、release COMPLETED 三处补齐影响行数校验（类注释"四方法均影响行数判定"自此逐字成立）；`insertLog` 调用契约登记于端口方法（拒绝类留痕仅在应用层无事务上下文调用 = 独立落库；主链内调用随主链事务） | 评审循环 2 P2-5/P2-8/P2-9（"转译 C0019 或登记不可达"二选一之**登记臂**）；修复批 R2 实现 |

> **同批登记（非契约勘误）**：跟踪-8 = contract-service 缺 `SharedContainerGuardTest`（subject/catalog/did/space/example 五处均有"容器用例必须带 `disabledWithoutDocker`"守卫，本模块缺席——建议随收敛卡补齐）；`ContractActionLog` 类名 vs §1 骨架 `contract-action_log` 为 Java 合法命名变体（无需改动）。**修复批 R2 增补登记（评审循环 2）**：① 资格失效留痕 contract_no 收口——已定位合约的六写语境必传合约号（DDL"定位前拒绝/不存在场景为 NULL"语义收口；发起场景合约尚不存在传 NULL），T2 六语境用例加定向断言；② W12 治理路径同受 `requireAdmitted` 门槛（Q8-A fail-closed）登记入 §2.3 W12——运营方资格异常时强制终止被拒，可用性非安全性；③ 治理注解层（`@RequirePermission("contract.governance")`）无独立测试锚的局限登记——HTTP 面 W12 越权腿由注解先行挡住（不可达），应用层第二道判定以直调测试 `ContractGovernanceForbiddenTest` 覆盖，读面注解 403 由 T7 覆盖；④ "先红后绿"证据纪律：R1 批 9 例的红相证据未留存、按"未能验证"登记改述（R2 批起红相证据强制留存——R2 交错用例红相已留存于修复段日志）；⑤ T3 零目录交互腿定位改述为"端到端锚 + 回归哨兵"（读面零 catalog 引用）。