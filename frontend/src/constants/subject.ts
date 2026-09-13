/**
 * WBS-3.1.5 前端共享常量（评审视角 3 建议上收：状态/类型映射在审核详情与认证页两处重复，
 * 上收消除漂移；键值与后端 SubjectStatus/SubjectType 枚举对齐）。
 */

/** 主体状态 → 中文标签（与后端枚举 SubjectStatus 一致）。 */
export const STATUS_LABELS: Record<string, string> = {
  PENDING_CERT: '待认证',
  PENDING_REVIEW: '待审核',
  ADMITTED: '已入驻',
  REJECTED: '已驳回',
  CERT_FAILED: '认证失败',
}

/** 主体状态 → Element Plus tag 类型。 */
export const STATUS_TYPES: Record<string, string> = {
  PENDING_CERT: 'warning',
  PENDING_REVIEW: 'primary',
  ADMITTED: 'success',
  REJECTED: 'danger',
  CERT_FAILED: 'info',
}

/** 主体类型 → 中文标签（与后端枚举 SubjectType 一致，三值）。 */
export function subjectTypeLabel(subjectType: string): string {
  if (subjectType === 'GOV') return '政府部门'
  if (subjectType === 'ENTERPRISE') return '企业'
  return '机构'
}
