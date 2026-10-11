/**
 * WBS-3.4.7 合约域 API 模块（hifi §1 端点表 / §2 前端模块）：
 * - 合约域 27 端点只读复用（模板 9 / 合约 15 / 治理 3）+ Q2-A 新增只读留痕端点 R13；
 * - 跨域只读复用：发起弹窗产品检索经 `api/catalog.ts` 的 `searchProducts`（目录域角色头）；
 * - 经 `apiJson` 统一封装（**不新增 `fetch` 直连**——T20 源集守卫）；
 * - **页面级角色头**：`X-Ctds-Roles: contractRolesHeader()`（普通档 `provider` / 运营档
 *   `admin`，覆盖 `apiJson` 的全局演示角色头；T18——普通档不得携带 `admin`）；
 * - 空主体编号 → 前置拦截（**零请求**），沿 3.2.6 E3 口径（hifi §4 边界）。
 */
import { apiJson, ApiError, getDemoSubject } from './client'
import { contractRolesHeader } from '../stores/demoIdentity'
import { DEMO_SUBJECT_REQUIRED_TIP } from '../constants/space'
import type { PageData } from './types'

/** 分页数据（唯一声明在 `./types`；此处保持既有导出面）。 */
export type { PageData }

// ==== 响应类型（字段与后端出参逐一对齐——hifi §1.1 出参清单） ====

export interface ContractTemplateType {
  code: string
  displayName: string
}

/** R4 模板列表条目。 */
export interface ContractTemplateItem {
  templateNo: string
  name: string
  type: ContractTemplateType
  currentVersion: number
}

/** 模板条款框架槽位（R5；发起表单与详情抽屉渲染源）。 */
export interface ClauseFrameworkSlot {
  key: string
  name: string
  required: boolean
  guide: string | null
}

export interface ClauseFramework {
  slots: ClauseFrameworkSlot[]
}

/** R5 模板详情。 */
export interface TemplateDetail {
  templateNo: string
  name: string
  type: ContractTemplateType
  currentVersion: number
  clauseFramework: ClauseFramework
  publishedAt: string | null
}

/** R1 运营列表条目（含停用）。 */
export interface ManageTemplate {
  templateNo: string
  name: string
  type: ContractTemplateType
  currentVersion: number
  status: string
  createdBy: string
  createdAt: string
  updatedAt: string
}

/** R2 版本历史条目（旧版本保留可查）。 */
export interface TemplateVersionItem {
  versionNo: number
  publishedBy: string
  publishedAt: string
  clauseFramework: ClauseFramework
}

/** R3 模板维护留痕行（含 DENIED_MANAGE 拒绝行）。 */
export interface TemplateActionLogItem {
  id: number
  templateNo: string
  versionNo: number | null
  action: string
  actorSubjectNo: string
  reasonCode: string | null
  fromValue: string | null
  toValue: string | null
  createdAt: string
}

/** W1 响应。 */
export interface TemplateCreated {
  templateNo: string
  versionNo: number
}

/** W2 修订响应（新版本号 + 修订前版本号）。 */
export interface RevisionResult {
  templateNo: string
  versionNo: number
  previousVersionNo: number
}

/** 模板启停响应。 */
export interface TemplateStatusResult {
  templateNo: string
  versionNo: number
  status: string
}

/** W5 发起响应（201 = 发起即协商中）。 */
export interface InitiatedResult {
  contractNo: string
  status: string
  clauseVersionNo: number
  templateNo: string
  templateVersionNo: number
  productId: number
}

/** 条款值（槽位键值 + 策略五要素——形态与后端文档字段名一致）。 */
export interface StrategyElement {
  enabled: boolean
  maxCount?: number | null
  startDate?: string | null
  endDate?: string | null
  text?: string | null
}

export interface StrategyValues {
  quota?: StrategyElement
  term?: StrategyElement
  purpose?: StrategyElement
  territory?: StrategyElement
  noRedistribution?: StrategyElement
  noRestrictionDeclared?: boolean
}

export interface ClauseValues {
  slots: Record<string, string>
  strategy: StrategyValues
}

/** W6 提案响应（版本 +1）。 */
export interface ProposalResult {
  contractNo: string
  clauseVersionNo: number
  previousVersionNo: number
}

/** W7 确认 / W8·W10 终止响应。 */
export interface ContractStateResult {
  contractNo: string
  status: string
}

/** W11 合意解除响应（双方各自确认；先确认方展示"已确认，等待对方"）。 */
export interface ReleaseConsentResult {
  contractNo: string
  status: string
  providerConsented: boolean
  requesterConsented: boolean
}

/** R6 我的合约条目（恒仅本主体参与）。 */
export interface MyContract {
  contractNo: string
  productName: string
  counterpartySubjectNo: string
  myRole: string
  status: string
  currentClauseVersion: number
  effectiveAt: string | null
  updatedAt: string
}

/** R7 签署状态行。 */
export interface SignatureStatus {
  partyRole: string
  subjectNo: string
  did: string
  signedAt: string | null
}

/** R7 存证事件摘要（未生效 = null）。 */
export interface AttestationSummary {
  contentHash: string
  providerDid: string
  providerSignedAt: string | null
  requesterDid: string
  requesterSignedAt: string | null
  attestedAt: string
}

/** R7 合约详情（当前条款 + 策略全文 + 签署状态；无数据本体）。 */
export interface ContractDetail {
  contractNo: string
  productId: number
  productName: string
  pricingModel: string
  priceAmount: string | null
  providerSubjectNo: string
  requesterSubjectNo: string
  templateNo: string
  templateVersionNo: number
  status: string
  terminationType: string | null
  terminationReason: string | null
  terminatedBy: string | null
  effectiveAt: string | null
  endedAt: string | null
  currentClauseVersion: number
  clauseValues: ClauseValues
  contentHash: string
  signatures: SignatureStatus[]
  attestation: AttestationSummary | null
  createdAt: string
  updatedAt: string
}

/** R8 条款版本链条目（全文 + 变更明细"从何值→到何值"）。 */
export interface ClauseVersionItem {
  versionNo: number
  clauseValues: ClauseValues
  changes: { slot: string; from: string | null; to: string | null }[] | null
  proposedBy: string | null
  proposedAt: string | null
  confirmedProviderAt: string | null
  confirmedRequesterAt: string | null
}

/** R9 验签报告（逐方结果原样）。 */
export interface VerificationReport {
  contractNo: string
  results: {
    partyRole: string
    did: string
    result: string
    reason: string | null
    verifiedAt: string
  }[]
}

/** R12 使用摘要（quota / 放行 / 拒绝 / 执行记录分页）。 */
export interface UsageRecord {
  requesterNo: string
  actionType: string
  outcome: string
  reasonCode: string | null
  violations: string | null
  usedCount: number
  occurredAt: string
}

export interface UsageSummary {
  contractNo: string
  quota: { limit: number; used: number } | null
  allowedCount: number
  deniedCount: number
  records: PageData<UsageRecord>
}

/** 模拟试算结论（拒绝以数据返回——HTTP 200 + allowed=false）。 */
export interface SimulationResult {
  contractNo: string
  contractStatus: string
  strategySource: string
  strategyEffective: boolean
  allowed: boolean
  violations: string[]
  assumedUsedCount: number | null
  assumedDate: string | null
  policySnapshot: Record<string, string> | null
}

/** 测试台报告（11 场景三态）。 */
export interface TestbenchScenario {
  code: string
  elementKey: string
  direction: string
  expectation: string
  outcome: string
  actual: { allowed: boolean; violations: string[] } | null
  note: string | null
}

export interface TestbenchReport {
  contractNo: string
  contractStatus: string
  strategyEffective: boolean
  runAt: string
  scenarios: TestbenchScenario[]
  summary: { total: number; pass: number; fail: number; skipped: number }
}

/** 受控执行结果（放行 200 + 计数；越界 1008C0020 原样抛出）。 */
export interface ExecutionResult {
  contractNo: string
  allowed: boolean
  usedCount: number
  occurredAt: string
}

/** W12 强制终止响应。 */
export interface ForceTerminationResult {
  contractNo: string
  status: string
}

/** R10 治理列表条目。 */
export interface GovernanceContract {
  contractNo: string
  productName: string
  providerSubjectNo: string
  requesterSubjectNo: string
  status: string
  terminationType: string | null
  currentClauseVersion: number
  effectiveAt: string | null
  updatedAt: string
}

/** R11 治理直查详情（详情 + 协商过程版本链）。 */
export interface GovernanceDetail {
  detail: ContractDetail
  clauseVersions: ClauseVersionItem[]
}

/** R13 合约统一留痕行（四要素 + 版本号 + from→to + 拒绝码；无敏感原文）。 */
export interface ContractActionLogItem {
  id: number
  contractNo: string | null
  versionNo: number | null
  action: string
  actorSubjectNo: string
  reasonCode: string | null
  fromValue: string | null
  toValue: string | null
  createdAt: string
}

// ==== 请求封装 ====

/** 页面级角色头（覆盖 apiJson 的全局 `demoRolesHeader()`；T18）。 */
function contractHeaders(): Record<string, string> {
  return { 'X-Ctds-Roles': contractRolesHeader() }
}

/** 合约域统一请求：空主体前置拦截（零请求）→ 附页面级角色头 → `apiJson`。 */
function contractJson<T>(path: string, options: RequestInit = {}): Promise<T> {
  if (!getDemoSubject().trim()) {
    return Promise.reject(new ApiError('1000C0002', DEMO_SUBJECT_REQUIRED_TIP))
  }
  return apiJson<T>(path, {
    ...options,
    headers: { ...contractHeaders(), ...(options.headers || {}) },
  })
}

function pageQuery(pageNum: number, pageSize: number, extra: Record<string, string> = {}): string {
  const params = new URLSearchParams({ pageNum: String(pageNum), pageSize: String(pageSize), ...extra })
  return `?${params.toString()}`
}

function postJson<T>(path: string, body?: unknown): Promise<T> {
  return contractJson<T>(path, {
    method: 'POST',
    body: body === undefined ? undefined : JSON.stringify(body),
  })
}

// ==== 模板面（R1~R5 + W1~W4） ====

/** R4 模板列表（仅启用中；所有已入驻主体）。 */
export function listTemplates(query: {
  pageNum: number
  pageSize: number
  type?: string
}): Promise<PageData<ContractTemplateItem>> {
  const extra: Record<string, string> = {}
  if (query.type) extra.type = query.type
  return contractJson(`/api/v1/contract-templates${pageQuery(query.pageNum, query.pageSize, extra)}`)
}

/** R5 模板详情（条款框架；停用/不存在同形 1008C0001）。 */
export function getTemplate(templateNo: string): Promise<TemplateDetail> {
  return contractJson(`/api/v1/contract-templates/${templateNo}`)
}

/** R1 运营列表（含停用；仅运营档）。 */
export function listManageTemplates(query: {
  pageNum: number
  pageSize: number
  status?: string
  type?: string
}): Promise<PageData<ManageTemplate>> {
  const extra: Record<string, string> = {}
  if (query.status) extra.status = query.status
  if (query.type) extra.type = query.type
  return contractJson(`/api/v1/contract-templates/manage${pageQuery(query.pageNum, query.pageSize, extra)}`)
}

/** R2 版本历史（旧版本保留可查）。 */
export function listTemplateVersions(templateNo: string): Promise<TemplateVersionItem[]> {
  return contractJson(`/api/v1/contract-templates/manage/${templateNo}/versions`)
}

/** R3 维护留痕（含 DENIED_MANAGE 拒绝行）。 */
export function listTemplateActionLogs(query: {
  pageNum: number
  pageSize: number
  templateNo?: string
}): Promise<PageData<TemplateActionLogItem>> {
  const extra: Record<string, string> = {}
  if (query.templateNo) extra.templateNo = query.templateNo
  return contractJson(`/api/v1/contract-templates/manage/action-logs${pageQuery(query.pageNum, query.pageSize, extra)}`)
}

/** W1 新增模板（同类型同名 1008C0005；框架非法 1008C0004）。 */
export function createTemplate(command: {
  name: string
  type: string
  clauseFramework: ClauseFramework
}): Promise<TemplateCreated> {
  return postJson('/api/v1/contract-templates', command)
}

/** W2 修订模板（出新版本号；旧版本保留）。 */
export function reviseTemplate(
  templateNo: string,
  command: { clauseFramework: ClauseFramework },
): Promise<RevisionResult> {
  return postJson(`/api/v1/contract-templates/${templateNo}/revisions`, command)
}

/** W3 停用（同态重复 1008C0007）。 */
export function disableTemplate(templateNo: string): Promise<TemplateStatusResult> {
  return postJson(`/api/v1/contract-templates/${templateNo}/disable`)
}

/** W4 启用。 */
export function enableTemplate(templateNo: string): Promise<TemplateStatusResult> {
  return postJson(`/api/v1/contract-templates/${templateNo}/enable`)
}

// ==== 合约面（W5~W11 + R6~R9 + R12 + 三端点 + R13） ====

/** W5 发起合约（幂等 ADR-007；201 = 发起即协商中）。 */
export function initiateContract(command: {
  productId: number
  templateNo: string
  templateVersionNo: number
  clauseValues: ClauseValues
}): Promise<InitiatedResult> {
  return postJson('/api/v1/contracts', command)
}

/** W6 提案（全量条款编辑；版本 +1）。 */
export function proposeClauses(
  contractNo: string,
  command: { clauseValues: ClauseValues },
): Promise<ProposalResult> {
  return postJson(`/api/v1/contracts/${contractNo}/proposals`, command)
}

/** W7 确认（空体；双齐 → 待签署）。 */
export function confirmClauses(contractNo: string): Promise<ContractStateResult> {
  return postJson(`/api/v1/contracts/${contractNo}/confirmations`)
}

/** W8 终止协商（空体；终态不可逆）。 */
export function terminateNegotiation(contractNo: string): Promise<ContractStateResult> {
  return postJson(`/api/v1/contracts/${contractNo}/negotiation-terminations`)
}

/** W9 签署（DID 必填；后签 → 已生效 + 存证）。 */
export function signContract(
  contractNo: string,
  command: { did: string },
): Promise<ContractStateResult> {
  return postJson(`/api/v1/contracts/${contractNo}/signatures`, command)
}

/** W10 拒签终止（空体；含"已签方撤回"语义）。 */
export function refuseSignature(contractNo: string): Promise<ContractStateResult> {
  return postJson(`/api/v1/contracts/${contractNo}/signature-refusals`)
}

/** W11 合意解除（空体；双齐 → 已完结）。 */
export function releaseConsent(contractNo: string): Promise<ReleaseConsentResult> {
  return postJson(`/api/v1/contracts/${contractNo}/release-consents`)
}

/** R6 我的合约（恒仅本主体参与）。 */
export function listMyContracts(query: {
  pageNum: number
  pageSize: number
  status?: string
  role?: string
}): Promise<PageData<MyContract>> {
  const extra: Record<string, string> = {}
  if (query.status) extra.status = query.status
  if (query.role) extra.role = query.role
  return contractJson(`/api/v1/contracts/mine${pageQuery(query.pageNum, query.pageSize, extra)}`)
}

/** R7 合约详情（非参与方与不存在同码同文 1008C0012）。 */
export function getContractDetail(contractNo: string): Promise<ContractDetail> {
  return contractJson(`/api/v1/contracts/${contractNo}`)
}

/** R8 条款版本链。 */
export function listClauseVersions(contractNo: string): Promise<ClauseVersionItem[]> {
  return contractJson(`/api/v1/contracts/${contractNo}/clause-versions`)
}

/** R9 验签核验（逐方结论；FAIL/UNAVAILABLE 原样展示）。 */
export function verifySignatures(contractNo: string): Promise<VerificationReport> {
  return postJson(`/api/v1/contracts/${contractNo}/signature-verifications`)
}

/** R12 使用摘要（quota / 放行 / 拒绝 / 记录分页）。 */
export function getUsageSummary(query: {
  contractNo: string
  pageNum: number
  pageSize: number
}): Promise<UsageSummary> {
  return contractJson(`/api/v1/contracts/${query.contractNo}/usage-summary${pageQuery(query.pageNum, query.pageSize)}`)
}

/** 模拟试算（拒绝以数据返回 HTTP 200 + allowed=false；草稿非法 1008C0015）。 */
export function simulatePolicy(
  contractNo: string,
  command: {
    actionType: string
    purpose?: string
    territory?: string
    assumedUsedCount?: number
    assumedDate?: string
    strategyDocument?: StrategyValues
  },
): Promise<SimulationResult> {
  return postJson(`/api/v1/contracts/${contractNo}/policy-simulations`, command)
}

/** 测试台一键报告（11 场景三态；零落库）。 */
export function runPolicyTestbench(contractNo: string): Promise<TestbenchReport> {
  return postJson(`/api/v1/contracts/${contractNo}/policy-testbench-runs`)
}

/** 受控执行（发起使用；无幂等键——每次提交计一次使用；越界 1008C0020 原样）。 */
export function executeUsage(
  contractNo: string,
  command: { actionType: string; purpose: string; territory: string },
): Promise<ExecutionResult> {
  return postJson(`/api/v1/contracts/${contractNo}/usage-executions`, command)
}

/** R13 合约统一留痕分页（参与方 + 治理例外；读取零新增留痕）。 */
export function listContractActionLogs(query: {
  contractNo: string
  pageNum: number
  pageSize: number
}): Promise<PageData<ContractActionLogItem>> {
  return contractJson(`/api/v1/contracts/${query.contractNo}/action-logs${pageQuery(query.pageNum, query.pageSize)}`)
}

// ==== 治理面（W12 + R10/R11） ====

/** R10 治理全量列表（每次调用写 GOVERNANCE_VIEW 留痕——服务端行为）。 */
export function listGovernanceContracts(query: {
  pageNum: number
  pageSize: number
  status?: string
}): Promise<PageData<GovernanceContract>> {
  const extra: Record<string, string> = {}
  if (query.status) extra.status = query.status
  return contractJson(`/api/v1/contracts/governance${pageQuery(query.pageNum, query.pageSize, extra)}`)
}

/** R11 治理直查详情（查看留痕；详情 + 版本链）。 */
export function getGovernanceDetail(contractNo: string): Promise<GovernanceDetail> {
  return contractJson(`/api/v1/contracts/governance/${contractNo}`)
}

/** W12 强制终止（理由必填 1~512）。 */
export function forceTerminate(
  contractNo: string,
  command: { reason: string },
): Promise<ForceTerminationResult> {
  return postJson(`/api/v1/contracts/governance/${contractNo}/force-termination`, command)
}
