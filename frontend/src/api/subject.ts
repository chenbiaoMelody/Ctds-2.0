/**
 * WBS-3.1.5 主体服务 API（字段口径逐字对齐 subject-service 已交付接口契约：
 * 3.1.2 注册/查询、3.1.3 认证、3.1.4 政务 CA、3.1.5 审核；不改接口只做界面化）。
 */
import { apiJson, apiUpload } from './client'
import type { PageData } from './types'

/** 分页数据（WBS-3.1.12 F5 类型收口：唯一声明在 `./types`；此处保持原导出面不变）。 */
export type { PageData }

// ==== 类型（对齐后端响应记录） ====

export type SubjectStatus = 'PENDING_CERT' | 'PENDING_REVIEW' | 'ADMITTED' | 'REJECTED' | 'CERT_FAILED'

export interface ReviewQueueItem {
  subjectNo: string
  subjectName: string
  subjectType: string
  createdAt: string
}

export interface OcrElements {
  subjectName: string
  uscc: string
  legalPerson: string
  regAddress: string
}

export interface GovCaProfile {
  uploaded: boolean
  fileName: string
  lastConclusion: string | null
  lastFailReason: string | null
  lastSubmittedAt: string | null
}

export interface VerificationEntry {
  conclusion: string
  failReason: string | null
  createdAt: string
}

export interface CertificationProfile {
  subjectNo: string
  status: SubjectStatus
  license: {
    uploaded: boolean
    recognizable: boolean
    confirmed: boolean
    confirmedAt: string | null
    confirmedResult: OcrElements | null
  } | null
  govCa: GovCaProfile | null
  verifications: VerificationEntry[]
  remainingAttemptsToday: number | null
}

export interface StatusTransition {
  fromStatus: string | null
  toStatus: string
  triggerRole: string
  operator: string
  remark: string | null
  createdAt: string
}

// 与后端 GET /registrations/{subjectNo}/progress?uscc= 出参 ProgressView 逐字段对齐
// （CHG-C-1.1-V1.2 行为 8：仅最小必要五字段，无联系人/电话/证照信息）。
export interface RegistrationProgressView {
  subjectNo: string
  subjectName: string
  subjectType: string
  status: SubjectStatus
  rejectReason: string | null
}

// 与后端 GET /registrations/{subjectNo} 出参 SubjectView 逐字段对齐（扁平结构，
// 注册信息脱敏展示：contactPhone 已掩码；无 adminAccount 字段）。
export interface SubjectDetailView {
  subjectNo: string
  subjectName: string
  uscc: string
  subjectType: string
  regAddress: string
  contactName: string
  contactPhone: string
  status: SubjectStatus
  statusLogs: StatusTransition[]
}

export interface LicenseImageView {
  fileName: string
  dataUrl: string
}

// ==== 申请人侧（3.1.2/3.1.3/3.1.4 契约） ====

export interface RegisterPayload {
  subjectName: string
  uscc: string
  subjectType: string
  regAddress: string
  contactName: string
  contactPhone: string
  adminAccount: string
}

export function registerSubject(payload: RegisterPayload): Promise<{ subjectNo: string }> {
  return apiJson('/api/v1/subject/registrations', {
    method: 'POST',
    body: JSON.stringify(payload),
  })
}

export function fetchSubjectDetail(subjectNo: string): Promise<SubjectDetailView> {
  return apiJson(`/api/v1/subject/registrations/${subjectNo}`)
}

// CHG-C-1.1-V1.2 入驻进度自助查询（行为 8：双凭证；查询失败后端统一 1000C0003 文案防枚举）
export function fetchRegistrationProgress(subjectNo: string, uscc: string): Promise<RegistrationProgressView> {
  const params = new URLSearchParams({ uscc })
  return apiJson(`/api/v1/subject/registrations/${encodeURIComponent(subjectNo)}/progress?${params.toString()}`)
}

export function fetchProfile(subjectNo: string): Promise<CertificationProfile> {
  return apiJson(`/api/v1/subject/registrations/${subjectNo}/certification`)
}

export function uploadLicense(
  subjectNo: string,
  file: File,
): Promise<{ fileName: string; recognizable: boolean; ocrResult: OcrElements | null }> {
  return apiUpload(`/api/v1/subject/registrations/${subjectNo}/certification/license`, file)
}

export function confirmLicense(
  subjectNo: string,
  payload: { subjectName: string; uscc: string; legalPerson: string; regAddress: string },
): Promise<{ confirmed: boolean }> {
  return apiJson(`/api/v1/subject/registrations/${subjectNo}/certification/license/confirmation`, {
    method: 'POST',
    body: JSON.stringify(payload),
  })
}

export function verifyLegalPerson(
  subjectNo: string,
  payload: { legalPersonName: string; legalPersonIdNo: string },
): Promise<{ conclusion: string }> {
  return apiJson(`/api/v1/subject/registrations/${subjectNo}/certification/legal-person-verifications`, {
    method: 'POST',
    body: JSON.stringify(payload),
  })
}

export function submitGovCertificate(
  subjectNo: string,
  file: File,
): Promise<{ conclusion: string; status: SubjectStatus; failReason: string | null }> {
  return apiUpload(`/api/v1/subject/registrations/${subjectNo}/certification/gov-ca-certificate`, file)
}

export function fetchLicenseImage(subjectNo: string): Promise<LicenseImageView> {
  return apiJson(`/api/v1/subject/registrations/${subjectNo}/certification/license/image`)
}

// ==== 审核端（WBS-3.1.5，subject.review 权限） ====

export function fetchReviewQueue(pageNum: number, pageSize: number): Promise<PageData<ReviewQueueItem>> {
  return apiJson(`/api/v1/subject/review/queue?pageNum=${pageNum}&pageSize=${pageSize}`)
}

export function approveSubject(subjectNo: string): Promise<{ subjectNo: string; status: string }> {
  return apiJson(`/api/v1/subject/registrations/${subjectNo}/review/approval`, { method: 'POST' })
}

export function rejectSubject(
  subjectNo: string,
  reason: string,
): Promise<{ subjectNo: string; status: string }> {
  return apiJson(`/api/v1/subject/registrations/${subjectNo}/review/rejection`, {
    method: 'POST',
    body: JSON.stringify({ reason }),
  })
}
