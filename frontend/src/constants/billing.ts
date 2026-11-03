/**
 * 计费域常量单一源（WBS-3.7.7 hifi §2.3 / §7）：
 * - 枚举 → 中文标签 / tag 色：未知枚举值一律原样显示（不隐藏、不猜测，防上游扩展导致界面失真）；
 * - 业务提示文案集中本文件（视图零散写中文），文案口径与规格 C-7.1-7.2 B9~B12 逐字对齐；
 * - 定价档标签与四档说明分置：标签复用 `constants/catalog.ts` 的 `PRICING_MODEL_LABELS`（同一枚举
 *   不二写），本文件只补「计费口径说明」（hifi §12 E1 勘误登记）；
 * - 硬约束：本文件零运营角色名字面量、零端点 URL（`PortalSourceGuard.spec.ts` / `BillingSourceGuard.spec.ts` 扫描面）。
 */

/** 账单状态标签（ISSUED / CONFIRMED / FROZEN 三态，hifi §2.3）。 */
export const BILL_STATUS_LABELS: Record<string, string> = {
  ISSUED: '已开具',
  CONFIRMED: '已确认',
  FROZEN: '已冻结',
}

/** 账单状态 tag 类型（Element Plus tag type）。 */
export const BILL_STATUS_TAG: Record<string, string> = {
  ISSUED: 'info',
  CONFIRMED: 'success',
  FROZEN: 'danger',
}

/** 账单类型标签（普通账单 / 更正单）。 */
export const BILL_TYPE_LABELS: Record<string, string> = {
  NORMAL: '普通账单',
  CORRECTION: '更正单',
}

/** 四档计费口径说明（费率视图卡片 + 出账弹窗提示复用；B9）。 */
export const PRICING_MODEL_RULES: Record<string, string> = {
  FREE: '不出账单、不产生计费流水',
  PER_CALL: '本期事件数 × 次单价，明细逐笔可溯',
  MONTHLY: '整月周期费，与调用次数无关',
  REVENUE_SHARE: '约定交易额 × 费率（费率 = 定价百分比 0~100）',
}

/** 对账结论标签（PASS / DIFFERENCE）。 */
export const CONCLUSION_LABELS: Record<string, string> = {
  PASS: '通过',
  DIFFERENCE: '差异',
}

/** 对账结论 tag 类型。 */
export const CONCLUSION_TAG: Record<string, string> = {
  PASS: 'success',
  DIFFERENCE: 'danger',
}

/** 七类差异判定中文说明（未知值由视图原样显示）。 */
export const DIFFERENCE_TYPE_LABELS: Record<string, string> = {
  COUNTER_VS_USAGE_LOG_ALL: '合约余额计数 vs 全量执行流水',
  USAGE_LOG_VS_METERING_ALL: '全量执行流水 vs 全量计量流水',
  USAGE_LOG_VS_METERING_PERIOD: '本期执行流水 vs 本期计量流水',
  BILL_QUANTITY_VS_METERING: '按次账单数量 vs 本期计量流水数',
  AMOUNT_RECOMPUTE: '按快照复算金额 vs 账单合计',
  MISSING_IN_BILL: '计量流水缺账（未进账单）',
  EXTRA_IN_BILL: '账单多计（无对应流水）',
}

/** 账单动作留痕标签（时间线渲染源）。 */
export const BILL_ACTION_LABELS: Record<string, string> = {
  CREATE: '创建',
  CONFIRM: '确认',
  FREEZE: '冻结',
  UNFREEZE: '解冻',
}

/** 计量源类型标签（`MeteringSourceType` 四值封闭枚举）。 */
export const SOURCE_TYPE_LABELS: Record<string, string> = {
  GATEWAY_API: '网关 API 调用',
  FILE_DELIVERY: '文件交付',
  SANDBOX_EXPORT: '沙箱导出',
  SANDBOX_JOB: '沙箱任务',
}

/** 账单明细行类型标签（原值 + 中文）。 */
export const DETAIL_LINE_TYPE_LABELS: Record<string, string> = {
  USAGE: '按次计量',
  PERIOD_FEE: '周期费',
  ADJUSTMENT: '调整',
}

/** 出账四档口径提示（P2 说明卡常显）。 */
export const OUTBILL_RULE_HINT =
  '出账按合约定价快照计算：按次 = 事件数 × 次单价；包月 = 整月周期费；交易额分成 = 约定交易额 × 费率；免费档不出账单。'

/** 分成基数输入提示（P2：仅分成档必填）。 */
export const SHARE_BASE_HINT = '仅交易额分成档必填，其余档留空'

/** 冻结态不可确认提示（P3 / B11-3）。 */
export const FROZEN_CONFIRM_HINT = '账单已冻结，需对账澄清后重新对账解冻'

/** 更正金额语义提示（P5）。 */
export const CORRECTION_AMOUNT_HINT = '负 = 红字冲正；正 = 补差；最多两位小数'

/** 改价不追溯口径说明（P9 / B9-4）。 */
export const PRICE_NO_RETRO_HINT =
  '定价来源 = 目录域产品定价（四档），下单时快照进合约与订单；账单只认快照，改价不追溯已出账单。'

/** 自动出账口径说明（E0-3：cron 走配置文件，界面只说明、不提供运行时改配置）。 */
export const AUTO_OUTBILL_HINT = '每月 1 日 00:10 自动出上月账（后台配置，界面不提供改配置入口）。'

/** 明细合计对照免责句（P3 / B9-4：不拦截、不改值）。 */
export const DETAIL_SUM_DISCLAIMER = '账单金额以服务端为准'

/** 确认弹窗文案（P4）。 */
export const CONFIRM_DIALOG_TEXT = '确认后账单状态将变为"已确认"，该动作会留下操作记录。'

/** 更正弹窗语义说明（P5：不是修改原单）。 */
export const CORRECTION_DIALOG_TEXT = '更正不是修改原单：系统会另出一张更正单，原单金额与明细保持不变。'

/** 差异对账冻结提示（P6 / B11-3）。 */
export const RECONCILE_DIFFERENCE_HINT = '该合约该账期账单已冻结，确认前需澄清差异并重新对账通过。'

/**
 * 计量流水页说明条（P7 / B12-2；§12 E5 勘误：原文指向「本方读面」但本卡未交付该页面）。
 * 只陈述权限事实，不指向不存在的界面。
 */
export const METERING_PAGE_DESC =
  '本页为运营方全量逐条流水口径（规格 B12-2）；需方／供方仅可读本方流水，不在此页。'

/** 订单检索页说明条（P8 / B4-2）。 */
export const ORDER_PAGE_DESC = '运营检索为全量口径（C-7.1 B4-2）；订单详情仅当事方可读。'

/** 门户账单页说明（P10）。 */
export const PORTAL_BILL_PAGE_DESC = '仅显示与本主体相关的账单（我是需方或供方）'

/** 账单不存在 / 不可读（与后端 1011C0009 同码同文防枚举）。 */
export const BILL_NOT_FOUND_TIP = '账单不存在或不可读'

/** 空态文案（P1 / P7 / P8 / P10）。 */
export const BILL_EMPTY_TEXT = '暂无账单，可调整过滤条件或发起手动出账'
export const PORTAL_BILL_EMPTY_TEXT = '暂无账单'
export const FLOW_EMPTY_TEXT = '暂无计量流水'
export const ORDER_EMPTY_TEXT = '暂无订单'
export const DIFFERENCE_EMPTY_TEXT = '无差异'

/** 前端空值拦截提示（仅体验层，业务判定在服务端）。 */
export const CONTRACT_REQUIRED_TIP = '请填写合约号'
export const PERIOD_REQUIRED_TIP = '请选择账期'
export const CORRECTION_AMOUNT_REQUIRED_TIP = '请填写更正金额'
export const CORRECTION_REASON_REQUIRED_TIP = '请填写更正原因'

/** 更正原因长度上限（与服务端留痕列宽口径一致：用户输入 200 字）。 */
export const CORRECTION_REASON_MAX_LENGTH = 200

/** 通用动作与分区标签。 */
export const SEARCH_LABEL = '查询'
export const RESET_LABEL = '重置'
export const RETRY_LABEL = '重试'
export const BACK_TO_LIST_LABEL = '返回列表'

/** 对账数值行标签（P6 四方 / 八方数值表）。 */
export const RECONCILE_FIGURE_LABELS: Record<string, string> = {
  counterUsedCount: '合约计数（余额口径）',
  usageLogAllowedCount: '执行流水放行数（本期）',
  meteringFlowCount: '计量流水数（本期）',
  usageLogAllowedAllTime: '执行流水放行数（全量）',
  meteringFlowAllTime: '计量流水数（全量）',
  billQuantity: '按次账单数量',
  billTotalAmount: '账单合计金额',
  recomputedAmount: '按快照复算金额',
}

/** 出账成功提示（含账单号）。 */
export function outbillSuccessTip(billNo: string): string {
  return `出账成功：${billNo}`
}
