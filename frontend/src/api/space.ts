/**
 * WBS-3.2.6 空间域 API 模块（hifi §1 端点表 / §2 前端模块，25 个端点逐一对应）：
 * - 全部复用 3.2.3 / 3.2.4 / 3.2.5 已交付端点 + 3.2.6 Q6-A 新增只读留痕端点，**零改动既有语义**；
 * - 经 `apiJson` 统一封装（**不新增 `fetch` 直连**）；
 * - **页面级角色头**：`X-Ctds-Roles: spaceRolesHeader()`（普通档 `applicant` / 运营档
 *   `applicant,platform.operator`），覆盖 `apiJson` 的全局演示角色头（hifi §4、T27）；
 * - 空主体编号 → 前置拦截（**零请求**），沿后端未认证口径提示（hifi §4 边界）。
 */
import { apiJson, ApiError, getDemoSubject } from './client'
import { spaceRolesHeader } from '../stores/demoIdentity'
import { DEMO_SUBJECT_REQUIRED_TIP } from '../constants/space'
import type { PageData } from './types'

/** 分页数据（唯一声明在 `./types`；此处保持既有导出面）。 */
export type { PageData }

// ==== 响应类型（字段与后端出参逐一对齐） ====

export interface SpaceSummary {
  id: number
  name: string
  sceneType: string
  accessMode: string
  visibility: string
  intro: string | null
  status: string
  effectiveFrom: string | null
  effectiveTo: string | null
}

export interface SpaceMemberBrief {
  subjectNo: string
  role: string
}

export interface SpaceDetail extends SpaceSummary {
  ownerSubjectNo: string
  createdAt: string
  updatedAt: string
  members: SpaceMemberBrief[]
}

export interface MemberItem {
  id: number
  spaceId: number
  subjectNo: string
  role: string
  status: string
  joinedAt: string
  exitedAt: string | null
}

export interface AdmissionItem {
  id: number
  spaceId: number
  subjectNo: string
  type: string
  status: string
  operator: string
  reason: string | null
  memberId: number | null
  createdAt: string
}

export interface AdmissionOperation {
  alreadyMember: boolean
  admission: AdmissionItem | null
  member: MemberItem | null
}

export interface OwnershipTransfer {
  spaceOwnerSubjectNo: string
  formerOwner: MemberItem
  newOwner: MemberItem
  transferredAt: string
}

export interface PolicyEntry {
  id: number
  entryKey: string
  displayName: string
  entryValue: string
  redline: boolean
  status: string
  createdAt: string
  updatedAt: string
}

export interface PolicyOverride {
  entryKey: string
  effectiveValue: string
  source: string
  provenance: string
  note: string
  platformValue: string
  spaceValue: string | null
  redline: boolean
}

export interface EffectivePolicyItem {
  entryKey: string
  displayName: string
  effectiveValue: string
  source: string
  provenance: string
  note: string
  platformValue: string
  spaceValue: string | null
  spaceStatus: string | null
  redline: boolean
}

export interface SpaceActionLog {
  id: number
  action: string
  operator: string
  result: string
  reason: string | null
  fromValue: string | null
  toValue: string | null
  targetType: string
  targetId: string | null
  createdAt: string
}

// ==== 请求体类型 ====

export interface CreateSpacePayload {
  name: string
  sceneType: string
  accessMode: string
  visibility: string
  intro?: string
  effectiveFrom?: string
  effectiveTo?: string
}

/** 变更空间配置：**仅白名单字段**（hifi §1 端点 6）。 */
export interface UpdateSpacePayload {
  intro?: string
  effectiveFrom?: string
  effectiveTo?: string
}

// ==== 请求封装 ====

const BASE = '/api/v1/data-spaces'
const PLATFORM_BASE = '/api/v1/platform-policies'

/** 页面级角色头（覆盖 apiJson 的全局 `demoRolesHeader()`）。 */
function spaceHeaders(): Record<string, string> {
  return { 'X-Ctds-Roles': spaceRolesHeader() }
}

/** 空间域统一请求：空主体前置拦截（零请求）→ 附页面级角色头 → `apiJson`。 */
function spaceJson<T>(path: string, options: RequestInit = {}): Promise<T> {
  if (!getDemoSubject().trim()) {
    return Promise.reject(new ApiError('1000C0002', DEMO_SUBJECT_REQUIRED_TIP))
  }
  return apiJson<T>(path, {
    ...options,
    headers: { ...spaceHeaders(), ...(options.headers || {}) },
  })
}

function pageQuery(pageNum: number, pageSize: number, extra: Record<string, string> = {}): string {
  const params = new URLSearchParams({ pageNum: String(pageNum), pageSize: String(pageSize), ...extra })
  return `?${params.toString()}`
}

// ==== 端点 1~8：空间生命周期与读取 ====

export function createSpace(payload: CreateSpacePayload): Promise<SpaceDetail> {
  return spaceJson(`${BASE}`, { method: 'POST', body: JSON.stringify(payload) })
}

export function enableSpace(spaceId: number): Promise<SpaceDetail> {
  return spaceJson(`${BASE}/${spaceId}/enablement`, { method: 'POST' })
}

export function freezeSpace(spaceId: number): Promise<SpaceDetail> {
  return spaceJson(`${BASE}/${spaceId}/freezing`, { method: 'POST' })
}

export function unfreezeSpace(spaceId: number): Promise<SpaceDetail> {
  return spaceJson(`${BASE}/${spaceId}/unfreezing`, { method: 'POST' })
}

export function dissolveSpace(spaceId: number, reason?: string): Promise<SpaceDetail> {
  return spaceJson(`${BASE}/${spaceId}/dissolution`, {
    method: 'POST',
    body: JSON.stringify({ confirmDissolve: true, reason }),
  })
}

export function updateSpace(spaceId: number, payload: UpdateSpacePayload): Promise<SpaceDetail> {
  return spaceJson(`${BASE}/${spaceId}`, { method: 'PUT', body: JSON.stringify(payload) })
}

export function listSpaces(pageNum: number, pageSize: number, keyword?: string): Promise<PageData<SpaceSummary>> {
  const extra: Record<string, string> = {}
  if (keyword) extra.keyword = keyword
  return spaceJson(`${BASE}${pageQuery(pageNum, pageSize, extra)}`)
}

export function getSpace(spaceId: number): Promise<SpaceDetail | SpaceSummary> {
  return spaceJson(`${BASE}/${spaceId}`)
}

// ==== 端点 9~19：成员与准入 ====

export function applyAdmission(spaceId: number): Promise<AdmissionOperation> {
  return spaceJson(`${BASE}/${spaceId}/admissions/applications`, { method: 'POST' })
}

export function inviteMember(spaceId: number, payload: { subjectNo: string; reason?: string }): Promise<AdmissionOperation> {
  return spaceJson(`${BASE}/${spaceId}/admissions/invitations`, { method: 'POST', body: JSON.stringify(payload) })
}

export function confirmAdmission(
  spaceId: number,
  admissionId: number,
  payload: { decision: 'CONFIRM' | 'DECLINE'; reason?: string },
): Promise<AdmissionItem> {
  return spaceJson(`${BASE}/${spaceId}/admissions/${admissionId}/confirmation`, {
    method: 'POST',
    body: JSON.stringify(payload),
  })
}

export function approveAdmission(
  spaceId: number,
  admissionId: number,
  payload: { decision: 'APPROVE' | 'REJECT'; reason?: string },
): Promise<AdmissionItem> {
  return spaceJson(`${BASE}/${spaceId}/admissions/${admissionId}/approval`, {
    method: 'POST',
    body: JSON.stringify(payload),
  })
}

export function listAdmissions(
  spaceId: number,
  pageNum: number,
  pageSize: number,
  status?: string,
): Promise<PageData<AdmissionItem>> {
  const extra: Record<string, string> = {}
  if (status) extra.status = status
  return spaceJson(`${BASE}/${spaceId}/admissions${pageQuery(pageNum, pageSize, extra)}`)
}

export function listMyAdmissions(pageNum: number, pageSize: number): Promise<PageData<AdmissionItem>> {
  return spaceJson(`${BASE}/admissions/mine${pageQuery(pageNum, pageSize)}`)
}

export function listMembers(spaceId: number, pageNum: number, pageSize: number): Promise<PageData<MemberItem>> {
  return spaceJson(`${BASE}/${spaceId}/members${pageQuery(pageNum, pageSize)}`)
}

export function assignRole(spaceId: number, memberId: number, role: 'ADMIN' | 'MEMBER'): Promise<MemberItem> {
  return spaceJson(`${BASE}/${spaceId}/members/${memberId}/role-assignment`, {
    method: 'POST',
    body: JSON.stringify({ role }),
  })
}

export function removeMember(spaceId: number, memberId: number, reason: string): Promise<MemberItem> {
  return spaceJson(`${BASE}/${spaceId}/members/${memberId}/removal`, {
    method: 'POST',
    body: JSON.stringify({ reason }),
  })
}

export function leaveSpace(spaceId: number): Promise<MemberItem> {
  return spaceJson(`${BASE}/${spaceId}/leaving`, { method: 'POST' })
}

export function transferOwnership(spaceId: number, targetMemberId: number): Promise<OwnershipTransfer> {
  return spaceJson(`${BASE}/${spaceId}/ownership-transfer`, {
    method: 'POST',
    body: JSON.stringify({ targetMemberId }),
  })
}

// ==== 端点 20~22：平台策略条目治理 ====

export function createPlatformPolicy(
  payload: { entryKey: string; entryValue: string; redline: boolean },
): Promise<PolicyEntry> {
  return spaceJson(PLATFORM_BASE, { method: 'POST', body: JSON.stringify(payload) })
}

export function updatePlatformPolicy(
  entryId: number,
  payload: { entryValue?: string; redline?: boolean },
): Promise<PolicyEntry> {
  return spaceJson(`${PLATFORM_BASE}/${entryId}`, { method: 'PUT', body: JSON.stringify(payload) })
}

export function listPlatformPolicies(pageNum: number, pageSize: number): Promise<PageData<PolicyEntry>> {
  return spaceJson(`${PLATFORM_BASE}${pageQuery(pageNum, pageSize)}`)
}

// ==== 端点 23~25：策略覆盖 / 有效策略 / 操作留痕 ====

export function submitPolicyOverride(
  spaceId: number,
  payload: { entryKey: string; entryValue: string },
): Promise<PolicyOverride> {
  return spaceJson(`${BASE}/${spaceId}/policies/overrides`, { method: 'POST', body: JSON.stringify(payload) })
}

export function getEffectivePolicies(spaceId: number): Promise<EffectivePolicyItem[]> {
  return spaceJson(`${BASE}/${spaceId}/policies/effective`)
}

export function listActionLogs(spaceId: number, pageNum: number, pageSize: number): Promise<PageData<SpaceActionLog>> {
  return spaceJson(`${BASE}/${spaceId}/action-logs${pageQuery(pageNum, pageSize)}`)
}
