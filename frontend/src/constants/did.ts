/**
 * WBS-3.1.11 DID 管理界面共享常量（hifi §6.4：状态与文案集中收口，禁止页面内散写字面量）。
 * 键值与后端枚举逐字对齐；**未感知的取值一律原样显示**（不吞掉、不显示空白）。
 */

/** 记录状态 → 中文标签（键 = 后端 DidStatus 三值；PENDING_ISSUE 为记录中间态）。 */
export const RECORD_STATUS_LABELS: Record<string, string> = {
  ACTIVE: '有效',
  REVOKED: '已吊销',
  PENDING_ISSUE: '待签发（记录中间态）',
}

/** 记录状态 → Element Plus tag 类型。 */
export const RECORD_STATUS_TYPES: Record<string, string> = {
  ACTIVE: 'success',
  REVOKED: 'danger',
  PENDING_ISSUE: 'warning',
}

/** 记录状态筛选下拉的取值（与后端 DidStatus 三值一致；空串 = 不筛选）。 */
export type RecordStatusFilterValue = '' | 'ACTIVE' | 'REVOKED' | 'PENDING_ISSUE'

/**
 * 记录状态筛选选项（hifi §6.4：页面禁止散写文案）。
 * label 一律取自 RECORD_STATUS_LABELS，保证筛选下拉与列表标签口径永久一致
 * （"待签发"与"待签发（记录中间态）"不得再出现两种说法）。
 */
export const RECORD_STATUS_FILTER_OPTIONS: { label: string; value: RecordStatusFilterValue }[] = [
  { label: '全部', value: '' },
  { label: RECORD_STATUS_LABELS.ACTIVE, value: 'ACTIVE' },
  { label: RECORD_STATUS_LABELS.REVOKED, value: 'REVOKED' },
  { label: RECORD_STATUS_LABELS.PENDING_ISSUE, value: 'PENDING_ISSUE' },
]

/** 操作类型 → 中文标签。 */
export const OPERATION_LABELS: Record<string, string> = {
  ISSUE: '签发',
  REISSUE: '重签',
  REVOKE: '吊销',
}

/** 验证结论 → 中文标签（UNAVAILABLE 为系统态，与业务态"不通过"分开展示）。 */
export const VERIFICATION_RESULT_LABELS: Record<string, string> = {
  PASS: '通过',
  FAIL: '不通过',
  UNAVAILABLE: '不可用（系统态）',
}

/** 验证原因 → 中文标签（复用后端五值，不新增）。 */
export const VERIFICATION_REASON_LABELS: Record<string, string> = {
  SIGNATURE_INVALID: '签名核验失败',
  REVOKED: '状态已吊销',
  SUBJECT_BINDING_FAILED: '主体绑定不成立',
  NOT_REGISTERED: '未登记',
  BINDING_UNAVAILABLE: '绑定服务不可用',
}

/** 列表空态文案（未入驻主体不签发 DID；行为 1 规则 1 / 验收标准 4）。 */
export const RECORDS_EMPTY_TEXT = '未找到符合条件的主体 DID 记录（未入驻主体不签发 DID）'

/** 解析未登记业务答复（warning 样式，不作系统报错样式；行为 2 规则 3）。 */
export const RESOLVE_NOT_REGISTERED_TEXT = '未登记该 DID'

/** 演示签名入口未启用（生产默认态；规格 §6 第 6 条）。 */
export const DEMO_DISABLED_HINT = '演示签名入口未启用（仅演示/调试期）'

/**
 * 标签映射（未感知取值原样返回，保证界面不出现空白/吞值）。
 * 值缺失（null/undefined/空串）显示占位符 "—"。
 */
export function labelOf(map: Record<string, string>, value: string | null | undefined): string {
  if (value === null || value === undefined || value === '') {
    return '—'
  }
  return map[value] ?? value
}

/** 状态标签（无占位符场景：状态恒有值）。 */
export function recordStatusLabel(status: string): string {
  return RECORD_STATUS_LABELS[status] ?? status
}

/** 状态 tag 类型（未感知取值回落 info，不抛错）。 */
export function recordStatusType(status: string): string {
  return RECORD_STATUS_TYPES[status] ?? 'info'
}
