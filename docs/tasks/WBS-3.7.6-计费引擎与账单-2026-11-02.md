# WBS-3.7.6 任务卡：计费引擎与账单（2026-11-02）

| 项 | 内容 |
| --- | --- |
| 来源 | 台账 / 日志续点「下一包立卡候选 = WBS 3.7 域下一包」（`docs/logs/Ctds-项目开发日志-26-11-02-0003.md` §下一步）；编排师 2026-11-02 会话裁定「**立卡 WBS-3.7.6**」+「停止演示链」+「本地 main ahead 2 随下一卡 PR 入库」→ 按 WBS 行序下一包 = **WBS-3.7.6 计费引擎与账单** → 按流程推进 **WBS-3.7.6 立卡段**；冷启动承接 3.7.5 全流程闭环（PR #25 MERGED `9495b1f`；收口批 `4d709bf` + 回填批 `22078d8` 本地 main ahead 2，随本卡 PR 入库——沿链式先例）。 |
| 级别与性质 | L1-3 平台功能**实施包 + 非界面类**（WBS 行 318，预算 **1 会话**；章程 2.6.3：非界面类两级设计 = **业务可读行为清单 + 接口契约表 + 边界值与异常行为**，lofi + hifi 同批 **V0.9** 提交、一次确认转 V1.0；**免 HTML 原型**——计费管理界面归 3.7.7，剧本 C-7.2 演示前提⑤既有裁决）。**扩展既有服务**：`services/metering-billing`（8089 / `ctds_metering` 库，迁移 **V2** 五表）——计费引擎三模式 + 出账双轨（手动 + 自然月自动）+ 账单不可变与更正 + 对账 fail-closed + 计费读面；**零前端改动 / 零新 npm / 零 MQ / 零新外部 Maven 依赖**；新增内部依赖仅 `common-pagination`；调度 = Spring 内置 `@Scheduled`（沿 delivery-service 先例，非新框架）。 |
| 需求锚点核对 | WBS `docs/C-TDS项目总体实施计划与WBS.md` **行 318**（`计费引擎与账单 \| 按次/包月/交易额计费、账单生成（含对账断言） \| 3.7.5 \| 1`——依赖 3.7.5 ✅ 已合并闭环；预算 1 会话）；规格 `docs/specs/C-7.1-7.2-门户与计费.md` **V1.0**：**行为 B9 计费引擎三模式**（4 规则：计费依据 = 已生效合约 + 订购定价四档快照 / 按次 = 事件数 × 次单价、包月 = 订单周期费、交易额分成 = 约定交易额 × 分成费率 / 计费可复算同输入同输出 / 资金语义红线无支付退款结算 + 费率模式快照变更留痕）+ **行为 B10 账单生成**（4 规则：账单 = 合约 × 出账周期聚合 / **出账双轨 = 自然月自动 + 运营手动即时**、同合约同周期幂等 / 字段最小集 / **生成后不可篡改，更正 = 更正单/红字冲正留痕**）+ **行为 B11 对账（硬约束）**（4 规则：四方交叉验证 = counter 余额口径 + usage_log 流水口径 + 计量流水聚合 + 账单合计 / **对账断言双向必有**（正向通过 + 负向差异必报）/ **差异 fail-closed 冻结 + 不自动修数** / 全程留痕）+ **行为 B12 计费读面**（需方/供方查本方计量/账单/对账结论；运营全量 + 手动出账/对账；非本方不可读；1011 段预留）；**§4-3**（计费引擎与账单实现归 3.7.6；计费管理界面归 3.7.7；测试达标与全链路联调归 3.7.8）+ **§5 依赖表**（C-3.1 定价四档 / C-4.1~4.3 合约快照与 V3 两表 / C-7.3 资金通道非本卡）+ **§6-2**（不改上游任何端点/表/错误码）+ **§6-3**（1011 段预留）。ADR `docs/adr/ADR-020-策略执行引擎契约.md` §2.4/§2.5/§2.6/§6（计数口径唯一权威：放行才 +1、拒绝零计数；counter = 余额口径 / log = 流水口径；3.7.x 对账两表交叉验证）；ADR-022 §2.6（放行 1 次 ≡ 计量事件 1 条；事件口径本卡消费）+ 行 100（次序观察项维持现状，对账按现状口径 fail-closed 暴露）；ADR-007（幂等语义；本卡"重复出账被拒"用唯一键而非结果复用）；ADR-005（统一响应 / 九位错误码 / 分页）；ADR-006 §3-5（错误码段位顺延制度）；ADR-009（迁移规范）；ADR-016 §2.7（服务仅绑回环）；ADR-018（coreModules 含 metering-billing 80% 覆盖自动生效）；ADR-001（技术栈冻结）；剧本 `docs/manuals/C-7.2-计量计费中心-验收剧本.md` **V1.1**（S2 出账幕 11 步：按次/包月/分成/复算/不追溯/更正/编号/读面/负向/自动腿说明）+ `docs/manuals/C-7.2-账单核对-验收剧本.md` **V1.0**（S1 对账通过 3 步 + S2 差异处置 5 步 + 附录 A 差异构造）；PRD 行 155（C-7.2"按调用次数/包月/交易额计量，账单生成"，P0）；台账 DB-51（计数口径归因节号勘正——本卡 B11 引用钉死 §2.4/§2.8 并随收口关闭）。 |
| 上游能力边界 | **计费依据上游（只读快照）**：`ctds_contract.contract`（V2 迁移实测）——`contract_no` UK / `product_id` / `product_name` / `provider_subject_no` / `requester_subject_no` / `pricing_model`（FREE/PER_CALL/MONTHLY/REVENUE_SHARE 字符串快照）/ `price_amount` DECIMAL(12,2)（语义随档：按次=元/次、包月=元/月、**分成=百分比 0~100**、免费=NULL）/ `status` 六态 / **`effective_at` / `ended_at`**（无账期/周期列、无约定交易额列——**实测**）；`ctds_contract.product_order`（V4）——定价快照普通列同源（`pricing_model/price_amount`）、`uk_contract_no` 一单一合约；`ctds_contract.contract_usage_counter`（V3）——`used_count` 余额口径（放行才 +1、拒绝零计数；无周期列）；`ctds_contract.contract_usage_log`（V3）——`outcome` ALLOWED/DENIED + `occurred_at`（可切周期）；**约定交易额全仓不存在（任何表/列/代码，实测）**——B9-3 乘数基数无数据源 = 立卡决策点 Q3（规格缺口上报，红线 4）。**计量上游（同服务自有）**：`metering_flow`（V1）——`id/contract_no/requester_no/source_type/source_no/action/resource/occurred_at/created_at`，uk(source_type, source_no)，**无金额列、无周期列**（周期按 `DATE_FORMAT(occurred_at,'%Y-%m')` 派生自然月，与 R-M2 同口径）；现有读面 R-M0~R-M3 四端点 + 权限 `metering.read/governance`；**现无任何账单/计费/对账代码或表（全仓 grep 零命中；71 张表中无 bill）**。**服务现状**：29 主源 Java / 5 测试文件 / **20 例**；迁移仅 V1（下一号 **V2**，`out-of-order:false`）；pom 无 validation / idempotency / pagination；**无调度**（全仓唯一调度先例 = delivery-service `@EnableScheduling` + `@Scheduled(fixedDelayString=...)`，SandboxLifecycleService）；主类无 `@EnableScheduling`；跨 schema 只读先例 = `JdbcContractOwnershipReader` / `JdbcMeteringReconcileReader`（3.7.5 已交付，采集账号已有 contract/usage 表 SELECT 授权）。**通用机制面**：错误码 = 常量类 + `ErrorCode.of("9位码")`；**1011 段 C0001~C0008 已占（订单 4 + 评价 3 + 计量 1，实测），C0009 起空闲**；权限点全景 **50 个**（3.7.5 后 +2，无 `billing.*`）；服务级 yml 自治映射（无平台登记文件）；分页 `common-pagination` `PageQuery/PageResult`（metering 未引入；contract `OrderQueryController` 先例）；`ApiResult` 统一响应已用。**网关**：路由 **10 条**（含 3.7.5 `connector-metering` → 8089），**无 billing 路由**；路由测试 45 例基线。**部署**：`deploy/k8s/` 8 服务对（16 yaml + kustomization），metering-billing deployment/service 已在（8089 / `CTDS_DB_URL` / source-schema env）；本卡 **k8s 零改动**。**门禁**：`gates-config.json` coreModules 含 `metering-billing`（行覆盖 ≥80% 自动生效）、mutation 显式清单仅 did（本卡不纳入，登记演进）、ConfigSha256 基线 `955bdbba…2ee2` 零改动。**测试基线**：metering **20** / contract **261**（25 类）/ gateway **45** / delivery **227** / 前端 **58 文件 749 例**（本卡零前端改动，复跑兜意外）。 |
| 交付物 | ① **迁移 V2 五表**（`billing_bill` 账单主表〔uk_bill_no + 生成列 `active_normal_key` 同合约同周期唯一 + 状态/双方/产品/周期/三模式快照列〕/ `billing_bill_detail` 明细〔按次逐笔 `metering_flow_id` 可溯〕/ `billing_bill_action_log` 动作留痕〔CREATE/CONFIRM/FREEZE/UNFREEZE〕/ `billing_reconciliation` 对账记录〔四方数值 + 复算金额 + 结论 + 差异 JSON〕/ `billing_bill_no_seq` 取号）；② **计费域**（Bill / BillType / BillStatus / BillingPricingModel / BillingErrorCodes **1011C0009~C0015 定稿** / 五端口 + 合约/用量只读 reader）；③ **计费引擎**（PER_CALL = 本期事件数 × 次单价；MONTHLY = 周期费整月；REVENUE_SHARE = 出账参数约定交易额 × 合约快照费率；FREE 不出账单；HALF_UP 两位；快照不可变 + 可复算）；④ **出账双轨**（`BillingOutbillService` 手动端点 B-W1 + `BillingAutoService` 自然月 `@Scheduled` 自动出账〔每月 1 日出上月；cron/enabled 可配；逐合约独立事务；唯一键防重〕）；⑤ **对账**（`BillingReconcileService`：全量余额腿 + 本期流水/计量腿 + 按次数量腿 + 金额复算腿；DIFFERENCE → 冻结 fail-closed；PASS → 解冻；不自动修数；留痕）；⑥ **读面与动作**（B-R1 mine / B-R2 detail / B-R3 governance / B-W2 confirm / B-W3 correction / B-W4 reconcile / B-R4 reconciliation list；归属校验 404 同码同文；分页）；⑦ **网关**（`connector-billing` 路由 `/api/v1/billing/**` → 8089 + 路由测试 1 例；调用点/过滤器/错误码零改动）；⑧ **剧本修订**（C-7.2 **V1.2**：S2 操作路径接口化 + S2-11 自动腿注记 + S1-5 免费档无账单注记；账单核对 **V1.1**：S1/S2 操作路径接口化 + 附录 A 差异构造适配 `metering_flow` 实际列面 + 确认动作路径；业务判定一字不改，PO 批准）；⑨ **文档与留痕**（复用/规格外声明、台账 / 开发日志 / 体量登记、业务可读交付说明、DB-51 勘正关闭留痕）。 |
| 关闭条件 | ① 两级设计获编排师一次确认（**Q1~Q9 + D1**）；② 本地门禁全绿：metering-billing `test`（现有 **20 例零回归** + 新增约 35~45 例全绿 + **核心行覆盖 ≥80% 自动生效**）+ `checkstyle:check` + gateway-service `test`（45 例零回归 + 路由契约 1 例 = 46）+ contract-service `test`（261 例零回归——跨 schema 只读不触达写面核验）+ 全仓 `compile`（19 模块）+ `gates-config.json` 零改动（ConfigSha256 与基线逐字一致）+ 前端四步（test/typecheck/lint/build）零回归兜底；③ 规格 B9（4 规则 + 4 AC）+ B10（4 规则 + 4 AC）+ B11（4 规则 + 4 AC，**双向对账断言**）+ B12（4 规则 + 4 AC）→ 端点/引擎/测试映射齐备；④ **剧本 C-7.2 V1.2 + C-7.2-账单核对 V1.1 修订完成并按接口路径可执行通过**（业务判定一字不改），提示 PO 批准留痕；⑤ 4 视角评审 + 修复循环 ≤3 轮 + 独立复审 PASS；⑥ 编排师验收通过（验收方式届时裁定——接口走查沿 C-6.x/C-7.2 先例）；⑦ 零越界（**上游零改动：合约/目录/订单/计量任何表结构、端点、错误码零触碰；只读快照与计数**；B12 读面之外的 3.7.7 界面 / 3.7.8 联调零触碰；**无资金动作**；**无 MQ**；调度仅 Spring 内置 `@Scheduled`；**无新外部 Maven 依赖**；零前端 / 零 npm；零新权限点以外配置；零门禁配置改动；零口令入库；`/internal/**` 零触碰）；⑧ **账单不可变 + 对账 fail-closed 结构性保证**（无修改金额/明细 SQL；差异必冻结；不自动修数）——由测试锚定（TB7/TB9/TB10）。 |
| 分支 | `feat/WBS-3.7.6-计费引擎与账单`（**自本地 main 梢 `22078d8` 创建** = 3.7.5 合并段批 `4d709bf` + main gate 回填批 `22078d8`——沿链式先例，**ahead 2 随本卡 PR 一并入库**；`origin/main`（`9495b1f`）未动、未建 PR）。 |
| 设计文件 | `docs/designs/WBS-3.7.6-lofi.md`（**V1.0 方向定稿**）/ `docs/designs/WBS-3.7.6-hifi.md`（**V1.0 编码契约**）——非界面类，免 HTML 原型。 |
| 纪律声明 | **上游零改动**：计费只读 `ctds_contract.contract` 快照、`contract_usage_log/counter`、自有 `metering_flow`；不改任何上游表/端点/错误码，不新增 `/internal/**` 端点（跨 schema 只读沿 3.7.5 先例）；**免费档不出账单**（B8-2）；**账单不可变**（无修改金额/明细的 SQL/端点；更正 = 另出更正单，原单逐字不变——B10-3）；**对账 fail-closed**（差异必报 → 冻结 → 确认被拒 → 澄清后解冻；不自动修数——B11-2~B11-4）；**资金语义红线**（无支付/退款/结算划拨/发票——规格 §4-7）；**Q3-A 规格解释须批准留痕**（约定交易额以出账参数登记，V1.0 不采集真实支付金额——B9-3 缺口上报）；**自动出账 = Spring 内置 `@Scheduled`**（沿 delivery 先例，非新框架；单实例演示，多实例靠唯一键不重复计费——登记演进）；**无 MQ / 无新外部依赖 / 零前端 / 零 npm / 零推送**；**剧本只改操作路径与承载说明，业务判定一字不改**；**规格缺口走变更流程/编排师裁定**，不在本卡私自补需求；发现冲突或疑似 S0 安全问题 → **立即停止上报**（红线 4）。 |

---

## 一、范围与设计

- **做什么 W1~W8 / 不做什么 N1~N8**：见 `docs/designs/WBS-3.7.6-lofi.md` §1；
- **结构组成**：lofi §2；**主要流程**：lofi §3；**决策点（待确认）**：lofi §4（**Q1~Q9 + D1**）；**体量**：lofi §5（预估 **2600~3000 行**有效变更；D1 建议不拆分、1 个编码会话）；
- **编码契约**（端点表 / 迁移 V2 五表 / 计费引擎 / 出账幂等 / 自动出账 / 对账算法 / 状态机与更正 / 错误码 / 权限矩阵 / 前置检查 / 测试锚点 / 剧本映射 / 边界）：见 `docs/designs/WBS-3.7.6-hifi.md` §1~§13。

### 立卡前素材盘点（只读，本会话；子智能体代码实测 + 主会话文档核对）

| 项 | 结论 |
| --- | --- |
| ① WBS 锚点 | 行 318 原文核对（计费三模式 + 账单生成含对账断言；依赖 3.7.5 已闭环；预算 1 会话）；下游 3.7.7 界面 / 3.7.8 联调与本卡边界：零触碰 |
| ② 规格锚点 | C-7.1-7.2 V1.0 B9（4 规则 + 4 AC）+ B10（4 规则 + 4 AC）+ B11（4 规则 + 4 AC）+ B12（4 规则 + 4 AC）+ §4-3 归口 + §6-2/§6-3；B7/B8 已交付（3.7.5）、B9 以下本卡；**B9-3 约定交易额无上游数据源 = Q3 缺口上报** |
| ③ 剧本锚点 | C-7.2 V1.1 S2 十一歩本卡承载（手动四档 + 复算 + 不追溯 + 更正 + 编号 + 读面 + 负向 + 自动腿说明）；账单核对 V1.0 S1 三步 + S2 五步 + 附录 A（差异构造方式待适配实际列面）；两剧本操作路径本卡修订（业务判定一字不改） |
| ④ ADR 锚点 | ADR-020 §2.4/§2.5/§2.6/§6（计数权威 + V3 两表对账预登记）；ADR-022 §2.6 + 行 100（计量事件口径 + 次序观察项）；ADR-007（幂等；唯一键 vs 结果复用）；ADR-005（响应/错误码/分页）；ADR-006 §3-5（1011 段顺延）；ADR-009（Flyway）；ADR-016 §2.7（回环）；ADR-018（core 80%）；ADR-001（栈冻结）；零国密接触（账单无加解密面） |
| ⑤ metering-billing 实测 | 29 主源 / 5 测试 / **20 例**；迁移仅 V1（下一号 V2）；端点 R-M0~R-M3；权限 `metering.read/governance`；无调度 / 无分页 / 无幂等依赖；`@EnableScheduling` 缺席；`Clock` bean 缺席；跨 schema 只读先例（contract/usage/delivery）已存在 |
| ⑥ 上游数据模型实测 | contract 列（pricing_model/price_amount/effective_at/ended_at/product_id/product_name/双方/status）；product_order 定价快照同源 + uk_contract_no；usage_counter（used_count 无周期列）；usage_log（outcome/occurred_at）；**无周期列 / 无约定交易额列**；内部端点不返回 pricing（**跨 schema 直读**为准） |
| ⑦ 定价口径实测 | `PricingModel` 四档（catalog）；`price_amount` 语义（按次元/次、包月元/月、分成百分比 0~100、免费 NULL）；合约/订单仅快照透传；**约定交易额全仓零命中** |
| ⑧ 通用机制实测 | 错误码常量类 + `ErrorCode.of`；1011C0001~C0008 已占、**C0009 空闲**；权限点 **50** 无 `billing.*`；`common-pagination` 可用、metering 未引入；`ApiResult` 已用；调度先例 = delivery `@EnableScheduling` + `@Scheduled` |
| ⑨ 门禁/部署/网关实测 | gates coreModules 含 metering-billing（80% 自动）、mutation 仅 did、ConfigSha256 基线；k8s 8 服务对（metering 已在，本卡零改动）；网关 10 路由无 billing（+1 新路由）；路由测试 45 例 |
| ⑩ 测试与体量面 | 基线 metering 20 / contract 261 / gateway 45 / delivery 227 / 前端 58 文件 749 例；本卡预估新增 **35~45 例**（metering）+ 1 路由例；红相归档 `build-output/w376-red-*` |
| ⑪ 演示面 | 本地进程 8080~8089（3.7.5 演示链已停）；本卡演示 = 直连 8089 `/api/v1/billing/**` + 身份头（ADR-016 §2.7；网关正式链路 `http://127.0.0.1:8086/api/v1/billing/**` JWT 注记）；演示数据沿 C-7.2 附录 A（三模式合约组 + 免费档 + N 次放行 + 拒绝 + 沙箱/导出 + 主体 C）；账单/对账数据由本卡端点产生，**禁直改库**（差异构造仅对账剧本附录 A 显式动作） |

**设计要点摘要（供快速表决）**：

1. **扩展 metering-billing（Q1-A）**：计费与计量同域同库，读 `metering_flow` 零跨服务；V2 五表（账单/明细/动作留痕/对账/取号）；k8s 零改动、无新服务。
2. **计费三模式 + 免费不出账（Q4-A）**：按次 = 本期事件数 × 次单价；包月 = 整月周期费；分成 = 出账参数约定交易额 × 合约快照费率；全部取合约快照、账单落不可变快照、HALF_UP 两位、可复算。
3. **出账双轨（Q2-A）**：手动即时端点 + 自然月 `@Scheduled`（每月 1 日出上月，cron/enabled 可配）；同合约同周期生成列唯一键幂等，重复 409；自动出账逐合约独立事务、跳过免费/无计量/分成无基数。
4. **约定交易额（Q3-A，缺口上报）**：无上游列 → 以出账参数登记（仅分成档必填，账单基数+费率双快照可溯）；自动出账该档跳过并告警；需编排师/PO 批准该解释（替选 B 计费域基数登记 / C 上游加列走变更）。
5. **账单不可变与更正（Q7-A）**：状态 `ISSUED/CONFIRMED/FROZEN`；确认动作；更正 = 另出更正单（负=红字/正=补差），原单逐字不变、双向可溯；唯一键只约束普通单，更正单可多份。
6. **对账口径（Q6-A）**：计数腿（全量余额 + 本期流水/计量）+ 按次数量腿 + 金额复算腿；差异必报 → 该合约该周期账单冻结（保存原状态）→ 确认被拒 → 澄清后对账通过解冻；不自动修数；DB-51 引用钉死 §2.4/§2.8。
7. **权限与错误码（Q8-A）**：新增 `billing.read`/`billing.governance`（50→52）；1011C0009~C0015 七码（404/409/400 语义明确、对外文案常量）。
8. **剧本（Q9-A）**：C-7.2 V1.2 + 账单核对 V1.1——S2 与对账两幕操作路径接口化；附录 A 差异构造适配（改一条 `metering_flow.occurred_at` 移出本期，改值不删行）；业务判定一字不改，PO 批准。

## 二、决策点与确认留痕（待编排师一次确认；章程 2.6.3）

> 决策点全文与备选业务影响见 `docs/designs/WBS-3.7.6-lofi.md` §4；下表为摘要。

| 编号 | 问题 | 建议口径 |
| --- | --- | --- |
| **Q1** | 服务落点 | **A 建议**：扩展既有 `metering-billing`（8089 / ctds_metering，迁移 V2 五表）——计费与计量同域同库、读 metering_flow 零跨服务、k8s 零新增。B：新建 billing-service（新端口/库/清单 + 跨服务读计量，部署与鉴权面膨胀，1 会话预算不可控） |
| **Q2** | 周期自动出账机制（B10-1 必需） | **A 建议**：Spring `@Scheduled`（内置，沿 delivery-service `@EnableScheduling` + `@Scheduled` 既有先例）+ 唯一键幂等 + 每月 1 日出上月账（cron/enabled 可配）；单实例演示，多实例唯一键保证不重复计费（登记演进）。B：读面惰性补账（读路径写副作用、"自动"语义弱）；C：仅手动（违 B10-1）；D：quartz/xxl-job（新框架违 ADR-001） |
| **Q3** | **约定交易额数据源（规格缺口）** | **A 建议**：本卡以**出账参数**登记约定交易额（仅分成档、手动必填；账单基数+费率双快照不可变可溯）；自动出账该档跳过并告警；作为诚实边界登记（V1.0 不采集真实支付金额，支付通道归 C-7.3）——**需编排师/PO 批准该解释留痕**。B：计费域新增「计费基数登记」资源（表+端点+剧本登记步，可支撑自动出账但增面）；C：上游合约/订单加列（跨包变更流程，超本卡且违"上游零改动"） |
| **Q4** | 免费档账单 | **A 建议**：不出账单（无计费流水/明细——B8-2 字面）；手动拒绝 1011C0015、自动跳过。B：出 0 元账单（与"不产生计费流水"冲突、消耗编号） |
| **Q5** | 包月口径 | **A 建议**：整月费不按天折算；合约在本自然月内生效（生效 ≤ 月末 且（未终止 或 终止 > 月初））即出整月费。B：按天折算（规格未定义、无周期起止字段）；C：仅月初生效才出账（对月中生效不公） |
| **Q6** | 对账四方口径 | **A 建议**：**计数腿 + 金额复算腿**——① 全量：counter.used_count ≡ usage_log ALLOWED 全量 ≡ metering_flow 全量（无 counter 行跳过）；② 本期：usage_log ALLOWED ≡ metering_flow（按次再 ≡ 账单数量）；③ 金额：账单金额 ≡ 按账单快照独立复算；差异明细逐腿可读。B：仅本期三腿（丢余额腿）；C：counter 强按周期切分（需新增快照表，超最小实现） |
| **Q7** | 账单状态机与更正 | **A 建议**：`ISSUED → CONFIRMED`；`FROZEN`（差异冻结、保存原状态、通过恢复）；更正 = 另出 `CORRECTION` 单（负=红字/正=补差、原因必填），原单逐字不变、双向可溯；不可更正更正单/冻结单。B：允许修改原单（违 B10-3 资金语义红线）；C：只红字不补差（规格"更正单/红字冲正"未限定，补差更完整） |
| **Q8** | 权限点选型 | **A 建议**：新增 `billing.read`（requester/provider/admin）+ `billing.governance`（仅 admin）——实测 50 点零 `billing.*`；沿 `域.动作` 制与服务级 yml 自治。B：复用 metering.read/governance（跨域语义错位，3.7.7 界面权限面混淆） |
| **Q9** | 剧本修订范围 | **A 建议**：C-7.2 转 **V1.2**（S2 操作路径接口化 + S2-11 自动腿注记 + S1-5 免费档无账单注记）；账单核对转 **V1.1**（S1/S2 操作路径接口化 + 附录 A 差异构造适配 metering_flow 实际列面 + 确认动作路径）；业务判定一字不改，PO 批准；本卡验收 = S2 + 对账两幕接口走查。B：不改剧本（操作形态与交付不一致，违修订义务） |
| **D1** | 体量 / 是否拆分 | **建议不拆分**：预估 **2600~3000 行**有效变更（任务卡 §五），**1 个编码会话**（WBS 行 318 = 1 人日）。理由：计费引擎 + 出账双轨 + 对账 + 读面/更正是同一条 S2 + 对账两幕走查链，拆分产生"有账单无对账 / 有对账无账单"中间态；沿 3.5.x / 3.6.x / 3.7.x 服务包先例豁免 400 行上限（登记制）。**实测超 400 行按既有先例登记并请编排师按 D1 确认**。 |

> **确认留痕（✅ 2026-11-02 编排师会话回复"全部按建议"，**Q1~Q9 全采建议 A + D1 不拆分**）**：Q1-A 扩展 `metering-billing`（迁移 V2 五表，不新建服务）/ Q2-A Spring `@Scheduled` 自动出账（沿 delivery 先例）/ **Q3-A 约定交易额以「出账参数」登记**（B9-3 规格缺口的上报解释，**编排师按本行批准留痕**：仅分成档手动出账必填，账单落基数+费率双快照；自动出账该档跳过并告警；V1.0 不采集真实支付金额，支付通道归 C-7.3）/ Q4-A 免费档不出账单 / Q5-A 包月整月费不折算 / Q6-A 对账 = 计数腿 + 金额复算腿（全量余额 + 本期流水/计量 + 按次数量 + 金额复算）/ Q7-A `ISSUED/CONFIRMED/FROZEN` + 更正单不改原单 / Q8-A 新增 `billing.read` + `billing.governance`（50→52）/ Q9-A 剧本 C-7.2 **V1.2** + 账单核对 **V1.1**（业务判定一字不改）/ **D1 = 不拆分**（2600~3000 行，1 个编码会话）。**编码契约（hifi §1~§13）即时生效**。

## 三、规格行为 → 本卡承载 → 剧本步骤 映射表（编码段回填实测锚点）

| 规格承载（V1.0） | 本卡落点（通道 / 端点） | 剧本步骤 | 测试锚点（编码段回填） |
| --- | --- | --- | --- |
| B9-1（按次 = 事件数 × 次单价） | `BillingEngine` PER_CALL + B-W1 手动出账；明细逐笔映射 metering_flow | C-7.2 S2-1 | TB1 |
| B9-2（包月 = 订单周期费，与调用次数无关） | `BillingEngine` MONTHLY（整月费） | C-7.2 S2-3 | TB2 |
| B9-3（分成 = 约定交易额 × 费率） | `BillingEngine` REVENUE_SHARE（Q3-A 出账参数基数；账单基数+费率快照） | C-7.2 S2-4 | TB3 |
| B9-4（可复算；快照变更不追溯） | 账单快照 + `recomputed_amount`；复算 = 明细 + 快照同输入同输出 | C-7.2 S2-5 / S2-6 | TB4 |
| B10-1（出账双轨 + 同合约同周期幂等） | B-W1 手动 + `BillingAutoService` 自动；`uk_active_normal_key` 409 | C-7.2 S2-1/S2-2/S2-11 | TB5 / TB6 |
| B10-2（编号唯一终身不复用） | 取号表 + `uk_bill_no` + 更正单独立编号 | C-7.2 S2-8 | TB7 |
| B10-3（不可篡改；更正单/红字冲正留痕） | 无修改金额明细路径；B-W3 更正单 + 动作留痕；原单零改动 | C-7.2 S2-7 | TB7 |
| B10-4（账单读面；非本方不可读） | B-R1/B-R2/B-R3 + 归属校验 404 同码同文 | C-7.2 S2-9/S2-10 | TB11 |
| B11-1（四方交叉验证正向） | B-W4 对账 + `billing_reconciliation` 记录（四方数值/结论/执行人/时点） | 账单核对 S1-1/S1-2 | TB8 |
| B11-2（账实不符必被报，双向） | 差异腿判定 + `details_json` 差异明细（不允许静默通过） | 账单核对 S2-1/S2-2 | TB9 |
| B11-3（差异 fail-closed 冻结/澄清解冻） | `FROZEN` + B-W2 confirm 被拒 1011C0011 + PASS 解冻 | 账单核对 S2-3/S2-5 | TB9 / TB14 |
| B11-4（留痕完整；不自动修数） | 对账记录 + 动作留痕；仅状态流转与留痕写入 | 账单核对 S2-4 | TB10 |
| B12-1（本方读面 + 对账结论可读） | B-R1/B-R2/B-R4（合约参与方校验） | C-7.2 S2-9 + 账单核对 S1-3 | TB11 |
| B12-2（运营全量 + 手动出账/对账） | B-R3 + B-W1/B-W4（billing.governance） | C-7.2 S2-1 / 账单核对 S1-1 | TB11 |
| B12-3（非本方一律拒绝） | B-R2/B-R4 404 + 1011C0009 同码同文 | C-7.2 S2-10 | TB11 |
| B8-2（免费档不产生计费流水） | FREE 不出账单（手动 1011C0015 / 自动跳过）；计量流水照常 | C-7.2 S1-5 注记 | TB12 |
| B9~B12 错误语义 1011 预留 | `BillingErrorCodes` 1011C0009~C0015 | （全幕错误文案观察） | TB13 |

**编码段实测回填（2026-11-02，mvn 实测 42 例 + 网关 46 例；修复批后 48 例）**：TB1~TB14 全部锚点落地，用例合并承载（一个用例覆盖同族多断言），落点如下——

| 锚 | 实测落点（类.方法） | 断言要点 |
| --- | --- | --- |
| TB1 / TB4 | `BillingOutbillIntegrationTest.perCallBillTracesEachEvent` / `existingBillUnaffectedByPricingChange` | 金额 9.00 = 3 × 3.00、明细 3 行逐笔 `meteringFlowId`、明细求和独立复算 = 账单金额；改价后旧单逐字不变、新周期按新价 |
| TB2 | `BillingOutbillIntegrationTest.monthlyBillIgnoresCallCount` | 两期调用次数 1 vs 5、金额均 100.00、`quantity` 为空、单行 `PERIOD_FEE` |
| TB3 | `BillingOutbillIntegrationTest.revenueShareBillUsesAgreedAmountAndRate` | 10000 × 5% = 500.00、`baseAmount` / `shareRate` 快照可读；缺基数 / 基数 0 → 400 1011C0013 |
| TB5 | `BillingOutbillIntegrationTest.duplicateOutbillRejectedIncludingConcurrent` | 重复 409 1011C0010；并发两请求恰一 201 一 409、账单仅一行 |
| TB6 | `BillingAutoReconcileIntegrationTest.autoOutbillScansAndSkips` / `schedulerRespectsDisabledFlag` | scanned 3 / billed 2 / skippedNoBase 1；重复运行 billed 0 / duplicate 2；`enabled=false` 调度零出账 |
| TB7 | `BillingOutbillIntegrationTest.correctionKeepsOriginalImmutable` | 更正单 `CORRECTION` + `correctionOfBillNo` + 独立编号 + 负金额；原单金额明细逐字不变、`correctionBillNos` 双向可溯；更正单不可再更正 1011C0012；金额 0 / 原因空 400 |
| TB8 | `BillingAutoReconcileIntegrationTest.reconcilePassesAfterNormalOutbill` | 四方数值 2/2/2 全量一致、`billQuantity` 2、金额 6.00 = 复算 6.00、结论 PASS、记录落库、参与方可读 |
| TB9 | `BillingAutoReconcileIntegrationTest.reconcileDifferenceFreezesAndRestores` | 改一条流水时点移出本期 → DIFFERENCE（含 `BILL_QUANTITY_VS_METERING`）、账单 FROZEN、confirm/更正 409 1011C0011；还原 → PASS → 解冻 → confirm 200 |
| TB10 | `BillingAutoReconcileIntegrationTest.reconcileNeverMutatesBusinessData` | 差异前后账单金额 / 明细条数 / 计量行数零变化；动作留痕含 CREATE + FREEZE |
| TB11 | `BillingReadIntegrationTest`（5 例） | 本方 mine 隔离与 `myRole` + 合约/周期过滤正例；非本方与不存在 404 同码同文 1011C0009；治理全量 + 状态/合约过滤、非运营 403；分页与过滤参数 400（含 billNo/contractNo 形态 400） |
| TB12 | `BillingOutbillIntegrationTest.freeContractProducesNoBill` | FREE 出账 409 1011C0015、零账单、计量流水照常留痕 |
| TB13 | `BillingOutbillIntegrationTest.unbillableContractsAndInvalidParams` + `BillingMigrationStructureIntegrationTest`（3 例） | 未生效 / 已终止 / 无计量 409；合约不存在 404 1011C0014；周期非法 / 未来周期 / 合约号非法 400；五表存在、`uk_bill_no` / `uk_active_normal_key` 与生成列 STORED、更正单可多份、取号行就绪 |
| TB14 | `BillingOutbillIntegrationTest.confirmStateMachineAndActionLog` | ISSUED → CONFIRMED、重复确认 1011C0012、动作留痕 CREATE + CONFIRM |
| TG1 | `GatewayConnectorRoutesIntegrationTest`（10 条路由清单 + `connectorBillingRouteForwardsInstancePath`） | `/api/v1/billing/**` 路由装配（单 Path 谓词、无路由级过滤器）、实例路径转发、不进交付判定链 / 零计量；46 例零回归 |

**修复批补强（2026-11-02，4 视角评审循环 1）**：TB6（`scheduledOutbillTargetsPreviousMonth`：固定时钟下"出上月/不出当月"+ 自动腿金额 + `created_by=system`）、TB7（`billNumbersStrictlyIncrease`；更正原因拼装后 495/496 边界）、TB8/TB9（`reconcileDetectsEachDifferenceLeg` 四类差异逐腿 expected/actual；`reconcileDifferenceFreezesAndRestores` 补 CONFIRMED 原状恢复 + UNFREEZE 留痕）、TB11（过滤正例 / `meteringFlowId` 集合逐一对应 / `BL` 序号）、TB13（索引锚 + 主源"无金额明细 UPDATE"扫描锚 + `next_no` 初值与取号）、服务级 advice 回归（`malformedRequestMapsToParamInvalid`：坏 JSON / 缺必填 query / 计量侧 1000C0001 零漂移）。

> 权限点（**新增 2 个**，建议口径 Q8）：B-R1/B-R2/B-R4 = `billing.read`（requester/provider/admin + 归属硬校验）；B-R3/B-W1~B-W4 = `billing.governance`（仅 admin）。**不调用** `/internal/**`、不改任何上游端点；3.7.7/3.7.8 零触碰。

## 四、执行记录

| 项 | 内容 |
| --- | --- |
| 立卡段（2026-11-02） | 🟡 **立卡段完成（待一次确认）**：任务卡 + lofi **V0.9** + hifi **V0.9** 落盘（非界面类，免 HTML 原型）；素材盘点 = 只读子智能体代码实测（metering 服务现状 / 合约与订单表 / 定价快照 / 错误码 / 权限 / 调度 / 分页 / 门禁 / 部署 / 网关 / 测试基线 / 两剧本 / ADR）+ 主会话文档核对（规格 B9~B12、两剧本、ADR-020/022/007、WBS 行 318）；**新发现 = B9-3 约定交易额全仓无数据源（规格缺口，Q3 上报）**；分支 `feat/WBS-3.7.6-计费引擎与账单` 自本地 main `22078d8` 创建（3.7.5 收口 + 回填批随本卡 PR 入库——链式先例）；**本段零代码、零依赖、零门禁配置改动，未动 `main`**；台账"进行中"行暂未改（沿 3.7.2/3.7.3/3.7.4/3.7.5 先例，编码段更新）；立卡批本批提交（未推送）。 |
| 确认段（2026-11-02） | ✅ **一次确认完成（编排师会话"全部按建议"）**：Q1~Q9 全采建议 A + D1 不拆分（留痕 = §二 确认段引述行）；lofi **V1.0 方向定稿** / hifi **V1.0 编码契约即时生效**；**Q3-A 规格缺口解释已获批准**（约定交易额 = 出账参数登记，自动出账该档跳过并告警，诚实边界登记）；零代码改动，确认批本地提交（未推送）。 |
| 编码段（2026-11-02） | 🟢 **编码交付完成（待 4 视角评审）**：迁移 V2 五表 + 计费域（4 枚举 / 7 错误码 / 8 端口 / 5 记录）+ 计费引擎三模式 + 出账双轨（手动 B-W1 + 自然月 `@Scheduled`）+ 对账（计数腿 + 金额复算腿，fail-closed）+ 读面/确认/更正 8 端点 + 网关 1 路由；**metering-billing 42 例全绿（既有 20 零回归 + 新增 22）**、行覆盖 **91.61%**（≥80% 核心档）、checkstyle 0 违规；网关 **46 例**（45 + 1 路由）；合约 **261 例**零回归；全仓 20 模块 compile SUCCESS；前端 58 文件 749 例 + typecheck + lint + build 全绿（零改动兜底）；剧本 C-7.2 **V1.2** + 账单核对 **V1.1** 修订完成（业务判定一字未改，待 PO 批准）；**上游零改动 / 零新外部依赖 / 零前端 / 零门禁配置改动**。 |
| 评审段（2026-11-02） | 🟡 **4 视角评审循环 1 完成**：单发串行只读子智能体 ×4，全部 PASS-with-findings，**0×P0 / 0×S0**；1×P1 本批必修（测试固定时钟缺失 = 时间炸弹）+ 1×P1 跨卡待裁定（B12-2 运营方查全量"计量"流水与 3.7.5 归属硬校验冲突，按红线 4 上报）；P2 池 12 条（代码 6 / 测试 6）、P3 池 14 条；主会话复核补 1 条（分成基数无上限）；归档 `build-output/w376-review-round1.md`；**零代码、零提交**。 |
| 修复批段（2026-11-02） | 🟢 **修复批第 1 轮完成**：P1-1 固定时钟（4 测试类共用 `FixedClockTestConfiguration`）+ 代码 6 处（冻结原状态行内真值+对账读加行锁 / reason 拼装后校列宽 / `billNo`·`contractNo` 形态 400 / 分成基数上限 / 取号 `@Transactional`+端口契约）+ 测试 6 组；**metering-billing 48 例全绿（42→48）**、行覆盖 **94.51%**、checkstyle 0；网关 **46** / 合约 **261** / 全仓 20 模块 compile / 前端 58 文件 749 例四步全绿；`gates-config.json` SHA256 未变（`955bdbba…2ee2`）；**P1-2 未修复（待编排师裁定，不静默绕过）**；P2-2 采 `FOR UPDATE` 闭合；其余 P3 处置与登记见 hifi §13 **E3**；两剧本**不动**。 |

## 五、体量登记（立卡时填写）

| 项 | 数值 / 说明 |
| --- | --- |
| 实测（立卡段） | 任务卡 **107 行**；`docs/designs/WBS-3.7.6-lofi.md` **112 行**；`docs/designs/WBS-3.7.6-hifi.md` **309 行** → 合计 **528 行**（行数口径 = 文件实际行数实测，2026-11-02 立卡段）；**无代码、无测试、无新 Maven 依赖**（common-pagination 为平台内部模块，编码段引入） |
| 预估（编码段） | 有效变更 **2600~3000 行**：迁移 V2 五表 ≈120 + 计费域 ≈180 + 引擎 ≈250 + 出账/自动/对账/读面服务 ≈450 + 仓储/只读 reader ≈350 + 接口/DTO/异常 ≈350 + 配置/调度 ≈60 + 网关路由与测试 ≈40 + 后端测试 ≈800~1000 + 剧本 V1.2/V1.1 与文档 ≈350~450；**1 个编码会话**；零前端 / 零 npm / 零 MQ / 零新外部依赖；新增迁移 1 件（五表）；新权限点 2 个；新错误码 7 个 |
| 超 400 行指引说明 | 本卡为"计费引擎 + 账单 + 对账"原子集（三模式计费 + 出账双轨 + 不可变更正 + 对账 fail-closed + 读面一次性）；拆则 S2 与对账两幕走查链断裂（对账依赖已出账账单与冻结动作）；请编排师在 **D1** 一并表态 |
| 实测（编码段，2026-11-02） | **59 文件 / 3844 行插入 / 36 行删除**（`git diff --shortstat f440cd1 ce44264` 实测，有效变更 ≈3808）：迁移 V2 五表 172 + 主源 Java 31 文件 ≈2050（domain **21 = 5 枚举 / 7 端口 / 8 记录 / 1 常量类** / application 11 / infrastructure 7 / interfaces 3 + 配置与主类 3）+ 测试 5 文件 ≈800 + 网关 2 文件 ≈20 + 剧本与任务卡 ≈750；**业务代码仅本服务内新增，上游（合约 / 目录 / 订单 / 计量）零改动**；零前端 / 零 npm / 零新外部依赖（+common-pagination 平台内部模块）；新增迁移 1 件（五表）/ 权限点 2 个 / 错误码 7 个。超预估带 2600~3000 约 27%（主因 = 测试断言密度与剧本操作路径表比骨架详实），**D1 已批准不拆分（登记制）**。 |
| 实测（修复批，2026-11-02） | **19 件**（跟踪文件 `git diff --numstat` = 17 文件 / +455 / −62；另新增 2 件：固定时钟测试配置 27 行 + 补交评审段日志）：主源 **11 文件**（仓储实现 + 仓储端口 / 对账服务 / 读面服务 / 引擎 / 自动出账服务 / 两 Controller / 异常处理器 / 取号实现 + 取号端口）+ 测试 5 文件（4 改 + 1 新增固定时钟配置）+ 文档 2 文件；**零新增依赖 / 零迁移改动 / 零网关改动 / 零前端 / 零门禁配置改动**；用例 **42 → 48**（基数上限断言并入 TB3，未单列用例）。 |

## 六、提交前自检单（章程附录 B1，编码段填写）

1. **两级设计门禁已过**：lofi **V1.0** 方向定稿 + hifi **V1.0** 编码契约（编排师 2026-11-02"全部按建议"一次确认，留痕 §二）；实现与设计逐条一致——五表列面 / 唯一键 / 生成列与 §2 逐字一致，8 端点与 §1 一致（权限点、错误码、响应字段记录），三模式计算与 §3.2 一致，出账事务与幂等（唯一键 → 409）与 §3.3 一致，自动出账扫描与跳过规则与 §4 一致，对账六类判定 + 冻结/解冻与 §5 一致，状态机与更正与 §6 一致，错误码 7 个与 §7 一致，权限矩阵与 §8 一致，网关 1 路由与 §9 一致。**实现形态偏差 2 处已登记**：**E2**（错误映射复用服务级既有处理器，错误码 / HTTP 语义 / 文案逐字不变）、**E3**（评审修复批的口径补充与登记项：冻结原状态行内真值 + 对账读行锁、`reason`/`billNo`/`contractNo` 校验口径、分成基数上限、`next_no` 语义与首个账单号、`skippedFree` 不实现理由、`reason` 转义义务、收敛与平台项登记）。
2. **映射表**：§三 已回填（规格 B9/B10/B11/B12 + B8-2 + 1011 预留 → 端点/引擎 → 剧本步骤 → TB1~TB14/TG1 实测类与方法，含每例断言要点）。
3. **复用声明（含检索过程）**：`common-pagination` `PageQuery/PageResult`（沿 contract `OrderQueryController` 先例，本服务首次引入）；`common-errorcode` `ApiResult` / `ErrorCode` / `BizException`；`common-auth` `@RequirePermission` + `AuthContext` + 服务级角色映射；Spring Boot 内置 `@Scheduled` + `@EnableScheduling`（沿 delivery `SandboxLifecycleService` 先例）；`Clock` bean（沿 `DeliveryBeanConfig` 先例）；跨 schema 只读直读（沿 `JdbcContractOwnershipReader` 3.7.5 先例，`ctds.metering.source-schema.contract` 配置化）；`SharedMySqlContainer` 测试基建（模块内第 6 副本，未新增容器）；单行原子取号（沿 `contract_no_seq` 先例）；生成列唯一键（沿 3.7.3 `active_order_key` 先例）。**规格外实现声明 = 空**（上游零改动 git 实证；无 3.7.7 界面 / 3.7.8 联调夹带）。
4. **本地全部检查命令与结果**（修复批复跑，2026-11-02；编码段数字括注）：
   - `mvn -B -ntp -pl services/metering-billing org.jacoco:jacoco-maven-plugin:0.8.12:prepare-agent test org.jacoco:jacoco-maven-plugin:0.8.12:report checkstyle:check` → **48 例 0 失败 0 跳过**（编码段 42）+ **行覆盖 94.51%**（missed 55 / covered 946 / total 1001，≥80% 核心档；编码段 91.61%）+ **checkstyle 0 违规** + BUILD SUCCESS；
   - `mvn -B -ntp -pl services/gateway-service test` → **46 例 0 失败**（45 基线 + 1 路由契约零回归）；
   - `mvn -B -ntp -pl services/contract-service test` → **261 例 0 失败**（跨 schema 只读不触达写面）；
   - `mvn -B -ntp -DskipTests compile` → **20 模块 BUILD SUCCESS**；
   - 前端兜底（零改动）：`npm run test` **58 文件 749 例全绿** + `typecheck` + `lint` + `build` 全 EXIT=0；
   - 门禁配置零改动：`scripts/gates/gates-config.json` SHA256 = `955bdbba…2ee2`（与基线逐字一致）。
   - 证据（gitignored）：编码段 `build-output/w376-red-backend.log` / `w376-metering-test.log` / `w376-verify-backend.log` / `w376-gateway-test.log` / `w376-contract-test.log` / `w376-compile-all.log` / `w376-frontend-test.log`；修复批 `w376-fix-verify-backend.log` / `-gateway.log` / `-contract.log` / `-compile-all.log` + `services/metering-billing/target/site/jacoco/jacoco.csv`。
5. **高风险点自查**：并发——同合约同周期由生成列唯一键兜底，并发用例（CyclicBarrier 双请求，10s/30s 超时兜底）实测恰一 201 一 409；**冻结/解冻读加 `FOR UPDATE`（同合约同周期串行化）+ 冻结原状态取行内真值**（修复批闭合评审 P2-1/P2-2，登记 E3-1）；事务——出账（账单 + 明细 + 留痕）与对账（状态流转 + 留痕 + 记录）各自单事务，自动出账逐合约独立事务（`@Transactional` 跨 bean 代理），取号器自带事务边界；加解密——不涉（账单 / 对账零敏感原文，无密钥入库）；性能——对账 / 出账均为单合约小查询集（本期计量按 `idx_flow_contract` 前缀 + `ORDER BY occurred_at`），万级压测归 4.2.1；**不可变结构性保证**——仓储无金额 / 明细更新 SQL（仅有 3 条状态流转 UPDATE），新增源码扫描锚（`sourceHasNoAmountOrDetailUpdateSql`）+ TB10 实测零修数。
6. **验收剧本更新建议（业务语言）**：两剧本均需随本卡修订（**已修订，待 PO 批准**）——C-7.2 转 **V1.2**（S2 操作路径改为接口承载 + 自动出账说明步更新 + 免费档"无账单"补验）、账单核对转 **V1.1**（操作路径接口化 + 差异构造改为"改一条流水的事件时点移出本期，改值不删行"）；**各步业务判定与预期结果一字未改**。**修复批结论：两剧本无需再次更新**（本轮 findings 均不改变业务判定、操作路径与预期结果）。
7. **业务可读交付说明**：
   - **完成了什么**：给"计量计费中心"补上了计费能力——按次（次数 × 单价，逐笔可查）、包月（整月固定费，与调用次数无关）、交易额分成（约定交易额 × 费率）三种账单自动算钱；运营可手动出账，系统也会每月 1 日自动为上月出账（重复出账会被拒绝）；账单生成后金额不可改，需要调整时另出"更正单"（红字冲正或补差），原单原样保留；运营可确认账单；出账后可执行"对账"，系统把合约计数、放行流水、计量流水、账单四方和金额复算逐项比对，**有任何不一致就报差异并把相关账单冻结**（冻结期间不能确认），澄清并还原后重新对账通过才解冻；需方 / 供方各查各的账单与对账结论，查别人的一律拒绝且提示同一句话（防猜测）；新增 2 个权限点（查账单 / 运营账单操作）。
   - **如何演示**：按《C-7.2 计量计费中心》S2 幕（V1.2 操作路径表逐条 curl）与《C-7.2 账单核对》S1 / S2 幕执行；服务直连 `http://127.0.0.1:8089`（身份头），正式链路经网关 `http://127.0.0.1:8086/api/v1/billing/**`（JWT）。
   - **注意事项**：① 交易额分成的"约定交易额"本次作为**出账参数**填入（合同 / 订单里没有这一栏，属已批准的诚实边界，真实支付金额对接归 C-7.3）；② 自动出账对该档跳过并告警，需运营手动补出；③ 免费档不出账单（符合规格"不产生计费流水"）；④ 演示差异构造仅限对账剧本附录 A 的显式动作，改值不删行、必须还原；⑤ 演示库执行 V2 迁移前请确认备份（Flyway 启动即应用）。

---

> **边界复述（红线自查）**：本卡**只做规格 B9 + B10 + B11 + B12**；**上游零改动**（合约/目录/订单/计量任何表结构、端点、错误码零触碰；只读快照与计数）；**免费档不出账单**；**账单不可变**（无金额/明细修改路径；更正 = 另出更正单）；**对账 fail-closed**（差异必报 → 冻结 → 确认被拒 → 澄清解冻；不自动修数）；**无资金动作**（无支付/退款/结算/发票）；**无 MQ / 无新外部依赖 / 零前端 / 零 npm / 零推送**；调度仅 Spring 内置 `@Scheduled`（delivery 先例）；**Q3-A 规格解释须批准留痕**；**剧本业务判定一字不改**；**规格缺口走变更流程/编排师裁定**；发现冲突或疑似 S0 安全问题立即停止上报（红线 4）。
