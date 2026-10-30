/**
 * WBS-3.6.7 连接器域 API 模块（hifi §1 端点表 / §2.1）：
 * - 既有 20 个对外端点只读复用 + Q3-A 新增 1 个加性只读治理列表端点（共 21 个函数）；
 * - 经 `makeDomainJson(connectorRolesHeader)` 统一封装（不新增第二份域工厂）；
 *   页面级角色头：普通档 `applicant` / 运营档 `admin`（Q2-A）；
 * - **无幂等键**：连接器域未启用幂等（无幂等头要求），界面不做假幂等；
 * - 内部面零调用（N8）；路径段编号一律 `encodeURIComponent`；
 * - 类型字段与后端 DTO 逐字对齐（`ConnectorViews` / `ConnectorLifecycleViews` / `ConnectorSourceViews`）。
 */
import { makeDomainJson, pageQuery } from './client'
import { connectorRolesHeader } from '../stores/demoIdentity'
import type { PageData } from './types'

export type { PageData }

const connectorJson = makeDomainJson(connectorRolesHeader)

function postJson<T>(path: string, body?: unknown): Promise<T> {
  return connectorJson<T>(path, {
    method: 'POST',
    body: body === undefined ? undefined : JSON.stringify(body),
  })
}

// ==== 响应类型（字段与后端出参逐一对齐） ====

export type ConnectorStatus = 'PENDING_REVIEW' | 'REGISTERED' | 'CANCELLED'
export type SourceTypeCode = 'GOV_EXCHANGE' | 'DATABASE' | 'OBJECT_STORAGE' | 'API_GATEWAY'

/** 注册申请视图（待核验态 connectorNo 为 null）。 */
export interface RegistrationRecord {
  applicationNo: string
  connectorNo: string | null
  subjectNo: string
  connectorName: string
  connectorType: string
  connectorVersion: string
  status: ConnectorStatus
  lastRejectionCode: string | null
  lastRejectionReason: string | null
  createdAt: string
  updatedAt: string
}

/**
 * 连接器视图（列表摘要 did/didStatus 为 null、verified 恒 false——不得当作 DID 无效展示）。
 * 治理列表含待核验档案（`ConnectorView.summary` 直接取申请行）：`connectorNo` 可为 null。
 */
export interface ConnectorRecord {
  connectorNo: string | null
  subjectNo: string
  connectorName: string
  connectorType: string
  connectorVersion: string
  status: ConnectorStatus
  did: string | null
  didStatus: string | null
  verified: boolean
  online: boolean
  lastHeartbeatAt: string | null
  updatedAt: string
}

/** 绑定视图（实时 DID 状态 + 建立时间）。 */
export interface BindingRecord {
  connectorNo: string
  subjectNo: string
  did: string
  bindingStatus: 'ACTIVE' | 'RELEASED'
  boundAt: string
  didStatus: string
  verified: boolean
}

/** 核验结论（HTTP 200 业务结论；verified=false 时 failedCheck + reason 标明首个不通过项）。 */
export interface VerificationResult {
  applicationNo: string
  verified: boolean
  failedCheck: 'SUBJECT_ADMISSION' | 'DID_STATUS' | 'COMPLETENESS' | null
  reasonCode: string | null
  reason: string | null
  connectorNo: string | null
  status: ConnectorStatus
  binding: BindingRecord | null
}

/** 版本变更视图（升级单 + 版本历史链）。 */
export interface VersionChangeRecord {
  upgradeNo: string
  connectorNo: string
  subjectNo: string
  fromVersion: string
  toVersion: string
  compatibility: 'COMPATIBLE' | 'INCOMPATIBLE' | null
  status: 'PENDING_CONFIRM' | 'APPLIED' | 'VOIDED'
  requestedBy: string
  confirmedBy: string | null
  appliedAt: string | null
  createdAt: string
}

/** 生命周期聚合视图（详情页单一数据源，零跨域；注销后仍可读）。 */
export interface LifecycleRecord {
  connectorNo: string
  subjectNo: string
  connectorName: string
  connectorType: string
  connectorVersion: string
  status: ConnectorStatus
  online: boolean
  lastHeartbeatAt: string | null
  cancelledAt: string | null
  bindingStatus: 'ACTIVE' | 'RELEASED' | null
  did: string | null
  boundAt: string | null
  releasedAt: string | null
  inProgressJobs: number
  pendingUpgrade: boolean
  versionHistory: VersionChangeRecord[]
  updatedAt: string
}

/**
 * 动作留痕视图。result 值域含 `FAILURE`（后端 `ConnectorAuditService` 对源自检 / 源读取失败写入；
 * action-logs 查询不过滤，逐字展示）。
 */
export interface ActionLogRecord {
  applicationNo: string | null
  connectorNo: string | null
  operatorNo: string
  action: string
  result: 'SUCCESS' | 'DENIED' | 'FAILURE'
  reasonCode: string | null
  detail: string | null
  createdAt: string
}

/** 最近一次自检摘要（源详情内嵌；`failureType` 后端 `@JsonInclude(NON_NULL)`，通过时为缺省字段）。 */
export interface CheckSummary {
  passed: boolean
  failureType?: 'NETWORK_UNREACHABLE' | 'INVALID_CREDENTIAL' | 'INSUFFICIENT_PERMISSION' | null
  message: string
  checkedAt: string
}

/** 源视图（清单不含 lastCheck；详情含最近一次自检）。 */
export interface SourceRecord {
  sourceNo: string
  connectorNo: string
  sourceType: SourceTypeCode
  endpoint: string
  credentialRef: string
  credentialMasked: string
  createdAt: string
  updatedAt: string
  lastCheck?: CheckSummary
}

/** 自检结论视图（200 业务结论；失败含 failureType + message）。 */
export interface SourceCheckRecord {
  sourceNo: string
  passed: boolean
  failureType?: 'NETWORK_UNREACHABLE' | 'INVALID_CREDENTIAL' | 'INSUFFICIENT_PERMISSION' | null
  message: string
  checkedAt: string
}

// ==== 注册申请与核验（R1~R6） ====

/** R1 提交注册申请（三要素；主体取身份头）。 */
export function applyConnector(body: {
  connectorName: string
  connectorType: 'STANDARD'
  connectorVersion: string
}): Promise<RegistrationRecord> {
  return postJson('/api/v1/connectors/registrations', body)
}

/** R2 补正要素（仅属主 + 仅待核验态）。 */
export function amendConnector(
  applicationNo: string,
  body: { connectorName: string; connectorType: 'STANDARD'; connectorVersion: string },
): Promise<RegistrationRecord> {
  return connectorJson(`/api/v1/connectors/registrations/${encodeURIComponent(applicationNo)}`, {
    method: 'PUT',
    body: JSON.stringify(body),
  })
}

/** R3 我的申请列表（全状态，含已注销历史；申请时间倒序）。 */
export function listMyRegistrations(pageNum: number, pageSize: number): Promise<PageData<RegistrationRecord>> {
  return connectorJson(
    `/api/v1/connectors/registrations/mine${pageQuery(pageNum, pageSize)}`,
  )
}

/** R4 待核验队列（运营方；申请时间升序）。 */
export function listReviewQueue(pageNum: number, pageSize: number): Promise<PageData<RegistrationRecord>> {
  return connectorJson(`/api/v1/connectors/review/queue${pageQuery(pageNum, pageSize)}`)
}

/** R5 执行核验（三查；通过 = 转已注册 + 编号 + 绑定；拒绝 = 200 业务结论）。 */
export function verifyRegistration(applicationNo: string): Promise<VerificationResult> {
  return postJson(
    `/api/v1/connectors/registrations/${encodeURIComponent(applicationNo)}/verification`,
  )
}

/** R6 撤回待核验申请（仅属主 + 仅待核验态）。 */
export function withdrawRegistration(applicationNo: string): Promise<RegistrationRecord> {
  return connectorJson(`/api/v1/connectors/registrations/${encodeURIComponent(applicationNo)}`, {
    method: 'DELETE',
  })
}

// ==== 读面 / 健康度 / 绑定 / 留痕（Q1~Q4） ====

/** Q1 我的已注册连接器列表（摘要不含 DID 状态；online 服务端读时派生）。 */
export function listMyConnectors(pageNum: number, pageSize: number): Promise<PageData<ConnectorRecord>> {
  return connectorJson(`/api/v1/connectors/mine${pageQuery(pageNum, pageSize)}`)
}

/** Q4 心跳上报（仅属主 + 仅已注册态）。 */
export function heartbeat(connectorNo: string): Promise<ConnectorRecord> {
  return postJson(`/api/v1/connectors/${encodeURIComponent(connectorNo)}/heartbeats`)
}

/** Q2 绑定查验（实时查 DID 域；查验动作留痕）。 */
export function getBinding(connectorNo: string): Promise<BindingRecord> {
  return connectorJson(`/api/v1/connectors/${encodeURIComponent(connectorNo)}/binding`)
}

/** Q3 动作留痕分页（读取零新增留痕）。 */
export function listActionLogs(
  connectorNo: string,
  pageNum: number,
  pageSize: number,
): Promise<PageData<ActionLogRecord>> {
  return connectorJson(
    `/api/v1/connectors/${encodeURIComponent(connectorNo)}/action-logs${pageQuery(pageNum, pageSize)}`,
  )
}

/** L1 生命周期聚合读（详情页单一数据源；零跨域；注销后仍可读）。 */
export function getLifecycle(connectorNo: string): Promise<LifecycleRecord> {
  return connectorJson(`/api/v1/connectors/${encodeURIComponent(connectorNo)}/lifecycle`)
}

// ==== 生命周期（L2~L5） ====

/** L2 升级登记（兼容 = 立即生效；不兼容 = 待运营方确认）。 */
export function requestUpgrade(
  connectorNo: string,
  body: { toVersion: string; compatibility: 'COMPATIBLE' | 'INCOMPATIBLE' },
): Promise<VersionChangeRecord> {
  return postJson(`/api/v1/connectors/${encodeURIComponent(connectorNo)}/upgrades`, body)
}

/** L3 待确认升级队列（运营方；提交时间升序）。 */
export function listPendingUpgrades(pageNum: number, pageSize: number): Promise<PageData<VersionChangeRecord>> {
  return connectorJson(`/api/v1/connectors/upgrades/pending${pageQuery(pageNum, pageSize)}`)
}

/** L4 升级确认（运营方显式确认；仅待确认态）。 */
export function confirmUpgrade(connectorNo: string, upgradeNo: string): Promise<VersionChangeRecord> {
  return postJson(
    `/api/v1/connectors/${encodeURIComponent(connectorNo)}/upgrades/${encodeURIComponent(upgradeNo)}/confirmation`,
  )
}

/** L5 注销连接器（属主或运营方；仅已注册态；在途占用 > 0 → 服务端拒绝）。 */
export function cancelConnector(connectorNo: string): Promise<LifecycleRecord> {
  return postJson(`/api/v1/connectors/${encodeURIComponent(connectorNo)}/cancellation`)
}

// ==== 源接入（S1~S5；界面仅普通档调用） ====

/** S1 源清单（不含自检结论）。 */
export function listSources(
  connectorNo: string,
  pageNum: number,
  pageSize: number,
): Promise<PageData<SourceRecord>> {
  return connectorJson(
    `/api/v1/connectors/${encodeURIComponent(connectorNo)}/sources${pageQuery(pageNum, pageSize)}`,
  )
}

/** S2 源详情（含最近一次自检，可空）。 */
export function getSource(connectorNo: string, sourceNo: string): Promise<SourceRecord> {
  return connectorJson(
    `/api/v1/connectors/${encodeURIComponent(connectorNo)}/sources/${encodeURIComponent(sourceNo)}`,
  )
}

/** S3 登记源（凭据仅请求侧，响应仅掩码）。 */
export function registerSource(
  connectorNo: string,
  body: { sourceType: SourceTypeCode; endpoint: string; credential: string },
): Promise<SourceRecord> {
  return postJson(`/api/v1/connectors/${encodeURIComponent(connectorNo)}/sources`, body)
}

/** S4 变更源（凭据留空 = 不修改，不携带该字段）。 */
export function amendSource(
  connectorNo: string,
  sourceNo: string,
  body: { endpoint: string; credential?: string },
): Promise<SourceRecord> {
  return connectorJson(
    `/api/v1/connectors/${encodeURIComponent(connectorNo)}/sources/${encodeURIComponent(sourceNo)}`,
    { method: 'PUT', body: JSON.stringify(body) },
  )
}

/** S5 只读自检（200 业务结论；失败含三类原因）。 */
export function checkSource(connectorNo: string, sourceNo: string): Promise<SourceCheckRecord> {
  return postJson(
    `/api/v1/connectors/${encodeURIComponent(connectorNo)}/sources/${encodeURIComponent(sourceNo)}/checks`,
  )
}

// ==== 运营方治理列表（G1；Q3-A 加性只读端点） ====

/** G1 全平台连接器列表（运营方；status 空 = 三态全部；online 服务端读时派生）。 */
export function listGovernanceConnectors(
  status: ConnectorStatus | '',
  pageNum: number,
  pageSize: number,
): Promise<PageData<ConnectorRecord>> {
  const extra: Record<string, string> = status ? { status } : {}
  return connectorJson(
    `/api/v1/connectors/governance/connectors${pageQuery(pageNum, pageSize, extra)}`,
  )
}
