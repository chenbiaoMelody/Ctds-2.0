/**
 * WBS-3.1.11 DID 管理界面 API（字段口径逐字对齐 services/did 契约，3.1.8/3.1.9/3.1.10 已有端点只做界面化）。
 *
 * 角色头为**页面级**（lofi/hifi Q7 采 A）：本模块请求显式附 `X-Ctds-Roles: applicant,reviewer,admin`，
 * **不改** client.ts 的全局 demoRolesHeader()——避免在 example-service 意外激活 greeting.delete
 * （最小权限面）。X-Ctds-Subject 复用 getDemoSubject()（由 apiJson 统一附加）。
 */
import { apiJson } from './client'

/** 记录状态（复用既有 DidStatus 三值：PENDING_ISSUE 为签发记录中间态，非 DID 状态）。 */
export type DidRecordStatus = 'ACTIVE' | 'REVOKED' | 'PENDING_ISSUE'

/** 操作类型（操作留痕）。 */
export type DidOperation = 'ISSUE' | 'REISSUE' | 'REVOKE'

/** 验证结论（PASS / FAIL / UNAVAILABLE，系统态与业务态分离）。 */
export type VerificationResult = 'PASS' | 'FAIL' | 'UNAVAILABLE'

/** 验证失败/不可用原因（复用 3.1.9 五值，不新增）。 */
export type VerificationReason =
  | 'SIGNATURE_INVALID'
  | 'REVOKED'
  | 'SUBJECT_BINDING_FAILED'
  | 'NOT_REGISTERED'
  | 'BINDING_UNAVAILABLE'

/** 分页数据（与后端 common/pagination PageResult 字段同构）。 */
export interface PageData<T> {
  list: T[]
  total: number
  pageNum: number
  pageSize: number
  totalPages: number
}

/** 签发记录行（did/keyRef 在"待签发"记录为空）。 */
export interface DidRecordView {
  subjectNo: string
  issuanceSeq: number
  did: string | null
  status: DidRecordStatus
  keyRef: string | null
  createdAt: string
  updatedAt: string
}

/** 操作留痕行（理由仅吊销非空、密钥引用仅签发/重签非空）。 */
export interface DidOperationLogView {
  operation: DidOperation
  operator: string
  reason: string | null
  keyRef: string | null
  statusFrom: string | null
  statusTo: string | null
  occurredAt: string
}

/** 验证留痕行（无数据原文）。 */
export interface VerificationLogView {
  did: string
  result: VerificationResult
  reason: VerificationReason | null
  occurredAt: string
}

/** 演示签名结果（原文与签名的 Base64）。 */
export interface DemoSignatureView {
  did: string
  data: string
  signature: string
  signedAt: string
}

/** DID 文档公开要素。 */
export interface ResolutionDocument {
  did?: string
  publicKey?: { type?: string; algorithm?: string; valueHex?: string }
  controller?: string
  service?: { id?: string; type?: string; serviceEndpoint?: string }[]
  created?: string
}

/** 解析结果（状态仅 有效/已吊销 两值）。 */
export interface ResolutionView {
  did: string
  status: 'ACTIVE' | 'REVOKED'
  document: ResolutionDocument
}

/** 验证结果。 */
export interface VerificationView {
  did: string
  result: VerificationResult
  reason: VerificationReason | null
  verifiedAt: string
}

/** 签发/重签/重试结果。 */
export interface IssuanceView {
  did: string | null
  status: DidRecordStatus
  keyRef: string | null
  issuedAt: string | null
}

/** 吊销结果。 */
export interface RevocationView {
  did: string
  status: 'REVOKED'
  revokedAt: string
}

/** 管理面角色头（页面级；did 服务把 admin 映射为 did.admin）。 */
const DID_ROLES_HEADER: Record<string, string> = { 'X-Ctds-Roles': 'applicant,reviewer,admin' }

/** 记录列表查询参数。 */
export interface DidRecordQuery {
  subjectNo?: string
  status?: DidRecordStatus | ''
  pageNum: number
  pageSize: number
}

/** 验证留痕查询参数。 */
export interface VerificationLogQuery {
  did?: string
  pageNum: number
  pageSize: number
}

function pageQuery(params: { pageNum: number; pageSize: number }): URLSearchParams {
  const query = new URLSearchParams()
  query.set('pageNum', String(params.pageNum))
  query.set('pageSize', String(params.pageSize))
  return query
}

/** 签发记录列表（分页 + 主体申请编号/记录状态过滤）。 */
export function fetchDidRecords(params: DidRecordQuery): Promise<PageData<DidRecordView>> {
  const query = pageQuery(params)
  if (params.subjectNo && params.subjectNo.trim()) {
    query.set('subjectNo', params.subjectNo.trim())
  }
  if (params.status) {
    query.set('status', params.status)
  }
  return apiJson(`/api/v1/did/records?${query.toString()}`, { headers: DID_ROLES_HEADER })
}

/** 单 DID 操作留痕（不分页）。 */
export function fetchOperationLogs(did: string): Promise<DidOperationLogView[]> {
  return apiJson(`/api/v1/did/records/${encodeURIComponent(did)}/operation-logs`, {
    headers: DID_ROLES_HEADER,
  })
}

/** 验证留痕列表（分页 + 可选 DID 过滤）。 */
export function fetchVerificationLogs(params: VerificationLogQuery): Promise<PageData<VerificationLogView>> {
  const query = pageQuery(params)
  if (params.did && params.did.trim()) {
    query.set('did', params.did.trim())
  }
  return apiJson(`/api/v1/did/verification-logs?${query.toString()}`, { headers: DID_ROLES_HEADER })
}

/** 演示代签（仅演示/调试期；入口未启用时后端回 1000C0003）。 */
export function demoSign(did: string, data: string): Promise<DemoSignatureView> {
  return apiJson(`/api/v1/did/${encodeURIComponent(did)}/demo-signatures`, {
    method: 'POST',
    headers: DID_ROLES_HEADER,
    body: JSON.stringify({ data }),
  })
}

/** 解析（文档公开要素 + 状态）。 */
export function resolveDid(did: string): Promise<ResolutionView> {
  return apiJson(`/api/v1/did/${encodeURIComponent(did)}`, { headers: DID_ROLES_HEADER })
}

/** 验证（三查结论与失败原因）。 */
export function verifySignature(did: string, data: string, signature: string): Promise<VerificationView> {
  return apiJson(`/api/v1/did/${encodeURIComponent(did)}/verifications`, {
    method: 'POST',
    headers: DID_ROLES_HEADER,
    body: JSON.stringify({ data, signature }),
  })
}

/** 吊销（理由必填，后端 1005C0002 兜底；不可逆）。 */
export function revokeDid(did: string, reason: string): Promise<RevocationView> {
  return apiJson(`/api/v1/did/${encodeURIComponent(did)}/revocation`, {
    method: 'POST',
    headers: DID_ROLES_HEADER,
    body: JSON.stringify({ reason }),
  })
}

/** 重签（新序号 + 全新密钥对；旧记录保留）。 */
export function reissueDid(subjectNo: string): Promise<IssuanceView> {
  return apiJson(`/api/v1/did/subjects/${encodeURIComponent(subjectNo)}/reissuances`, {
    method: 'POST',
    headers: DID_ROLES_HEADER,
  })
}

/** 重试（仅"待签发"记录可用）。 */
export function retryIssuance(subjectNo: string): Promise<IssuanceView> {
  return apiJson(`/api/v1/did/subjects/${encodeURIComponent(subjectNo)}/issuance-retries`, {
    method: 'POST',
    headers: DID_ROLES_HEADER,
  })
}
