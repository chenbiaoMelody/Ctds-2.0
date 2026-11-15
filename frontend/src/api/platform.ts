/**
 * WBS-3.9.5 platform 域 API 封装（hifi §1/§3；DB-41 横切口径）：
 * - URL 单点：rbac 4 端点 + platform 4 端点全部 URL 仅本文件持有（SourceGuard 断言，视图零 URL）；
 * - 域工厂两枚：rbac 域走 `rbacRolesHeader()`、platform 新域走 `platformRolesHeader()`
 *   （均随三员视角，hifi §5；普通主体档 = requester 单头，fail-closed 由服务端拒）；
 * - 分页复用共享 `pageQuery`（DB-105：不建视图私有副本）；
 * - 处置参数边界（hifi §3）：`durationMinutes` 仅 SILENCE 携带、其余动作不传（后端缺省 30、
 *   上限 1440 为后端裁决；前端不复制规则副本，仅控件级体验引导）；
 * - grant/revoke 成功出参 = 变更号字符串（E-2 勘误实测：幂等重放 = 空串）；
 * - 错误通道 = 既有 `ApiError`（envelope code + message 原文透传，B3-AC1 不转译）。
 */
import { makeDomainJson, pageQuery } from './client'
import type { PageData } from './types'
import { platformRolesHeader, rbacRolesHeader } from '../stores/demoIdentity'

// ==== URL 单点（SourceGuard：全仓仅此处持有这 8 条 URL） ====

const RBAC_ROLES_URL = '/api/v1/rbac/roles'
const RBAC_GRANT_URL = '/api/v1/rbac/permissions/grant'
const RBAC_REVOKE_URL = '/api/v1/rbac/permissions/revoke'
const RBAC_CHANGES_URL = '/api/v1/rbac/changes'
const PLATFORM_DOMAIN_URL = '/api/v1/platform/domain'
const PLATFORM_METRICS_URL = '/api/v1/platform/metrics'
const PLATFORM_ALERTS_URL = '/api/v1/platform/alerts'
const PLATFORM_ALERT_ACTIONS_URL = '/api/v1/platform/alerts/actions'

// ==== 共享类型（出参字段名以前置检查 P2 实测为准，样本见 build-output/w395-precheck-data/） ====

/** B1-AC1 权限点清单行（GET /rbac/roles）。 */
export interface RbacRoleView {
  role: string
  permissions: string[]
}

/** B1-AC3 授权映射变更留痕行（GET /rbac/changes，八要素）。 */
export interface RbacChangeView {
  changeNo: string
  changeOrderNo: string
  changedBy: string
  changedAt: string
  roleCode: string
  permissionPoint: string
  beforeValue: string
  afterValue: string
}

/** B9-AC1 部署域读面（GET /platform/domain，八字段；INCONSISTENT 以 503 出站不进成功响应）。 */
export interface DomainView {
  domainId: string
  domainName: string
  platformVersion: string
  lifecycleStatus: string
  schemaList: string[]
  serviceList: string[]
  configConsistency: string
  scopeNote: string
}

/** B5-R2 监控读数行（GET /platform/metrics；NOT_INTEGRATED 行读数列 null + note 原文）。 */
export interface MonitorServiceView {
  service: string
  job: string
  integrated: string
  alive: string | null
  heapUsedBytes: number | null
  heapMaxBytes: number | null
  gcPauseSecondsRate5m: number | null
  httpRatePerSec1m: number | null
  errorRate5xx5m: number | null
  p95LatencyMs5m: number | null
  note: string | null
}

export interface MetricsView {
  services: MonitorServiceView[]
}

/** B7-AC1 告警七要素行（GET /platform/alerts；封闭两规则固定入列，前端零过滤）。 */
export interface AlertItemView {
  alertName: string
  severity: string
  target: string
  triggeredAt: string | null
  currentValue: string | null
  threshold: string | null
  state: string
  stateNote: string | null
}

export interface AlertsView {
  alerts: AlertItemView[]
}

/** B8 处置请求（POST /platform/alerts/actions；durationMinutes 仅 SILENCE）。 */
export interface DisposeRequest {
  alertName: string
  action: 'ACKNOWLEDGE' | 'SILENCE' | 'ESCALATE'
  note?: string
  durationMinutes?: number
}

/** B8-AC1 处置响应（result=OK；silenceId 仅静默；recordedAt = 处置时点）。 */
export interface DisposeView {
  alertName: string
  action: string
  result: string
  silenceId: string | null
  recordedAt: string
}

// ==== 域工厂（hifi §3：rbac 域与 platform 新域两枚，头随三员视角） ====

const rbacJson = makeDomainJson(rbacRolesHeader)
const platformJson = makeDomainJson(platformRolesHeader)

// ==== rbac 域 4 函数（hifi §1.1；越权 403 文案统一见规格 B3-AC1，服务端权威） ====

/** 权限点清单（挂 connector.health.read；清单即读面，前端零聚合加工）。 */
export function listRbacRoles(): Promise<RbacRoleView[]> {
  return rbacJson<RbacRoleView[]>(RBAC_ROLES_URL)
}

/** 授予请求体三元组（幂等唯一键；变更人 = X-Ctds-Subject 头由 client 统一带）。 */
export interface GrantRevokeRequest {
  role: string
  permission: string
  changeOrderNo: string
}

/** 授予（挂 connector.review；成功 = 变更号字符串，E-2）。 */
export function grantPermission(req: GrantRevokeRequest): Promise<string> {
  return rbacJson<string>(RBAC_GRANT_URL, { method: 'POST', body: JSON.stringify(req) })
}

/** 收回（挂 connector.review；同上）。 */
export function revokePermission(req: GrantRevokeRequest): Promise<string> {
  return rbacJson<string>(RBAC_REVOKE_URL, { method: 'POST', body: JSON.stringify(req) })
}

/** 变更留痕分页（挂 evidence.governance；八列原文呈现）。 */
export function listRbacChanges(pageNum: number, pageSize: number): Promise<PageData<RbacChangeView>> {
  return rbacJson<PageData<RbacChangeView>>(`${RBAC_CHANGES_URL}${pageQuery(pageNum, pageSize)}`)
}

// ==== platform 域 4 函数（hifi §1.2/§1.3） ====

/** 部署域读面（挂 platform.domain.read；配置不一致 503 → 整页错误态，码见规格 B9-3）。 */
export function getDomain(): Promise<DomainView> {
  return platformJson<DomainView>(PLATFORM_DOMAIN_URL)
}

/** 监控读数（挂 platform.monitor.read；无监控栈环境 503 → 错误态原文，E-1 实测）。 */
export function getMetrics(): Promise<MetricsView> {
  return platformJson<MetricsView>(PLATFORM_METRICS_URL)
}

/** 告警列表（挂 platform.monitor.read；七要素 + stateNote 原文）。 */
export function listAlerts(): Promise<AlertsView> {
  return platformJson<AlertsView>(PLATFORM_ALERTS_URL)
}

/**
 * 告警处置（挂 platform.alert.handle；唯一在线写面）：`durationMinutes` 仅 SILENCE 携带
 * （hifi §3：其余动作从请求体剔除，后端缺省 30、上限 1440 裁决）。
 */
export function disposeAlert(req: DisposeRequest): Promise<DisposeView> {
  const body = { ...req }
  if (body.action !== 'SILENCE') {
    delete body.durationMinutes
  }
  return platformJson<DisposeView>(PLATFORM_ALERT_ACTIONS_URL, {
    method: 'POST',
    body: JSON.stringify(body),
  })
}
