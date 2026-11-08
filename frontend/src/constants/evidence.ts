/**
 * 存证域常量单一源（WBS-3.8.5 hifi §2 / lofi §2-S3）：
 * - 枚举 → 中文标签 / tag 色：未知枚举值一律原样显示（不隐藏、不猜测，防上游扩展导致界面失真）；
 * - 环节 / 状态 / 核验结论 / 异常类型 / 留痕动作五组双 map（LABELS + TAG type）；
 * - 枚举值以前置检查 P1 实测出参为准（AUTHORIZATION/CONTRACT/DELIVERY/USAGE、
 *   PENDING/ON_CHAIN/FAILED、PASS/MISMATCH/NOT_ON_CHAIN、CREATED/CHAIN_SUCCEEDED/
 *   CHAIN_FAILED/VERIFIED），未实测动作值不预设标签（labelOf 兜底原样）；
 * - 硬约束：本文件零 admin 字面量、零端点 URL（EvidenceSourceGuard.spec.ts 扫描面）；
 * - 文案规避"修改 / 删除"字样（只读铁律的源集守卫断言面）。
 */

/** 存证环节标签（四环节；P1 实测 E1 matrix 键）。 */
export const EVIDENCE_STAGE_LABELS: Record<string, string> = {
  AUTHORIZATION: '授权',
  CONTRACT: '合约',
  DELIVERY: '交付',
  USAGE: '使用',
}

/** 存证环节 tag 类型。 */
export const EVIDENCE_STAGE_TAG: Record<string, string> = {
  AUTHORIZATION: 'info',
  CONTRACT: 'info',
  DELIVERY: 'info',
  USAGE: 'info',
}

/** 存证状态标签（三态；B3-1 显式呈现）。 */
export const EVIDENCE_STATUS_LABELS: Record<string, string> = {
  PENDING: '待上链',
  ON_CHAIN: '已上链',
  FAILED: '上链失败',
}

/** 存证状态 tag 类型。 */
export const EVIDENCE_STATUS_TAG: Record<string, string> = {
  PENDING: 'warning',
  ON_CHAIN: 'success',
  FAILED: 'danger',
}

/** 核验结论标签（三态 + 报告分布未核验态；P1 实测 conclusionSummary 四键）。 */
export const EVIDENCE_VERIFY_CONCLUSION_LABELS: Record<string, string> = {
  PASS: '通过',
  MISMATCH: '不一致',
  NOT_ON_CHAIN: '未上链',
  NOT_VERIFIED: '未核验',
}

/** 核验结论 tag 类型（三色结论卡同源：绿 / 红 / 橙；未核验 info）。 */
export const EVIDENCE_VERIFY_CONCLUSION_TAG: Record<string, string> = {
  PASS: 'success',
  MISMATCH: 'danger',
  NOT_ON_CHAIN: 'warning',
  NOT_VERIFIED: 'info',
}

/** 异常类型标签（E2 三类并集；P1 实测 anomalyType 值域）。 */
export const EVIDENCE_ANOMALY_LABELS: Record<string, string> = {
  PENDING: '待上链',
  FAILED: '上链失败',
  MISMATCH: '核验不一致',
}

/** 异常类型 tag 类型。 */
export const EVIDENCE_ANOMALY_TAG: Record<string, string> = {
  PENDING: 'warning',
  FAILED: 'danger',
  MISMATCH: 'danger',
}

/** 留痕动作标签（P1 实测四值；未实测动作值由 labelOf 兜底原样，不预设）。 */
export const EVIDENCE_ACTION_LABELS: Record<string, string> = {
  CREATED: '创建',
  CHAIN_SUCCEEDED: '上链成功',
  CHAIN_FAILED: '上链失败',
  VERIFIED: '核验',
}

/** 时间线业务留痕节点跨域来源标签（P1 实测 source 值域；未知原样）。 */
export const EVIDENCE_TRAIL_SOURCE_LABELS: Record<string, string> = {
  SPACE: '空间域',
  CONTRACT: '合约域',
  DELIVERY: '交付域',
}

/** 未知枚举兜底（did / space / catalog 同型第 4 份；跨域收敛归 DB-105 前端横切收敛批）：命中标签用标签，未命中原样显示。 */
export function labelOf(labels: Record<string, string>, value: string | null | undefined): string {
  if (value === null || value === undefined || value === '') return '—'
  return labels[value] ?? value
}

/** 空值占位（时间显示 / 文本列通用）。 */
export function orDash(value: string | number | null | undefined): string | number {
  if (value === null || value === undefined || value === '') return '—'
  return value
}

/** 摘要中截断（hifi §4 通用契约；≤24 位原样，长摘要 = 前 8 + … + 后 8）。 */
export function truncateDigest(value: string | null | undefined): string {
  if (value === null || value === undefined) return '—'
  if (value.length <= 24) return value
  return `${value.slice(0, 8)}…${value.slice(-8)}`
}

/**
 * 90 天窗口引导判定（Q6-A 体验引导纯 UI 函数，业务裁决在后端）：
 * datetimerange 首选日与候选日的跨度 > 90 天 → 禁选（disabled-date 回调用）。
 */
export function isBeyond90Days(firstPickMs: number | null, candidateMs: number): boolean {
  if (firstPickMs === null) return false
  return Math.abs(candidateMs - firstPickMs) > 90 * 24 * 60 * 60 * 1000
}

// ==== 页面文案（视图零散写中文，集中本文件） ====

export const EVIDENCE_AUDIT_PAGE_TITLE = '监管审计'
export const EVIDENCE_AUDIT_PAGE_DESC =
  '监管方审计视图：存证总览、记录检索、异常项、全链时间线与调阅、审计报告（只读）。'
export const EVIDENCE_VERIFY_PAGE_TITLE = '存证核验'
export const EVIDENCE_VERIFY_PAGE_DESC =
  '核验 = 重算摘要并与链上记录比对，结论三态显式给出，不改变存证与业务数据。'

export const EVIDENCE_TAB_LABELS = {
  overview: '审计总览',
  records: '存证记录',
  anomalies: '异常项',
  trails: '时间线与调阅',
  reports: '审计报告',
} as const

/** 90 天体验引导（Q6-A：UI 便利非第二裁决，业务判定在后端）。总览 tab 全句（含"不填=全量"语义）。 */
export const EVIDENCE_WINDOW_HINT = '时间窗不超过 90 天；不填 = 全量快照'
/** 90 天引导半句（时间线 / 报告 tab 用——零条件被后端拒，不与"至少一个条件"并排产生"可全量"误读）。 */
export const EVIDENCE_WINDOW_HINT_ONLY = '时间窗不超过 90 天'
export const EVIDENCE_CONDITION_HINT = '至少填写一个条件（环节 / 单号 / 主体摘要 / 时间范围）'
export const EVIDENCE_ACCESS_HINT = '调阅动作由平台自动留痕'
export const EVIDENCE_TRAIL_HINT_DEFAULT = '输入业务单据号查看全链时间线'
export const EVIDENCE_UNAVAILABLE_WARNING = '跨域留痕暂不可达，时间线可能不完整'
export const EVIDENCE_NOT_ON_CHAIN_STATUS_PREFIX = '当前存证状态：'
export const EVIDENCE_NOT_ON_CHAIN_FAILED_PREFIX = '当前存证状态：上链失败'
export const EVIDENCE_NOT_ON_CHAIN_PENDING_TIP = '当前存证状态：待上链'
export const EVIDENCE_VERIFY_TITLE_PREFIX = '核验结论：'
export const EVIDENCE_NO_INPUT_TIP = '请输入存证编号'
export const EVIDENCE_REDOWNLOAD_NO_TIP = '请输入报告编号'
export const EVIDENCE_SNAPSHOT_PREFIX = '快照时点：'
export const EVIDENCE_TRAIL_STATUS_PREFIX = '留痕状态：'
export const EVIDENCE_EMPTY_TEXT = '暂无数据'
export const EVIDENCE_DIGEST_HINT = '点击复制完整值'
export const EVIDENCE_COPY_SUCCESS_TIP = '已复制完整值'
export const EVIDENCE_COPY_FAIL_TIP = '复制失败，请手动选择复制'

/** 通用动作与分区标签。 */
export const EVIDENCE_SEARCH_LABEL = '查询'
export const EVIDENCE_RETRY_LABEL = '重试'
export const EVIDENCE_VERIFY_SUBMIT_LABEL = '发起核验'
export const EVIDENCE_GENERATE_LABEL = '生成报告'
export const EVIDENCE_CSV_LABEL = '下载 CSV'
export const EVIDENCE_JSON_LABEL = '下载 JSON'
export const EVIDENCE_REDOWNLOAD_TITLE = '按报告编号补取文件'
