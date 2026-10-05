-- 合约协商与电子签署（WBS-3.4.3，hifi §4 定稿；规格 docs/specs/C-4.1-4.3-数字合约与使用控制.md V1.0
-- 行为 2 / 3 / 6 / 7 + 行为 4 本卡承载面）。六表承载：contract（合约主表：一行 = 一份合约，
-- 发起即协商中，产品/定价/模板版本为发起时快照——行为 2 规则 3）、contract_clause_version
-- （条款版本表：行级版本化、版本行不可变 = 快照载体，变更明细"从何值→到何值"——行为 2 规则 4）、
-- contract_signature（签署记录表：每方一行不可变，双签生效判定依据——行为 3 规则 1/2）、
-- contract_attestation（存证事件表：合约哈希 + 双方签署要素，链上对接归 3.8.x、预留埋点口径——行为 3 规则 5）、
-- contract_action_log（合约统一留痕表：四要素 + 理由码 + from→to，只插不改，不含敏感原文——行为 2 规则 6 /
-- 行为 7 规则 5）、contract_no_seq（合约编号序号表：全局 1 行原子取号，CO+6 位不按日重置）。
-- 数据分级落级（WBS-3.4.3 hifi §8，分级规范 §6.1 同步行）：contract / contract_no_seq = L1
-- （运营状态元数据与序号；产品名/定价随目录公开口径，沿 3.3.5 复核结论；不含条款全文）；
-- contract_clause_version / contract_signature / contract_attestation = CAT-04 L3（合约文本与签署记录）
-- ——条款值/变更明细/规范化原文/签名值四类走 SM4 密文列（common-crypto 唯一入口，ADR-006；CAT-04
-- L3 存储保密硬约束），内容哈希为不可逆 SM3 摘要、明文落库（登记口径：摘要不可反推原文，且为
-- 验签与 3.8.x 埋点直读所需）；contract_action_log = CAT-06 L3（安全审计留痕，零敏感原文——
-- 值级明细在加密版本行可查，本表只记版本号与状态号）。
-- 跨表引用一律同库逻辑引用不建外键（沿 catalog / V1 模板先例）；跨库引用（subject_no →
-- ctds_subject.subject、product_id → ctds_catalog.data_product.id）同样逻辑引用（ADR-016 §6 口径）。
-- 排序规则落 MySQL 8 默认 utf8mb4_0900_ai_ci（无应用层归一化判重需求，除外无第二套口径）。

-- 1. 合约主表：一行 = 一份合约（发起即协商中；状态机六态 + 终止分支类型——行为 6 规则 1）。
-- 产品/定价/模板版本为发起时快照（模板版本快照 = 引用式锁定：3.4.2 版本行不可变保证稳定性，
-- 模板后续修订/停用不影响既有合约——行为 2 规则 3、承接-2；产品下架不联动既有合约效力——
-- 行为 6 规则 3，无产品状态回写路径）。
CREATE TABLE contract (
    id                          BIGINT        NOT NULL AUTO_INCREMENT COMMENT '技术主键（对外 REST 逻辑键 = contract_no）',
    contract_no                 VARCHAR(32)   NOT NULL COMMENT '合约编号 CO+6位全局序号（序号表原子取号；不可变）',
    product_id                  BIGINT        NOT NULL COMMENT '合约对象产品 id（catalog data_product.id，逻辑引用不建外键）',
    product_name                VARCHAR(128)  NOT NULL COMMENT '产品名快照（发起时）',
    provider_subject_no         VARCHAR(32)   NOT NULL COMMENT '提供方主体编号快照（= 发起时产品属主；行为 2 规则 1）',
    requester_subject_no        VARCHAR(32)   NOT NULL COMMENT '需求方主体编号（发起人；行为 2 规则 1）',
    template_no                 VARCHAR(32)   NOT NULL COMMENT '模板编号（3.4.2 QV1/QV2 校验后锁定）',
    template_version_no         INT           NOT NULL COMMENT '模板版本快照（引用式锁定：3.4.2 版本行不可变保证稳定性——承接-2）',
    pricing_model               VARCHAR(16)   NOT NULL COMMENT '定价档快照（FREE/PER_CALL/MONTHLY/REVENUE_SHARE）',
    price_amount                DECIMAL(12,2) NULL COMMENT '定价数值快照（档位语义随 pricing_model；免费恒 NULL）',
    current_clause_version      INT           NOT NULL DEFAULT 1 COMMENT '当前条款版本指针（协商每轮前移）',
    status                      VARCHAR(32)   NOT NULL COMMENT 'NEGOTIATING/PENDING_SIGNATURE/PARTIALLY_SIGNED/EFFECTIVE/COMPLETED/TERMINATED',
    termination_type            VARCHAR(32)   NULL COMMENT 'TERMINATED 时必填：NEGOTIATION_TERMINATED/SIGNATURE_REFUSED/GOVERNANCE_FORCE_TERMINATED',
    termination_reason          VARCHAR(512)  NULL COMMENT '强制终止理由（必填场景；业务文本，不含条款原文——沿目录域强制下架理由先例）',
    terminated_by               VARCHAR(24)   NULL COMMENT '终止操作者（强制终止 = 运营方；两方终止分支 = 发起终止方）',
    effective_at                DATETIME      NULL COMMENT '生效时间 = 最后一签时间（行为 3 规则 2）',
    ended_at                    DATETIME      NULL COMMENT '已完结/已终止时间（终态时点）',
    release_consent_provider_at DATETIME      NULL COMMENT '合意解除·提供方确认时间',
    release_consent_requester_at DATETIME     NULL COMMENT '合意解除·需求方确认时间',
    created_by                  VARCHAR(24)   NOT NULL COMMENT '发起操作者（= requester_subject_no）',
    created_at                  DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at                  DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_contract_no (contract_no),
    KEY idx_provider (provider_subject_no, created_at),
    KEY idx_requester (requester_subject_no, created_at),
    KEY idx_status (status, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '合约主表（发起即协商中；产品/定价/模板版本快照；状态机六态+终止分支类型）';

-- 2. 条款版本表：一行 = 一轮条款版本（行级版本化；版本行不可变 = 快照载体；无 updated_at，
-- 仓储无条款内容更新路径——编译期保证）。条款值/变更明细/规范化原文均为 CAT-04 L3 合约文本
-- → SM4 密文列（common-crypto 唯一入口，ADR-006）。规范化原文与内容哈希在双方确认齐锁定时
-- 写入（canonical = 签名哈希输入原件；content_hash = SM3 hex，不可逆摘要明文——登记口径）。
-- 变更明细 = 相对上一版的"从何值→到何值"（V1 = NULL；留痕表只记版本号，值级明细在此密文列）。
CREATE TABLE contract_clause_version (
    id                     BIGINT      NOT NULL AUTO_INCREMENT COMMENT '技术主键',
    contract_id            BIGINT      NOT NULL COMMENT '合约 id（contract.id，同库逻辑引用不建外键）',
    version_no             INT         NOT NULL COMMENT '版本号（合约内递增，从 1 起）',
    clause_values_cipher   LONGBLOB    NOT NULL COMMENT '条款值密文 SM4{JSON: {slots:{槽位键:值}, strategy:{五要素|noRestrictionDeclared}}}',
    changes_cipher         LONGBLOB    NULL COMMENT '变更明细密文 SM4{JSON:[{slot, from, to}]}（V1 = NULL；"从何值→到何值"承载——留痕表只记版本号）',
    canonical_cipher       LONGBLOB    NULL COMMENT '规范化原文密文 SM4{canonical text}（双方确认齐锁定时写入 = 签名哈希输入原件）',
    content_hash           VARCHAR(64) NULL COMMENT '内容哈希（SM3 hex，锁定时写入；不可逆摘要明文落库——登记口径）',
    proposed_by            VARCHAR(24) NOT NULL COMMENT '提案方主体编号',
    proposed_at            DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '提案时间',
    confirmed_provider_at  DATETIME    NULL COMMENT '提供方确认时间（逐版本）',
    confirmed_requester_at DATETIME    NULL COMMENT '需求方确认时间（逐版本）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_contract_version (contract_id, version_no)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '条款版本表（行级版本化、不可变；文本密文列 L3；锁定时固化规范化原文与哈希）';

-- 3. 签署记录表：一行 = 一方签署（每方一行不可变，uk_contract_party 封口；签署记录 L3 →
-- 签名值密文 SM4{SM2 DER 签名 Base64}；内容哈希为不可逆摘要明文）。contract_no 冗余承载
-- 读面直取（减少 join，沿存证表同款）。签署时间 = 生效判定依据（最后一签 = 合约生效时间）。
CREATE TABLE contract_signature (
    id               BIGINT      NOT NULL AUTO_INCREMENT COMMENT '技术主键',
    contract_id      BIGINT      NOT NULL COMMENT '合约 id（同库逻辑引用不建外键）',
    contract_no      VARCHAR(32) NOT NULL COMMENT '冗余编号（读面直取，减少 join）',
    party_role       VARCHAR(16) NOT NULL COMMENT 'PROVIDER/REQUESTER',
    subject_no       VARCHAR(32) NOT NULL COMMENT '签署方主体编号',
    did              VARCHAR(128) NOT NULL COMMENT '签署 DID（did:ctds:{subjectNo}.{seq}，校验归属后落库）',
    content_hash     VARCHAR(64) NOT NULL COMMENT '所签内容哈希（= 锁定版本 content_hash）',
    signature_cipher LONGBLOB    NOT NULL COMMENT 'SM2 DER 签名（Base64）的 SM4 密文——签署记录 L3 存储保密',
    signed_at        DATETIME    NOT NULL COMMENT '签署时间（最后一签 = 合约生效时间）',
    created_at       DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_contract_party (contract_id, party_role)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '签署记录表（每方一行不可变；签名值密文 L3；双签生效判定依据）';

-- 4. 存证事件表：一行 = 一次生效存证（合约哈希 + 双方签署要素；V1.0 落库留痕，链上对接归
-- 3.8.x——预留埋点口径 = 本行事件 + 合约哈希，3.8.3"合约"环节消费）。attested_at = 存证登记
-- 时间（= 双签生效时点）。
CREATE TABLE contract_attestation (
    id                   BIGINT      NOT NULL AUTO_INCREMENT COMMENT '技术主键',
    contract_id          BIGINT      NOT NULL COMMENT '合约 id（同库逻辑引用不建外键）',
    contract_no          VARCHAR(32) NOT NULL COMMENT '合约编号（冗余承载，读面直取）',
    content_hash         VARCHAR(64) NOT NULL COMMENT '合约哈希（= 锁定版本 SM3；3.8.3"合约"环节埋点消费）',
    provider_subject_no  VARCHAR(32) NOT NULL COMMENT '提供方主体编号',
    provider_did         VARCHAR(128) NOT NULL COMMENT '提供方签署 DID',
    provider_signed_at   DATETIME    NOT NULL COMMENT '提供方签署时间',
    requester_subject_no VARCHAR(32) NOT NULL COMMENT '需求方主体编号',
    requester_did        VARCHAR(128) NOT NULL COMMENT '需求方签署 DID',
    requester_signed_at  DATETIME    NOT NULL COMMENT '需求方签署时间',
    attested_at          DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '存证登记时间（= 双签生效时点）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_attestation_contract (contract_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '存证事件表（合约哈希+双方签署要素；V1.0 落库留痕，链上对接归 3.8.x）';

-- 5. 合约统一留痕表：四要素（谁/何时/哪个合约+版本/动作）+ 拒绝理由码 + from→to（版本号或
-- 状态）。全部状态转移与业务动作共用一张（动作码区分）；只插不改（无 updated_at）；
-- **不含敏感原文**——条款值级明细在加密版本行可查，本表只记版本号（行为 7 规则 5：留痕不含
-- 敏感原文，字段集锚定）。拒绝与验签异常同表承载（DENIED_ACCESS / VERIFY_SIGNATURE_FAILED），
-- reason_code = 1008 码位尾号（DB-31 口径：直接存码位尾号，不设第二套枚举）。
CREATE TABLE contract_action_log (
    id               BIGINT       NOT NULL AUTO_INCREMENT COMMENT '技术主键',
    contract_no      VARCHAR(32)  NULL COMMENT '合约编号（定位前拒绝/不存在场景为 NULL）',
    version_no       INT          NULL COMMENT '动作涉及条款版本号（有则记）',
    action           VARCHAR(32)  NOT NULL COMMENT '动作码：CREATE发起/PROPOSE提案/CONFIRM确认/TERMINATE_NEGOTIATION协商终止/SIGN签署/REFUSE_SIGN拒签终止/ATTEST存证/RELEASE_CONSENT合意解除确认/FORCE_TERMINATE强制终止/GOVERNANCE_VIEW治理查看/VERIFY_SIGNATURE_FAILED验签异常/DENIED_ACCESS越权拒绝（值域封闭 12 值）',
    actor_subject_no VARCHAR(24)  NOT NULL COMMENT '操作者主体编号（治理查看 = 实际登录主体）',
    reason_code      VARCHAR(16)  NULL COMMENT '拒绝/异常理由码尾号（如 C0012/C0013/C0018/S0002——DB-31 口径）',
    from_value       VARCHAR(64)  NULL COMMENT 'PROPOSE: 旧版本号；SIGN/TERMINATE_*: 旧状态；RELEASE_CONSENT: 状态',
    to_value         VARCHAR(128) NULL COMMENT 'PROPOSE: 新版本号；SIGN: 新状态；终止: TERMINATED；RELEASE_CONSENT: EFFECTIVE/COMPLETED；CONFIRM: 确认版本号',
    created_at       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '留痕时间',
    PRIMARY KEY (id),
    KEY idx_log_contract (contract_no, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '合约统一留痕表（只插不改；不含敏感原文；拒绝与验签异常同表）';

-- 6. 合约编号序号表：全局 1 行原子自增（不按日重置；CO+6 位）。取号 = UPDATE ... SET next_no =
-- LAST_INSERT_ID(next_no + 1) 后读 LAST_INSERT_ID()（沿模板 contract_template_no_seq / subject
-- nextDailySeq 先例；事务绑定连接保证 LAST_INSERT_ID 连接级正确）。
CREATE TABLE contract_no_seq (
    id       TINYINT NOT NULL COMMENT '固定 1 行（id=1）',
    next_no  INT     NOT NULL COMMENT '下一个全局序号（初始 1；CO 编号全局递增）',
    PRIMARY KEY (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '合约编号序号表（全局 1 行原子自增；CO+6位序号）';

INSERT INTO contract_no_seq (id, next_no) VALUES (1, 1);
