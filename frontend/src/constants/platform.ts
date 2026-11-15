/**
 * WBS-3.9.5 platform 域常量层（hifi §2 W3）：部署域生命周期 / 集成态 / 告警状态与级别 /
 * 处置动作 / 配置一致性 双 map（LABELS + TAG type）；`labelOf()` 未知枚举值原样显示
 * （lofi §4 通用约定：不吞不猜）。connector 健康三态映射收口 `constants/connector.ts`
 * （connector 域专用含「—」口径，E-4 勘误：platform 常量层不建第二份 HEALTH 副本）。
 */

/** 未知值原样显示（null/undefined → 「—」）。 */
export function labelOf(labels: Record<string, string>, value: string | null | undefined): string {
  if (value === null || value === undefined || value === '') return '—'
  return labels[value] ?? value
}

/** 未知 tag 类型回退 info（中性）。 */
export function tagTypeOf(tagTypes: Record<string, string>, value: string | null | undefined): string {
  return (value && tagTypes[value]) || 'info'
}

/** 部署域生命周期（B9-AC1；ACTIVE 绿 / RETIRED 灰）。 */
export const LIFECYCLE_LABELS: Record<string, string> = {
  ACTIVE: '生效中',
  RETIRED: '已退役',
}

export const LIFECYCLE_TAG_TYPES: Record<string, string> = {
  ACTIVE: 'success',
  RETIRED: 'info',
}

/** 监控集成态（B5-R2/B5-R4：NOT_INTEGRATED 行显式"未接入"，读数不渲染 0）。 */
export const INTEGRATED_LABELS: Record<string, string> = {
  INTEGRATED: '已接入',
  NOT_INTEGRATED: '未接入',
}

export const INTEGRATED_TAG_TYPES: Record<string, string> = {
  INTEGRATED: 'success',
  NOT_INTEGRATED: 'info',
}

/** 告警状态（B7-R1 firing·resolved 双向可辨：已触发〔红〕/ 已恢复〔灰〕；恢复后条目仍可见）。 */
export const ALERT_STATE_LABELS: Record<string, string> = {
  firing: '已触发',
  resolved: '已恢复',
}

export const ALERT_STATE_TAG_TYPES: Record<string, string> = {
  firing: 'danger',
  resolved: 'info',
}

/** 告警级别（critical 红 / warning 橙）。 */
export const SEVERITY_LABELS: Record<string, string> = {
  critical: '严重',
  warning: '警告',
}

export const SEVERITY_TAG_TYPES: Record<string, string> = {
  critical: 'danger',
  warning: 'warning',
}

/** 处置动作（B8：三动作；唯一在线写面 = POST /platform/alerts/actions）。 */
export const DISPOSE_ACTION_LABELS: Record<string, string> = {
  ACKNOWLEDGE: '确认',
  SILENCE: '静默',
  ESCALATE: '升级',
}

/** 部署域配置一致性（B9：CONSISTENT 绿 / NOT_APPLICABLE 灰；INCONSISTENT 以 503+1013S0002 出站不进成功响应）。 */
export const CONSISTENCY_LABELS: Record<string, string> = {
  CONSISTENT: '一致',
  NOT_APPLICABLE: '不适用',
}

export const CONSISTENCY_TAG_TYPES: Record<string, string> = {
  CONSISTENT: 'success',
  NOT_APPLICABLE: 'info',
}

/** 界面提示文案（评审③ P3 收敛：提示文案集中 constants，视图零散写——沿 connector/billing 域惯例）。 */
export const MONITOR_EMPTY_TEXT = '暂无服务读数'
export const ALERT_EMPTY_TEXT = '暂无告警'
export const ROLES_EMPTY_TEXT = '暂无角色清单'
export const CHANGES_EMPTY_TEXT = '暂无变更留痕'
export const CHANGE_ORDER_REQUIRED_TIP = '变更单号必填（如 CHG-2026-001），授权映射变更以变更单留痕'
export const GRANT_ROLE_PERMISSION_REQUIRED_TIP = '请先选择或输入角色与权限点'
export const SILENCE_ID_COPIED_TIP = '静默编号已复制'
export const COPY_FAILED_TIP = '复制失败，请手动选择复制'

/**
 * 三员角色清单（lofi C-1"三员在前"展示序白名单；空间域演示档角色不在三员之列，
 * 真机走查偏差修正 2026-11-15）。视图零字面量散写（SourceGuard 口径）。
 */
export const PLATFORM_ADMIN_ROLES: string[] = [
  'platform.sysadmin',
  'platform.secadmin',
  'platform.audadmin',
]

/**
 * 授予/收回表单候选（lofi C-2 角色下拉 / 权限点下拉；**静态快照仅作输入辅助**，
 * 真实值域封闭判定在后端 B1-R1/B1-R2；候选源 = 前置检查 P2 实测 roles 出参〔V3/V4/V5 seed〕，
 * 登记于 `build-output/w395-precheck-data/precheck-summary.md`；不含空间域演示档角色
 * ——空间域最小权限面纪律〔SpaceSourceGuard T29〕：该角色值仅出现在 api/space.ts 与 demoIdentity.ts）。
 */
export const RBAC_ROLE_OPTIONS: string[] = [
  'platform.sysadmin',
  'platform.secadmin',
  'platform.audadmin',
  'admin',
  'applicant',
  'reviewer',
  'provider',
  'requester',
  'user',
]

export const RBAC_PERMISSION_OPTIONS: string[] = [
  'connector.health.read',
  'connector.review',
  'connector.upgrade.confirm',
  'connector.cancel',
  'evidence.governance',
  'platform.domain.read',
  'platform.monitor.read',
  'platform.alert.handle',
  'subject.register',
  'subject.read',
  'subject.review',
  'dataset.register',
  'dataset.read',
  'catalog.governance',
  'contract.template.manage',
  'contract.deal',
  'delivery.governance',
  'metering.governance',
  'billing.governance',
  'sandbox.review',
  'greeting.read',
  'demo.read',
]
