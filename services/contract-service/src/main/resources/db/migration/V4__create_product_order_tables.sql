-- 订购与订单流程（WBS-3.7.3，hifi §2 定稿；规格 docs/specs/C-7.1-7.2-门户与计费.md V1.0
-- 行为 B3（订购发起与订单登记）/ B4（订单查询与留痕）/ B6（状态感知 = 留痕可查）+ B12-1 订单读面）。
-- 三表承载：product_order（订单主表：一行 = 一笔订购；订单 = 合约的业务外壳——订单登记与合约
-- 草案同事务落库，产品/定价/模板版本快照与合约同源；状态三态只映射规格 B3-4 两条联动：合约生效
-- →已完成、生效前终止→已关闭，合约生效后的完结/强制终止不改订单状态——Q7-A）、
-- product_order_action_log（订单统一留痕表：创建/完成/关闭/拒绝访问四类动作，只插不改，
-- reason 仅业务常量文案零条款值/合同原文——B4-3 全生命周期留痕）、product_order_no_seq
-- （订单编号序号表：全局 1 行原子取号，OD+6 位不按日重置，沿 contract_no_seq 先例）。
-- 数据分级落级：product_order / product_order_no_seq = L1（运营状态元数据与序号；产品名/定价
-- 随目录公开口径，沿 V2 contract 表复核结论；不含条款值/合同正文）；product_order_action_log =
-- CAT-06 L3（安全审计留痕，零敏感原文——条款值在合约加密版本行，本表只记状态号与业务文案）。
-- 跨表引用一律同库逻辑引用不建外键（contract_no → contract.contract_no、product_id →
-- ctds_catalog.data_product.id、subject_no → ctds_subject.subject 均逻辑引用，沿 V2 口径）。
-- 进行中订单唯一性（B3-2：同需方同产品仅一份进行中订单）由生成列 active_order_key 唯一索引
-- 承载：仅 status='IN_PROGRESS' 行生成键 `requester#productId`，终态行键为 NULL——MySQL 唯一
-- 索引允许多 NULL，终态订单不占键位（关闭后同产品可重购——Q6-A）。

-- 1. 订单主表：一行 = 一笔订购（订单登记与合约草案同事务落库——B3-3）。
CREATE TABLE product_order (
    id                      BIGINT        NOT NULL AUTO_INCREMENT COMMENT '技术主键（对外 REST 逻辑键 = order_no）',
    order_no                VARCHAR(32)   NOT NULL COMMENT '订单编号 OD+6位全局序号（序号表原子取号；不可变）',
    product_id              BIGINT        NOT NULL COMMENT '订购对象产品 id（catalog data_product.id，逻辑引用不建外键）',
    product_name            VARCHAR(128)  NOT NULL COMMENT '产品名快照（订购时，与合约快照同源——B3-3）',
    provider_subject_no     VARCHAR(32)   NOT NULL COMMENT '提供方主体编号快照（= 订购时产品属主）',
    requester_subject_no    VARCHAR(32)   NOT NULL COMMENT '需求方主体编号（= 发起人，B3-1 四要素）',
    pricing_model           VARCHAR(16)   NOT NULL COMMENT '定价档快照（FREE/PER_CALL/MONTHLY/REVENUE_SHARE，与合约快照同源）',
    price_amount            DECIMAL(12,2) NULL COMMENT '定价数值快照（免费/未登记恒 NULL，与合约快照同源）',
    template_no             VARCHAR(32)   NOT NULL COMMENT '模板编号快照（合约发起锁定版本）',
    template_version_no     INT           NOT NULL COMMENT '模板版本快照（引用式锁定，沿 contract 表口径）',
    contract_no             VARCHAR(32)   NOT NULL COMMENT '关联合约编号（contract.contract_no 逻辑引用；一笔订单对应一份合约草案）',
    status                  VARCHAR(16)   NOT NULL COMMENT '订单状态三态：IN_PROGRESS 进行中 / COMPLETED 已完成 / CLOSED 已关闭',
    active_order_key        VARCHAR(80)   GENERATED ALWAYS AS (CASE WHEN status = 'IN_PROGRESS' THEN CONCAT(requester_subject_no, '#', product_id) ELSE NULL END) STORED COMMENT '进行中订单唯一键（B3-2：仅进行中行生成 需方#产品 键；终态行 NULL 不占键位）',
    closed_reason           VARCHAR(512)  NULL COMMENT '关闭原因业务文案（协商终止/拒签终止；零终止类型码与理由原文）',
    closed_at               DATETIME      NULL COMMENT '关闭时点',
    created_by              VARCHAR(24)   NOT NULL COMMENT '创建操作者（= requester_subject_no）',
    created_at              DATETIME      NOT NULL COMMENT '创建时点（B3-1 四要素时点）',
    updated_at              DATETIME      NOT NULL COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_order_no (order_no),
    UNIQUE KEY uk_contract_no (contract_no),
    UNIQUE KEY uk_active_order_key (active_order_key),
    KEY idx_requester (requester_subject_no, created_at),
    KEY idx_provider (provider_subject_no, created_at),
    KEY idx_product (product_id, created_at),
    KEY idx_status (status, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '产品订单主表（订单=合约业务外壳；状态三态随合约联动）';

-- 2. 订单留痕表：创建/完成/关闭/拒绝访问四类动作，只插不改（B4-3；B4-4 拒绝留痕）。
CREATE TABLE product_order_action_log (
    id                      BIGINT        NOT NULL AUTO_INCREMENT COMMENT '技术主键',
    order_no                VARCHAR(32)   NOT NULL COMMENT '订单编号（product_order.order_no 逻辑引用）',
    action                  VARCHAR(24)   NOT NULL COMMENT '动作：CREATE 创建 / COMPLETE 合约生效完成 / CLOSE 合约终止关闭 / DENIED_ACCESS 非本方访问拒绝',
    from_status             VARCHAR(16)   NULL COMMENT '动作前状态（CREATE 为 NULL）',
    to_status               VARCHAR(16)   NULL COMMENT '动作后状态（DENIED_ACCESS 为 NULL）',
    reason                  VARCHAR(512)  NULL COMMENT '业务文案（关闭原因/拒绝说明；仅常量文案零敏感原文）',
    operator_subject_no     VARCHAR(32)   NOT NULL COMMENT '操作者主体编号（DENIED_ACCESS = 越权访问者）',
    created_at              DATETIME      NOT NULL COMMENT '留痕时点',
    PRIMARY KEY (id),
    KEY idx_order_no_created (order_no, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '产品订单留痕表（全生命周期；零敏感原文）';

-- 3. 订单编号序号表：全局 1 行原子自增（沿 contract_no_seq 先例；OD+6位）。
CREATE TABLE product_order_no_seq (
    id       TINYINT NOT NULL COMMENT '固定 1 行（id=1）',
    next_no  INT     NOT NULL COMMENT '下一个全局序号（初始 1；OD 编号全局递增）',
    PRIMARY KEY (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '订单编号序号表（全局 1 行原子自增；OD+6位序号）';

INSERT INTO product_order_no_seq (id, next_no) VALUES (1, 1);
