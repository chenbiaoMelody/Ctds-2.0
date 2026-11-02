# WBS-3.7.6 高保真设计：计费引擎与账单（V1.0 编码契约，2026-11-02）

> 承 `WBS-3.7.6-lofi.md` V1.0（方向定稿）；**本文 V1.0 = 编排师 2026-11-02 一次确认（Q1~Q9 全采建议 A + D1 不拆分）后编码契约即时生效**。规格派生：C-7.1-7.2 V1.0 B9 / B10 / B11 / B12 / §4-3 / §5 / §6-3。
> 勘误编号自 **E1** 起（§13）；实现与本文不一致 = 打回项。

## 1. 端点契约表（8 个，全部 `/api/v1/billing` 前缀，ADR-005 统一响应）

| # | 方法与路径 | 权限点 | 请求 | 响应 data | 错误码 | 说明 |
| --- | --- | --- | --- | --- | --- | --- |
| B-W1 | `POST /api/v1/billing/bills`（201） | `billing.governance`（仅 admin） | `{contractNo*, period?, agreedTransactionAmount?}`（period 默认当前自然月；分成档必填基数） | `BillDetail`（见 §3.4） | 1011C0010 重复出账（409）/ 1011C0013 分成缺基数（400）/ 1011C0014 合约不存在（404）/ 1011C0015 合约不满足出账条件（409）/ 1000C0001 参数非法（400） | 手动即时出账（B10-1 手动腿）；单事务落账单+明细+动作留痕；同合约同周期唯一键幂等（重复=409） |
| B-R1 | `GET /api/v1/billing/bills/mine` | `billing.read` | `contractNo?` / `period?` / `productId?` / `pageNum` / `pageSize` | `PageResult<BillSummary>`（含 `myRole`） | 1000C0001 | 需方/供方查本方账单（当前主体为 requester 或 provider），按创建时点倒序（B12-1） |
| B-R2 | `GET /api/v1/billing/bills/{billNo}` | `billing.read` | 路径参数 billNo | `BillDetail`（含明细 / 动作时间线 / 更正单号列表） | 1011C0009 账单不存在或非本方（404 同码同文）/ 1000C0001 | 本方或 admin；非本方与不存在同形防枚举（B12-3） |
| B-R3 | `GET /api/v1/billing/bills/governance` | `billing.governance` | `contractNo?` / `period?` / `productId?` / `requesterNo?` / `providerNo?` / `status?` / `billType?` / `pageNum` / `pageSize` | `PageResult<BillSummary>` | 1000C0001 | 运营全量检索（B12-2；界面归 3.7.7） |
| B-W2 | `POST /api/v1/billing/bills/{billNo}/confirm` | `billing.governance` | 无 body | `BillDetail`（状态 `CONFIRMED`） | 1011C0009（404）/ 1011C0011 已冻结（409）/ 1011C0012 状态不允许（409） | 运营确认账单（`ISSUED → CONFIRMED`）；冻结态被拒（B11-3 负向承载） |
| B-W3 | `POST /api/v1/billing/bills/{billNo}/corrections`（201） | `billing.governance` | `{amount*, reason*}`（amount ≠ 0，可负=红字冲正/正=补差） | `BillDetail`（更正单，`billType=CORRECTION`、`correctionOfBillNo`=原单） | 1011C0009（404）/ 1011C0011（冻结 409）/ 1011C0012（更正单不可再更正 409）/ 1000C0001 | 原账单金额/明细逐字不变；更正单独立编号、双向可溯（B10-3） |
| B-W4 | `POST /api/v1/billing/contracts/{contractNo}/reconciliations?period=YYYY-MM` | `billing.governance` | 路径 + query period（必填） | `ReconciliationView`（结论 PASS/DIFFERENCE + 四方数值 + 差异明细） | 1011C0009 该周期账单不存在（404）/ 1011C0014 合约不存在（404）/ 1000C0001 | 执行对账（B11）；差异 → 冻结该合约该周期全部账单；通过 → 解冻恢复原状态 |
| B-R4 | `GET /api/v1/billing/contracts/{contractNo}/reconciliations` | `billing.read` | `period?` / `pageNum` / `pageSize` | `PageResult<ReconciliationView>` | 1011C0009（非本方/不存在 404 同码同文）/ 1011C0014（合约不存在 404）/ 1000C0001 | 需方/供方读本方合约对账结论与差异明细（B12-1；admin 可读全部） |

> 非 admin 调 B-W1~B-W4：权限层 403（common-auth 既有口径）。`billNo` / `contractNo` 格式非法：1000C0001（通用参数码沿 3.7.5 同口径）。
> 自动出账无对外端点（调度内部动作）；B10-1 周期腿由门禁测试（固定 Clock 直接调用服务方法）验证，剧本 S2-11 说明步同步更新。

## 2. 迁移 V2（metering-billing，`V2__create_billing_tables.sql`，五表）

```sql
CREATE TABLE billing_bill (
    id                    BIGINT       NOT NULL AUTO_INCREMENT,
    bill_no               VARCHAR(32)  NOT NULL COMMENT '对外账单号（BL+6位，全局唯一、终身不复用）',
    bill_type             VARCHAR(16)  NOT NULL COMMENT 'NORMAL / CORRECTION',
    contract_no           VARCHAR(32)  NOT NULL COMMENT '合约编号（计费对象；快照）',
    requester_no          VARCHAR(32)  NOT NULL COMMENT '需方快照',
    provider_no           VARCHAR(32)  NOT NULL COMMENT '供方快照',
    product_id            BIGINT       NOT NULL COMMENT '产品 id 快照（读面过滤）',
    product_name          VARCHAR(128) NOT NULL COMMENT '产品名快照（读面展示）',
    period                CHAR(7)      NOT NULL COMMENT '自然月 YYYY-MM',
    pricing_model         VARCHAR(16)  NOT NULL COMMENT 'FREE/PER_CALL/MONTHLY/REVENUE_SHARE 快照（免费档不落单）',
    quantity              INT          NULL COMMENT '按次=本期事件数；包月/分成=NULL',
    unit_price            DECIMAL(12,2) NULL COMMENT '按次/包月=快照单价；分成=NULL',
    base_amount           DECIMAL(12,2) NULL COMMENT '分成=约定交易额；其余 NULL',
    share_rate            DECIMAL(12,2) NULL COMMENT '分成=费率百分比 0~100；其余 NULL',
    amount                DECIMAL(12,2) NOT NULL COMMENT '账单金额（HALF_UP 两位）',
    status                VARCHAR(16)  NOT NULL COMMENT 'ISSUED / CONFIRMED / FROZEN',
    status_before_freeze  VARCHAR(16)  NULL COMMENT '冻结前状态（解冻恢复用）',
    correction_of_bill_no VARCHAR(32)  NULL COMMENT '更正单指向原账单号；普通单 NULL',
    created_by            VARCHAR(32)  NOT NULL COMMENT '操作主体；自动出账=system',
    created_at            DATETIME     NOT NULL,
    active_normal_key     VARCHAR(80)  GENERATED ALWAYS AS
        (CASE WHEN bill_type='NORMAL' THEN CONCAT(contract_no,'#',period) ELSE NULL END) STORED,
    PRIMARY KEY (id),
    UNIQUE KEY uk_bill_no (bill_no),
    UNIQUE KEY uk_active_normal_key (active_normal_key),
    KEY idx_contract_period (contract_no, period),
    KEY idx_requester (requester_no, created_at),
    KEY idx_provider (provider_no, created_at),
    KEY idx_product (product_id, created_at),
    KEY idx_status (status, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='账单主表（金额/明细生成后不可改；状态冻结/确认留痕）';

CREATE TABLE billing_bill_detail (
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    bill_no          VARCHAR(32)  NOT NULL,
    line_no          INT          NOT NULL,
    line_type        VARCHAR(24)  NOT NULL COMMENT 'PER_CALL_EVENT / PERIOD_FEE / REVENUE_SHARE_BASE',
    description      VARCHAR(256) NOT NULL COMMENT '业务可读行说明（常量文案）',
    metering_flow_id BIGINT       NULL COMMENT '按次事件行→metering_flow.id（逐笔可溯）；其他 NULL',
    source_type      VARCHAR(24)  NULL COMMENT '按次事件行快照',
    source_no        VARCHAR(64)  NULL COMMENT '按次事件行快照',
    occurred_at      DATETIME     NULL COMMENT '按次事件时点；其他 NULL',
    quantity         INT          NOT NULL DEFAULT 1,
    unit_price       DECIMAL(12,2) NULL,
    amount           DECIMAL(12,2) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_bill_line (bill_no, line_no),
    KEY idx_bill_no (bill_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='账单明细（按次逐笔映射计量流水；包月/分成单行）';

CREATE TABLE billing_bill_action_log (
    id                  BIGINT       NOT NULL AUTO_INCREMENT,
    bill_no             VARCHAR(32)  NOT NULL,
    action              VARCHAR(24)  NOT NULL COMMENT 'CREATE / CONFIRM / FREEZE / UNFREEZE',
    from_status         VARCHAR(16)  NULL,
    to_status           VARCHAR(16)  NULL,
    reason              VARCHAR(512) NULL COMMENT '业务可读原因（冻结/更正/确认说明；无敏感原文）',
    operator_subject_no VARCHAR(32)  NOT NULL COMMENT '操作主体（自动=system）',
    created_at          DATETIME     NOT NULL,
    PRIMARY KEY (id),
    KEY idx_bill_action (bill_no, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='账单动作留痕（确认/冻结/解冻/创建；不承载金额修改）';

CREATE TABLE billing_reconciliation (
    id                        BIGINT       NOT NULL AUTO_INCREMENT,
    contract_no               VARCHAR(32)  NOT NULL,
    period                    CHAR(7)      NOT NULL,
    counter_used_count        INT          NOT NULL COMMENT '全量余额口径（无 counter 行=0）',
    usage_log_allowed_count   INT          NOT NULL COMMENT '本期 ALLOWED 流水数',
    metering_flow_count       INT          NOT NULL COMMENT '本期计量流水数',
    usage_log_allowed_all_time INT         NOT NULL COMMENT '全量 ALLOWED 流水数',
    metering_flow_all_time    INT          NOT NULL COMMENT '全量计量流水数',
    bill_quantity             INT          NULL COMMENT '按次账单数量；其他 NULL',
    bill_total_amount         DECIMAL(12,2) NOT NULL,
    recomputed_amount         DECIMAL(12,2) NOT NULL COMMENT '按账单快照独立复算金额',
    conclusion                VARCHAR(16)  NOT NULL COMMENT 'PASS / DIFFERENCE',
    details_json              TEXT         NULL COMMENT '差异明细 JSON（类型/腿/期望/实际/条目）',
    created_by                VARCHAR(32)  NOT NULL,
    created_at                DATETIME     NOT NULL,
    PRIMARY KEY (id),
    KEY idx_recon_contract_period (contract_no, period, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='对账记录（每次执行一行；不自动修数）';

CREATE TABLE billing_bill_no_seq (
    id      INT    NOT NULL,
    next_no BIGINT NOT NULL,
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='账单号序号（单行原子取号，沿 contract_no_seq 先例）';
INSERT INTO billing_bill_no_seq (id, next_no) VALUES (1, 1);
```

- **取号**：`UPDATE billing_bill_no_seq SET next_no = LAST_INSERT_ID(next_no + 1) WHERE id = 1` → `SELECT LAST_INSERT_ID()` → `BL%06d`（沿 `contract_no_seq` 单行原子取号实现；并发唯一由序号行锁 + `uk_bill_no` 双保险）。
- **同合约同周期幂等**：`active_normal_key` 仅对 `NORMAL` 生成，普通单唯一；更正单可多份（生成列 NULL 可重复，沿 3.7.3 `active_order_key` 先例）。
- **不可变**：无任何修改金额/明细字段的 SQL；仅 `status` / `status_before_freeze` 可由确认/冻结/解冻路径更新。
- **索引**：读面过滤（合约、周期、产品、双方、状态）+ 对账查询（合约 × 周期）。

## 3. 计费引擎与出账契约

### 3.1 上游快照读取（只读，单合约单查询）

`BillingContractReader`：`SELECT contract_no, requester_subject_no, provider_subject_no, product_id, product_name, pricing_model, price_amount, status, effective_at, ended_at FROM ctds_contract.contract WHERE contract_no = ?`（库名前缀配置化，沿用 `ctds.metering.source-schema.contract`；**零写零改上游**）。合约不存在 → 1011C0014。

### 3.2 三模式计算（`BillingEngine`，全部 BigDecimal）

| 档位 | 数量 | 单价/费率/基数 | 金额 | 明细 |
| --- | --- | --- | --- | --- |
| `PER_CALL` | 本期 `metering_flow` 事件数 N | `unit_price = price_amount`（元/次） | `N × unit_price`（HALF_UP 两位） | N 行事件明细（`metering_flow_id/source_type/source_no/occurred_at` 逐笔）+ 每行 `amount=unit_price` |
| `MONTHLY` | NULL | `unit_price = price_amount`（元/月） | `unit_price`（整月，不折算） | 1 行 `PERIOD_FEE`（"包月周期费"）；**金额与调用次数无关** |
| `REVENUE_SHARE` | NULL | `share_rate = price_amount`（百分比 0~100）；`base_amount = 出账参数` | `base_amount × share_rate / 100`（HALF_UP 两位） | 1 行 `REVENUE_SHARE_BASE`（本金 × 费率说明常量化，数值在账单头快照） |
| `FREE` | — | — | — | **不出账单**（手动拒绝 1011C0015；自动跳过——B8-2） |

- **金额校验**：`price_amount` 缺失（付费档）→ 1011C0015（合约不满足出账条件，数据异常 fail-closed）；`base_amount` 缺失/≤0/小数位>2 → 1011C0013（分成档）；`amount` 超出 DECIMAL(12,2) → 1000C0001（参数/数据异常，服务端日志明细）。
- **按次无事件**：本期 N=0 → 1011C0015（该周期无计量，不出空账单）；包月不依赖事件；分成不依赖事件。
- **合约可出账条件**：`effective_at <= 周期末` 且（`ended_at IS NULL` 或 `ended_at > 周期初`）；否则 1011C0015。
- **周期**：`CHAR(7) 'YYYY-MM'` 自然月（与计量 R-M2 `DATE_FORMAT(occurred_at,'%Y-%m')` 同口径）；事件归属按 `occurred_at`（事件时点，非采集时点）；时区沿库/服务默认（与 3.7.5 演示环境同 server 口径）。
- **复算**（B9-4）：任意第三方按账单明细 + 账单头快照字段独立计算，结果必须 = `amount`（同输入同输出；测试以独立实现复算断言）。

### 3.3 出账事务与幂等（`BillingOutbillService`，手动/自动共用）

1. 校验 period 格式与不晚于当前月（Clock）；读合约快照并校验可出账条件与档位；
2. 计算金额与明细（上述引擎）；
3. 取账单号 → 插入 `billing_bill` + `billing_bill_detail` + `billing_bill_action_log(CREATE)`（单事务）；
4. 捕获 `DuplicateKeyException` 于 `uk_active_normal_key` → 409 1011C0010（整个事务回滚，不残留半单）；
5. 手动出账 `created_by = admin 主体`；自动出账 `created_by = 'system'`；更正单创建 `created_by = admin 主体`（见 §6）。

### 3.4 响应字段（records，金额一律 `BigDecimal.toPlainString()` 出站）

`BillSummary`：`billNo / billType / contractNo / requesterNo / providerNo / productId / productName / period / pricingModel / amount / status / correctionOfBillNo / createdAt / myRole?`（`myRole` 仅 mine 面，`REQUESTER|PROVIDER`）。
`BillDetail` = Summary + `quantity / unitPrice / baseAmount / shareRate / details[] / actions[] / correctionBillNos[]`。
`BillDetailLine`：`lineNo / lineType / description / meteringFlowId / sourceType / sourceNo / occurredAt / quantity / unitPrice / amount`。
`BillActionView`：`action / fromStatus / toStatus / reason / operatorSubjectNo / createdAt`。
`ReconciliationView`：`id / contractNo / period / counterUsedCount / usageLogAllowedCount / meteringFlowCount / usageLogAllowedAllTime / meteringFlowAllTime / billQuantity / billTotalAmount / recomputedAmount / conclusion / differences[] / createdBy / createdAt`。
`ReconciliationDifference`：`type / leg / expected / actual / detail`。

## 4. 自动出账契约（`BillingAutoService`，B10-1 周期腿）

- **调度**：`@EnableScheduling`（主类）+ `@Scheduled(cron = "${ctds.billing.auto.cron:0 10 0 1 * ?}")` → `runForPeriod(YearMonth.now(clock).minusMonths(1))`；`ctds.billing.auto.enabled` 默认 `true`（测试 yml 置 `false`，测试直接调用服务方法；不等待真实时钟）。
- **扫描范围**：`ctds_contract.contract` 中 `pricing_model <> 'FREE'` 且 `effective_at <= 周期末` 且（`ended_at IS NULL` 或 `ended_at > 周期初`）的合约；逐合约独立事务（单合约失败不整体回滚，记 ERROR 日志继续）。
- **逐档规则**：`PER_CALL` 本期有事件才出账；`MONTHLY` 直接出整月费；`REVENUE_SHARE` **跳过并 WARN**（无基数登记，需人工补出账——Q3-A 诚实边界）；重复单捕获唯一键跳过（幂等，不报错）。
- **单实例语义**：k8s 单副本；多实例部署时唯一键保证不重复计费（调度重复执行安全），调度协调演进登记 3.7.8/部署期。
- **返回/留痕**：批量结果仅日志（scanned / billed / skippedDuplicate / skippedFree / skippedNoMetering / skippedNoBase / failed）；无对外端点。

## 5. 对账算法与差异处置（`BillingReconcileService`，B11）

1. **前置**：合约存在（1011C0014）；该合约该周期 `NORMAL` 账单存在（1011C0009）；取账单头快照。
2. **取值**（全部只读）：
   - ① `counter_used_count` = `ctds_contract.contract_usage_counter.used_count`（无行=0，该腿跳过比对）；
   - ② `usage_log_allowed_count`（本期）/ `usage_log_allowed_all_time` = `contract_usage_log` `outcome='ALLOWED'` 计数；
   - ③ `metering_flow_count`（本期）/ `metering_flow_all_time` = `metering_flow` 计数；
   - ④ `bill_total_amount` / `bill_quantity` / `recomputed_amount`（按账单快照复算）。
3. **判定差异**（任一命中即 `DIFFERENCE`，全部写入 `details_json`）：
   - `COUNTER_VS_USAGE_LOG_ALL`：counter ≠ 全量 ALLOWED（仅当 counter 行存在）；
   - `USAGE_LOG_VS_METERING_ALL`：全量 ALLOWED ≠ 全量计量；
   - `USAGE_LOG_VS_METERING_PERIOD`：本期 ALLOWED ≠ 本期计量；
   - `BILL_QUANTITY_VS_METERING`：按次账单数量 ≠ 本期计量数；
   - `AMOUNT_RECOMPUTE`：账单金额 ≠ 快照复算金额；
   - `MISSING_IN_BILL` / `EXTRA_IN_BILL`：按次账单明细的 `metering_flow_id` 集合 vs 本期计量 id 集合（缺失/多出条目）。
4. **处置**：`PASS` → 该合约该周期全部 `FROZEN` 账单恢复 `status_before_freeze`（默认 `ISSUED`）并记 `UNFREEZE`；`DIFFERENCE` → 全部非冻结账单置 `FROZEN`（保存原状态）并记 `FREEZE`；对账记录一行（结论/数值/明细/执行人/时点）。**除状态流转与留痕外零数据修改**（不自动修数）。
5. **ADR-022 行 100 观察项**：交付域"发起人 ≠ 需求方"拒绝发生在计数后（usage_log 有 ALLOWED、网关计量事件不产生）将使 ② 与 ③ 差 1 → **fail-closed 报差异并冻结**（本设计按现状口径正确暴露，不绕过；演示不构造该场景；如需改判定次序走变更流程）。
6. **DB-51 口径**：计数权威引用钉死 ADR-020 **§2.4 / §2.8**（禁止误引 §2.7）；随本卡收口将 DB-51 状态更新为关闭（文档勘正留痕）。

## 6. 账单状态机、确认与更正

```
ISSUED --confirm--> CONFIRMED
ISSUED/CONFIRMED --reconcile DIFFERENCE--> FROZEN（status_before_freeze = 原状态）
FROZEN --reconcile PASS--> 原状态（ISSUED/CONFIRMED，清空 status_before_freeze）
NORMAL(任意非 FROZEN) --correction--> 新 CORRECTION 单（ISSUED），原单零改动
```
- 确认：仅 `ISSUED`；`FROZEN` → 1011C0011；`CONFIRMED` → 1011C0012。
- 更正：仅 `NORMAL`；`FROZEN` → 1011C0011；`CORRECTION` → 1011C0012；`amount ≠ 0`、`reason` 非空 ≤512；更正单继承原单合约/双方/产品/周期/定价档快照，`correction_of_bill_no` 指向原单；原单动作时间线不新增修改行（原单金额/明细逐字不变；更正关系由更正单 + 原单 `correctionBillNos[]` 读面双向可溯）。
- 冻结/解冻动作均记 `billing_bill_action_log`（operator = 对账执行人；自动出账单的 operator 可为 system 的确认由 admin 执行）。

## 7. 错误码（1011 段续用；C0001~C0008 已由订单/评价/计量占用，**C0009 起空闲实测**）

| 码 | HTTP | 业务语义 | 文案（业务可读，常量） |
| --- | --- | --- | --- |
| 1011C0009 | 404 | 账单或对账数据不存在或非本方（同码同文防枚举） | 账单或对账数据不存在或不可读 |
| 1011C0010 | 409 | 同合约同周期账单已存在（幂等负向） | 该合约该周期账单已存在，请勿重复出账 |
| 1011C0011 | 409 | 账单已冻结，操作被拒绝（fail-closed） | 账单已冻结，当前操作不可执行 |
| 1011C0012 | 409 | 账单状态不允许该操作 | 账单当前状态不允许该操作 |
| 1011C0013 | 400 | 交易额分成出账缺少/非法约定交易额 | 交易额分成出账须提供有效的约定交易额 |
| 1011C0014 | 404 | 合约不存在或不可读 | 合约不存在或不可读 |
| 1011C0015 | 409 | 合约不满足出账条件（免费档/未生效/周期无计量/数据异常） | 合约不满足出账条件，无法出账 |

- 登记位置：`BillingErrorCodes`（domain/billing）+ `BillingExceptionHandler` 映射（沿 metering 既有处理器模式）；1000C0001 参数通用沿 common；权限 403/401 沿 common-auth 既有。
- 所有对外文案不含内部实现细节；详细原因入服务端日志与动作留痕（无敏感原文）。

## 8. 权限矩阵（服务级 yml 自治；新增 2 点，50→52）

| 动作 | requester | provider | admin | 服务端把关 |
| --- | --- | --- | --- | --- |
| 查本方账单/对账结论 B-R1/B-R2/B-R4 | ✓（归属硬校验） | ✓（同） | ✓ | `billing.read` + 双方归属/合约参与方硬校验 |
| 全量账单 B-R3 | ✗ | ✗ | ✓ | `billing.governance` |
| 手动出账 B-W1 / 对账 B-W4 / 确认 B-W2 / 更正 B-W3 | ✗ | ✗ | ✓ | `billing.governance` |

- `application.yml` 权限映射扩展：`requester: metering.read,billing.read` / `provider: metering.read,billing.read` / `admin: metering.read,metering.governance,billing.read,billing.governance`。

## 9. 网关与部署改动清单

| 件 | 改动 |
| --- | --- |
| gateway `application.yml` | `ctds.gateway.routes` 增 `connector-billing`：`/api/v1/billing/**` → `http://127.0.0.1:8089`（**不进交付面白名单**；调用点/过滤器/错误码零改动） |
| gateway 测试 | 路由契约测试扩 1 例（沿 3.7.4/3.7.5 模式；45→46，零回归） |
| metering-billing pom | + `common-pagination`（内部模块，分页）；**零新外部依赖**（`@Scheduled` 为 Spring Boot 内置） |
| metering-billing 主类/配置 | `@EnableScheduling` + `BillingProperties`（auto.enabled/cron）+ `Clock` bean（`systemDefaultZone`，测试固定时钟） |
| deploy/k8s | **零改动**（同一 deployment/service；自动出账默认值在 yml，无需新 env） |
| 前端 | **零改动 / 零 npm**（界面归 3.7.7） |

## 10. 实施前置检查（编码段第一动作，逐项实测留痕）

1. 分支 `feat/WBS-3.7.6-计费引擎与账单` 自本地 main `22078d8` 创建、工作树 clean（ahead 2 随本卡 PR 入库）；
2. metering-billing 迁移仅 V1、下一号 V2、`out-of-order:false` 复核；`metering_flow` 列面亲读（无金额/周期列）；
3. 1011C0009 起空闲复核（Java + docs 双查；C0001~C0008 占用清单）；
4. 权限点全景 50 个复核（零 `billing.*`）；服务级 yml 权限映射现状；
5. 合约表列亲读（`pricing_model/price_amount/effective_at/ended_at/product_id/product_name/`双方）；`product_order` 快照列亲读；`usage_log/counter` 列与计数语义；
6. 跨 schema 只读授权复核（metering 采集账号已有 contract/usage 表 SELECT——3.7.5 授权面，无新增授权义务）；
7. `gates-config.json` 哈希基线复核（`955bdbba…2ee2` 零改动）；coreModules 含 metering-billing、mutation 仅 did；
8. gateway 路由现状 10 条复核（无 billing，防撞名）；路由测试模式亲读；
9. metering 测试基线复跑 20 例；gateway 45 例；contract 261 例；delivery 227 例；前端 58 文件 749 例四步（零改动兜底）；
10. `Clock` bean 现状复核（metering 尚无；合约域先例签名）；delivery `@EnableScheduling`/`@Scheduled` 先例亲读；测试 yml 关闭 auto；
11. `common-pagination` 坐标与 `PageQuery/PageResult` 用法亲读（contract `OrderQueryController` 先例）；
12. MySQL 可达 + `ctds_metering` 库与 V1 已应用；`ctds_contract` 三表可 SELECT（演示库）；
13. 剧本版本现状（C-7.2 V1.1 / 账单核对 V1.0）与 S2、对账 S1/S2 操作路径核对；
14. 时区口径（库/服务默认，自然月按 occurred_at）与 3.7.5 一致复核。

## 11. 测试锚点规划（测试先行：红相先行，编码段回填实测类名与例数）

| # | 锚 | 用例要点（Given/When/Then 映射） | 落点（规划） |
| --- | --- | --- | --- |
| TB1 | B9-1 按次 | 按次合约单价 3.00 + 本期 3 条计量 → 手动出账：amount=9.00、quantity=3、明细 3 行逐笔可溯至 metering_flow；再按账单快照独立复算 = 9.00 | 出账集成（SharedMySqlContainer + V2） |
| TB2 | B9-2 包月 | 包月合约两期：一期 1 次调用、二期 5 次调用 → 两期 amount 均 = 周期费（与次数无关） | 同上 |
| TB3 | B9-3 分成 | 基数 10000 × 费率 5% → amount=500.00；基数/费率在账单快照可读；缺基数 → 1011C0013 | 同上 |
| TB4 | B9-4 可复算/不追溯 | 出账后变更合约/目录价格（测试库直改）→ 账单 amount 与复算结果逐字不变；同输入同输出 | 同上 |
| TB5 | B10-1 手动/幂等 | 同合约同周期手动二次出账 → 409 1011C0010；并发两请求恰一成功一 409；账单仅一行 | 同上 |
| TB6 | B10-1 自动腿 | 固定 Clock：上月合约有按次事件 → `runForPeriod` 出账；重复运行零新增；包月出整月费；免费/无计量/分成无基数跳过；auto.enabled=false 不调度 | 同上（直接调用服务方法） |
| TB7 | B10-2/3 编号与更正 | 账单号唯一且递增不复用；更正单生成后原单金额/明细逐字不变、双向可溯；更正单不可再更正；冻结单不可更正；更正金额 0/原因空 → 1000C0001 | 同上 |
| TB8 | B11-1 对账正向 | 正常出账后对账：四方数值一致、结论 PASS、记录（时点/执行人/数值）落库 | 同上 |
| TB9 | B11-2/3 差异与冻结 | 测试库直改一条计量流水 `occurred_at` 移出本期 → 对账 DIFFERENCE（明细可定位）；账单 FROZEN；confirm → 1011C0011；还原后对账 PASS → 解冻；confirm 成功 | 同上 |
| TB10 | B11-4 不自动修数 | 差异前后计量/账单金额明细零修改（仅状态与留痕新增）；对账记录与动作留痕完整 | 同上 |
| TB11 | B12 读面/归属 | 需方/供方仅见本方（按合约/周期/产品过滤）；非本方与不存在同 404 1011C0009；admin 治理面全量；对账结论参与方可读 | 同上 |
| TB12 | B8-2 免费档 | FREE 合约手动出账 → 1011C0015；账单零新增；自动出账跳过；计量流水不受影响 | 同上 |
| TB13 | 结构锚 | V2 五表列/唯一键/生成列/索引；账单金额明细不可变（无更新 SQL）；错误码常量映射 | 迁移结构测试（沿 3.7.5 T9 同型） |
| TB14 | 确认与动作留痕 | ISSUED→CONFIRMED 成功；CONFIRMED 再确认 → 1011C0012；动作留痕 CREATE/CONFIRM/FREEZE/UNFREEZE 齐全 | 出账集成 |
| TG1 | 网关路由 | connector-billing 路由契约 + 既有 45 例零回归（46 例） | gateway-service |

- 红相归档 `build-output/w376-red-*.log`；既有 metering 20 例零回归；metering-billing 核心行覆盖 ≥80%（coreModules 自动生效）。
- **对账断言双向必有**（AGENTS §4）：TB8 正向 + TB9 负向（差异必报、冻结 fail-closed、不静默通过）。

## 12. 剧本映射（C-7.2 V1.1 → V1.2；账单核对 V1.0 → V1.1）

| 剧本步骤 | 本卡承载 | 修订内容（业务判定一字不改） |
| --- | --- | --- |
| C-7.2 S2-1 | B-W1 手动出账（按次）；账单字段最小集齐备 | 操作路径接口化（curl 示例）；预期结果不动 |
| S2-2 | 重复出账 409（1011C0010） | 同上 |
| S2-3 | 包月整月费（与次数无关） | 同上 |
| S2-4 | 分成 = 约定交易额 × 费率（出账参数携带基数；基数/费率快照可溯） | 操作路径增加基数参数说明；预期结果不动（Q3-A 留痕） |
| S2-5 | 按明细 + 快照独立复算 = 账单金额 | 操作路径接口化 |
| S2-6 | 定价变更后已出账单逐字不变 | 同上 |
| S2-7 | 无修改端点（PUT/PATCH → 404/405）；更正单路径 | 操作路径接口化 + 更正 curl |
| S2-8 | 账单号唯一不复用（治理列表抽查） | 同上 |
| S2-9 | 本方按合约/周期/产品过滤查询 | 同上 |
| S2-10 | 非本方 404 + 1011C0009 业务可读 | 同上 |
| S2-11 | 自动出账已实现（TB6 门禁验证）；剧本仍手动腿演示 | 注记更新（诚实边界：自动腿由测试承载） |
| S1-5 | 免费档无账单（计费腿补验） | 操作路径注记"免费档无账单"；业务判定不动 |
| 对账 S1-1/2 | B-W4 对账 PASS + 四方数值/结论/留痕 | 操作路径接口化 |
| 对账 S1-3 | B-R4 参与方读本方对账结论 | 同上 |
| 对账 S2-1 | 附录 A 差异构造适配：改一条计量流水 `occurred_at` 移出本期（改值不删行），记录原值/新值 | 构造方式适配实际列面（metering_flow 无数量/金额列）；业务判定不动 |
| 对账 S2-2 | 对账 DIFFERENCE + 差异明细可定位 | 操作路径接口化 |
| 对账 S2-3 | 冻结态 confirm 被拒（1011C0011） | 同上 |
| 对账 S2-4 | 复核 + 还原 + 留痕；系统不自动修数 | 同上 |
| 对账 S2-5 | 还原后对账 PASS → 解冻 | 同上 |

- 两剧本版本更新并提示 **PO 批准**（留痕 = 任务卡验收段 + 剧本版本行/维护说明）；业务判定与预期结果零改动。

## 13. 边界、复用声明与勘误预留

- **复用声明**：common-pagination `PageQuery/PageResult`（分页）；common-errorcode `ApiResult/ErrorCode`；common-auth `@RequirePermission` + 服务级角色映射；ADR-009 Flyway 迁移；ADR-016 §2.7 回环绑定；ADR-007 唯一键幂等语义（本卡"重复被拒"不用 `@Idempotent` 结果复用）；delivery-service `@EnableScheduling + @Scheduled` 先例；3.7.3 `active_order_key` 生成列唯一键与 `*_action_log` / `*_no_seq` 模式；3.7.5 跨 schema 只读 reader、`SharedMySqlContainer` 测试基建、网关路由测试模式；k8s 零改动。**零新外部 Maven 依赖**（+common-pagination 为平台内部模块）；`@Scheduled` 为 Spring Boot 内置。
- **规格外实现声明**：应为空（全部条目可回溯至 B9~B12 与 Q1~Q9+D1 确认口径）；Q3-A 的"出账参数登记约定交易额"作为 B9-3 的落地解释需编排师/PO 批准留痕。
- **勘误预留**：E1 起编号；立卡阶段自勘一处——hifi 草案曾拟"账单数量腿"直接对 counter 周期值，因 counter 无周期列已更正为"全量余额腿 + 本期流水腿"（Q6-A）。
- **E2（编码段，2026-11-02）**：§7 "登记位置 = BillingErrorCodes + BillingExceptionHandler" 的实现形态改为**复用服务级既有 `MeteringExceptionHandler` 统一映射**（同一服务内计量 / 计费两域共用一份 `@RestControllerAdvice`；若各建一份同 `@Order` 的 advice，Spring 解析顺序不确定，会导致既有 1011C0008 → 404 语义漂移）。该处理器在 §7 七码基础上扩展 1011 段精确 HTTP 语义（404 / 409），并补 `HttpMessageNotReadableException` / `MissingServletRequestParameterException` → 1000C0001；**错误码值、HTTP 语义与文案与 §7 逐字一致**，仅承载类名不同。
- **E3（评审修复批，2026-11-02；4 视角评审循环 1 的处置与登记）**：
  - **E3-1 对账并发与冻结原状态（评审 P2-1 / P2-2，已修）**：`markFrozen` 改为单条 `UPDATE ... SET status_before_freeze = status, status = 'FROZEN'`（原状态取行内真值，不传应用侧旧读值；顺序不可颠倒）；对账冻结/解冻对象的读取加 `FOR UPDATE`（同合约同周期在事务内串行化），闭合"confirm 与 reconcile 交错导致确认证据被回退"与"最新对账 DIFFERENCE 但账单未冻结"两个窗口。残余：多实例部署下由唯一键 + 行锁保证不重复计费；调度协调已在 §4 登记，压测复核归 3.7.8。
  - **E3-2 参数形态与长度口径（评审 P2-3 / P2-5，已修）**：`billNo` 形态 `^BL\d{6}$`、`contractNo` 形态 `^[A-Z]{2}\d{6}$` 在 B-R1/B-R2/B-R3 统一校验（非法 → 1000C0001），与计量读面同口径；更正单留痕 `reason` 按**拼装后总长**校验列宽（前缀"更正单（原单 {billNo}）："= 17 + 账单号 8 + 用户输入 ≤ 512），超限 → 1000C0001（不再落库溢出 500）。
  - **E3-3 分成基数上限（评审 P2-4，已修）**：约定交易额须 ≤ 9999999999.99（DECIMAL(12,2) 可表示上界），超限 → 1000C0001（与 §3.2 金额溢出同口径）。
  - **E3-4 `next_no` 语义（新发现，登记）**：`billing_bill_no_seq.next_no` = **已发最大序号**（取号 SQL 为 `next_no + 1` 后读 `LAST_INSERT_ID`）；§2 的种子 `(1,1)` 推论首个账单号 = `BL000002`。不影响唯一性与不复用（B10-2），但若演示/验收需从 `BL000001` 起，须改种子为 0——属迁移口径变更，本卡不改（走变更流程）。
  - **E3-5 自动出账 `skippedFree` 不实现（评审 P3-1，登记）**：§4 字段清单中的 `skippedFree` 不对外报告——FREE 档由 `findBillable` 扫描 SQL 前置排除（恒 0，无观测价值）；服务内 `FREE` 分支保留为**防御分支**并注明明细，扫描口径若变更才生效。
  - **E3-6 `reason` 渲染转义义务（评审 P3-12，转 3.7.7）**：`reason` 为运营自由文本且对合约双方可见（B-R2）；本卡只存储不渲染，**3.7.7 账单界面必须转义输出**（存储型 XSS 面）。
  - **E3-7 收敛建议（评审 P3-8，登记不入本卡）**：`CONTRACT_NO` 正则 4 份、`BizException(code, 常量文案)` 11 处、`toLocalDateTime` 7 种写法、`SqlArgs` 逐字复制 2 份、金额常量（SCALE 2 / MAX_AMOUNT / HUNDRED）分散——建议后续横切收敛进 `common`（先查后提沉淀建议，不在本卡私自扩面）。
  - **E3-8 平台与跨卡项（登记，不在本卡）**：① 服务信任网关注入的身份头（依赖仅回环绑定 ADR-016 §2.7；k8s 无 NetworkPolicy，3.9.x 平台演进）；② `ClaimValidator` issuer 为空不校验（网关 yml issuer 默认空，3.9.1 令牌签发前提）；③ 网关路由清单测试命名/注释与 10 条不符且缺全量计数断言（3.7.8）；④ `BillingProperties.Auto.cron` 与 `@Scheduled` 占位符/yml 三处默认值双源（`getCron()` 零调用方）。
  - **E3-9 时区口径（评审 P3-14，登记）**：`BillingPeriods` 按 JVM 时区构周期区间，计量 R-M2 按库时区 `DATE_FORMAT`；跨月边界若两者不一致会产生"假差异"（3.7.5 同型）。演示与测试环境为同一 server 口径，压测/多实例部署前需统一（归 3.7.8）。
  - **E3-10 远期与压测项（独立复审补充，登记）**：① 账单号为 `BL` + 6 位（`^BL\d{6}$` 校验 / `BL%06d` 取号），序号超过 999999 后生成的 7 位号将无法被 detail/confirm/correction 受理——与 §1 契约一致，属远期容量风险（演示量级无影响，扩容随 §2 取号格式变更流程处理）；② 对账冻结/解冻的 `FOR UPDATE` 在 RR 隔离下对 `idx_contract_period` 取间隙锁，与同合约同周期并发 `INSERT` 理论上存在 1213 死锁可能（现并发用例覆盖"出账 vs 出账"），压测复核归 3.7.8；③ 约定交易额"缺失/≤0/小数位>2"→1011C0013 与"超上限"→1000C0001 为双码，后续客户端体验可在 3.7.7 收敛；④ 不可变源码扫描锚只覆盖 `src/main/java`（不含 resources SQL）且大小写敏感，已足以让"新增金额 UPDATE"变红，若后续引入 SQL 外置需扩展扫描面。
- **ADR-022 行 100 观察项**：对账会把"计数已发生但网关计量事件未产生"的边缘暴露为差异并冻结（fail-closed 正确行为）；演示不构造；如需改判定次序走变更流程（关联 3.7.5 计量口径复核）。
- **DB-51**：B11 对账口径引用钉死 ADR-020 §2.4/§2.8；随本卡收口关闭 DB-51（文档勘正留痕）。
- **演示环境义务（交付说明承载）**：`ctds_metering` 已有 V1；V2 由 Flyway 自动应用；采集账号已有本库全权 + contract/usage 表 SELECT（3.7.5 授权面），**无新增建库/授权义务**；Time zone 与 3.7.5 同 server 口径。
- **数据分类**：账单/对账 = 交易元数据（CAT-04 L1，沿合约 L1 口径）；零请求体/响应体/条款原文/令牌/密钥。
