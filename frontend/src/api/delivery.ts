/**
 * WBS-3.5.6 交付域 API 模块（hifi §1 端点表 / §2.1）：
 * - 交付域 21 端点只读复用（交付 4 / 沙箱 7 / 沙箱结果 3 / 沙箱治理 4 / 交付治理 2 / 数据面拒绝 1）
 *   + Q5-A 新增 G7 双路径只读留痕端点 + 评审修复 G8 治理结果物只读端点（hifi §11 E-4）；
 * - 经 `makeDomainJson(deliveryRolesHeader())` 统一封装（**不新增第二份域工厂**——T14 源集守卫）；
 * - 页面级角色头：普通档 `requester,provider` 双头 / 运营档 `admin`（Q2-A；T2 硬约束：
 *   普通档不得携带 `admin`）；空主体编号 → 前置拦截零请求（沿 DB-41 口径）；
 * - 写端点幂等键（Q3-A）：调用方收 `idempotencyKey` 参数 → `X-Idempotency-Key` 头
 *   （5 个写面必传：发起交付 / 申请沙箱 / 运行作业 / 提交审核 / 导出；治理审核 G3 后端不收）；
 * - 路径段字符串编号一律 `encodeURIComponent`（沿 contract.ts 先例）；
 * - 类型字段与后端 `DeliveryViews.java` / `SandboxViews.java` 逐字对齐（编码段实测锚定：
 *   productId = number、fileSize = number | null、ResultView 审核四字段未登记为 null、
 *   conclusion 由 status 派生〔PENDING 为 null，否则与 status 同值〕）。
 */
import { ApiError, getDemoSubject, makeDomainJson, pageQuery } from './client'
import { DEMO_SUBJECT_REQUIRED_TIP } from '../constants/space'
import { deliveryRolesHeader } from '../stores/demoIdentity'
import type { PageData } from './types'

export type { PageData }

const deliveryJson = makeDomainJson(deliveryRolesHeader)

function idemHeaders(idempotencyKey: string): Record<string, string> {
  return { 'X-Idempotency-Key': idempotencyKey }
}

function postJson<T>(path: string, body?: unknown, headers?: Record<string, string>): Promise<T> {
  return deliveryJson<T>(path, {
    method: 'POST',
    body: body === undefined ? undefined : JSON.stringify(body),
    ...(headers ? { headers } : {}),
  })
}

// ==== 响应类型（字段与后端出参逐一对齐——hifi §2.1 实测锚定） ====

/** D1/D2/D3 交付记录视图（DeliveryView）。 */
export interface DeliveryRecord {
  deliveryNo: string
  contractNo: string
  productId: number
  productName: string
  deliveryType: 'API' | 'FILE' | 'SANDBOX_EXPORT'
  requesterNo: string
  providerNo: string
  fileId: string | null
  fileName: string | null
  fileSize: number | null
  packageId: string | null
  checksum: string | null
  result: string
  createdAt: string
}

/** S10 导出记录条目（ExportView；SANDBOX_EXPORT 形态的交付行）。 */
export interface SandboxExportRecord {
  deliveryNo: string
  deliveryType: string
  contractNo: string
  sandboxNo: string
  resultName: string
  requesterNo: string
  providerNo: string
  fileName: string | null
  fileSize: number | null
  checksum: string | null
  result: string
  createdAt: string
}

/** S2/S3/G1/G2 沙箱任务视图（SandboxView）。 */
export interface SandboxRecord {
  sandboxNo: string
  contractNo: string
  productId: number
  productName: string
  requesterNo: string
  providerNo: string
  status: 'RUNNING' | 'STOPPED' | 'EXPIRED'
  cpus: number
  memoryMb: number
  ttlSeconds: number
  expiresAt: string | null
  dataFileCount: number
  closeReason: string | null
  createdAt: string
}

/** S6 作业视图（JobView）。 */
export interface SandboxJobRecord {
  jobNo: string
  sandboxNo: string
  jobId: string
  status: 'RUNNING' | 'SUCCEEDED' | 'FAILED' | 'TIMEOUT'
  exitCode: number | null
  durationMs: number | null
  purpose: string | null
  territory: string | null
  createdAt: string
  finishedAt: string | null
}

/**
 * S7 结果物视图（ResultView）：审核四字段未提交时为 null（实测锚定）；
 * conclusion 由后端从 status 派生——PENDING 为 null，APPROVED/REJECTED 与 status 同值。
 */
export interface SandboxResultItem {
  fileName: string
  sizeBytes: number
  checksum: string | null
  createdAt: string
  status: 'PENDING' | 'APPROVED' | 'REJECTED' | null
  conclusion: 'APPROVED' | 'REJECTED' | null
  rejectReason: string | null
  reviewRound: number | null
}

/** G4 审核留痕（ReviewView；conclusion 值域 = APPROVED/REJECTED）。 */
export interface ReviewRecord {
  sandboxNo: string
  fileName: string
  reviewRound: number
  conclusion: 'APPROVED' | 'REJECTED'
  reason: string | null
  reviewedBy: string
  createdAt: string
}

/** G7 动作留痕（ActionLogView；谁 / 何时 / 对象 / 动作 / 结果码）。 */
export interface ActionLogItem {
  id: number
  deliveryNo: string | null
  contractNo: string
  operatorNo: string
  action: string
  result: 'SUCCESS' | 'DENIED' | 'FAILURE'
  reasonCode: string | null
  createdAt: string
}

/** 下载结果（密文 Blob + 明文 SM3 校验值，用于成功提示与完整性比对）。 */
export interface PackageDownloadResult {
  blob: Blob
  sm3: string
  fileName: string
}

// ==== 交付面（D1~D4） ====

/** D1 发起交付（幂等键必传）。 */
export function requestDelivery(
  idempotencyKey: string,
  command: {
    contractNo: string
    deliveryType: 'API' | 'FILE'
    fileId?: string
    purpose?: string
    territory?: string
  },
): Promise<DeliveryRecord> {
  return postJson('/api/v1/deliveries', command, idemHeaders(idempotencyKey))
}

/** D3 交付记录列表（按调用者过滤；形态过滤可选）。 */
export function listDeliveries(query: {
  pageNum: number
  pageSize: number
  contractNo?: string
  deliveryType?: string
}): Promise<PageData<DeliveryRecord>> {
  const extra: Record<string, string> = {}
  if (query.contractNo) extra.contractNo = query.contractNo
  if (query.deliveryType) extra.deliveryType = query.deliveryType
  return deliveryJson(`/api/v1/deliveries${pageQuery(query.pageNum, query.pageSize, extra)}`)
}

/** D2 交付详情。 */
export function getDelivery(deliveryNo: string): Promise<DeliveryRecord> {
  return deliveryJson(`/api/v1/deliveries/${encodeURIComponent(deliveryNo)}`)
}

/**
 * D4 加密包下载（仅合约需求方 + FILE/SANDBOX_EXPORT 形态）：二进制流走原生 fetch
 * （apiJson 假定 JSON 响应，不适用）；成功返回密文 Blob 与响应头 `X-Ctds-Sm3`
 * 明文校验值（T14 源集守卫唯一 fetch 豁免点）；非 ApiResult 响应（403/404）原样抛错。
 */
export async function downloadDeliveryPackage(deliveryNo: string): Promise<PackageDownloadResult> {
  if (!getDemoSubject().trim()) {
    return Promise.reject(new ApiError('1000C0002', DEMO_SUBJECT_REQUIRED_TIP))
  }
  const response = await fetch(
    `/api/v1/deliveries/${encodeURIComponent(deliveryNo)}/package`,
    {
      headers: {
        'X-Ctds-Subject': getDemoSubject(),
        'X-Ctds-Roles': deliveryRolesHeader(),
      },
    },
  )
  if (!response.ok) {
    let message = `HTTP ${response.status}`
    try {
      const body = (await response.json()) as { code?: string; message?: string }
      if (body.code && body.message) {
        throw Object.assign(new Error(body.message), { code: body.code })
      }
    } catch (e) {
      if (e instanceof Error && 'code' in e) {
        throw e
      }
    }
    throw Object.assign(new Error(message), { code: `HTTP_${response.status}` })
  }
  const disposition = response.headers.get('Content-Disposition') ?? ''
  const utf8Match = disposition.match(/filename\*=UTF-8''([^;]+)/)
  const plainMatch = disposition.match(/filename="?([^";]+)"?/)
  const fileName = decodeURIComponent(utf8Match?.[1] ?? plainMatch?.[1] ?? `${deliveryNo}.pkg`)
  return {
    blob: await response.blob(),
    sm3: response.headers.get('X-Ctds-Sm3') ?? '',
    fileName,
  }
}

// ==== 沙箱面（S1~S10） ====

/** S1 申请沙箱（幂等键必传）。 */
export function requestSandbox(
  idempotencyKey: string,
  command: { contractNo: string },
): Promise<SandboxRecord> {
  return postJson('/api/v1/sandboxes', command, idemHeaders(idempotencyKey))
}

/** S3 沙箱任务列表。 */
export function listSandboxes(query: {
  pageNum: number
  pageSize: number
  contractNo?: string
  status?: string
}): Promise<PageData<SandboxRecord>> {
  const extra: Record<string, string> = {}
  if (query.contractNo) extra.contractNo = query.contractNo
  if (query.status) extra.status = query.status
  return deliveryJson(`/api/v1/sandboxes${pageQuery(query.pageNum, query.pageSize, extra)}`)
}

/** S2 沙箱任务详情。 */
export function getSandbox(sandboxNo: string): Promise<SandboxRecord> {
  return deliveryJson(`/api/v1/sandboxes/${encodeURIComponent(sandboxNo)}`)
}

/** S4 关闭任务（幂等：重复关返回当前视图；不收幂等键头）。 */
export function closeSandbox(sandboxNo: string): Promise<SandboxRecord> {
  return postJson(`/api/v1/sandboxes/${encodeURIComponent(sandboxNo)}/actions/close`)
}

/** S5 运行作业（幂等键必传；jobId 白名单 = dataset-profile / input-inventory）。 */
export function runSandboxJob(
  idempotencyKey: string,
  sandboxNo: string,
  command: { jobId: string; purpose?: string; territory?: string },
): Promise<SandboxJobRecord> {
  return postJson(
    `/api/v1/sandboxes/${encodeURIComponent(sandboxNo)}/actions/run-job`,
    command,
    idemHeaders(idempotencyKey),
  )
}

/** S6 作业历史。 */
export function listSandboxJobs(
  sandboxNo: string,
  query: { pageNum: number; pageSize: number },
): Promise<PageData<SandboxJobRecord>> {
  return deliveryJson(
    `/api/v1/sandboxes/${encodeURIComponent(sandboxNo)}/jobs${pageQuery(query.pageNum, query.pageSize)}`,
  )
}

/** S7 结果物列表（无分页，元数据列表；未登记文件审核四字段为 null）。 */
export function listSandboxResults(sandboxNo: string): Promise<SandboxResultItem[]> {
  return deliveryJson(`/api/v1/sandboxes/${encodeURIComponent(sandboxNo)}/results`)
}

/** S8 提交结果物审核（幂等键必传；REJECTED 后重新提交轮次 +1）。 */
export function submitResultReview(
  idempotencyKey: string,
  sandboxNo: string,
  fileName: string,
): Promise<SandboxResultItem> {
  return postJson(
    `/api/v1/sandboxes/${encodeURIComponent(sandboxNo)}/results/${encodeURIComponent(fileName)}/actions/submit-review`,
    undefined,
    idemHeaders(idempotencyKey),
  )
}

/** S9 导出结果物（幂等键必传；用途 / 地域可选策略要素）。 */
export function exportSandboxResult(
  idempotencyKey: string,
  sandboxNo: string,
  fileName: string,
  elements: { purpose?: string; territory?: string },
): Promise<SandboxExportRecord> {
  const body: { purpose?: string; territory?: string } = {}
  if (elements.purpose) body.purpose = elements.purpose
  if (elements.territory) body.territory = elements.territory
  return postJson(
    `/api/v1/sandboxes/${encodeURIComponent(sandboxNo)}/results/${encodeURIComponent(fileName)}/actions/export`,
    body,
    idemHeaders(idempotencyKey),
  )
}

/** S10 导出记录列表。 */
export function listSandboxExports(
  sandboxNo: string,
  query: { pageNum: number; pageSize: number },
): Promise<PageData<SandboxExportRecord>> {
  return deliveryJson(
    `/api/v1/sandboxes/${encodeURIComponent(sandboxNo)}/exports${pageQuery(query.pageNum, query.pageSize)}`,
  )
}

// ==== 治理面（G1~G7；读即留痕） ====

/** G1 沙箱治理列表。 */
export function listGovernanceSandboxes(query: {
  pageNum: number
  pageSize: number
  contractNo?: string
  status?: string
}): Promise<PageData<SandboxRecord>> {
  const extra: Record<string, string> = {}
  if (query.contractNo) extra.contractNo = query.contractNo
  if (query.status) extra.status = query.status
  return deliveryJson(
    `/api/v1/sandboxes/governance${pageQuery(query.pageNum, query.pageSize, extra)}`,
  )
}

/** G2 沙箱治理详情。 */
export function getGovernanceSandbox(sandboxNo: string): Promise<SandboxRecord> {
  return deliveryJson(`/api/v1/sandboxes/governance/${encodeURIComponent(sandboxNo)}`)
}

/** G3 结果物审核（治理唯一写面；后端不收幂等键——实测锚定；结论一经作出不可修改）。 */
export function reviewGovernanceResult(
  sandboxNo: string,
  fileName: string,
  command: { conclusion: 'APPROVE' | 'REJECT'; reason?: string },
): Promise<SandboxResultItem> {
  return postJson(
    `/api/v1/sandboxes/governance/${encodeURIComponent(sandboxNo)}/results/${encodeURIComponent(fileName)}/actions/review`,
    command,
  )
}

/** G4 审核留痕列表（含轮次；fileName 过滤可选）。 */
export function listGovernanceReviews(
  sandboxNo: string,
  query: { pageNum: number; pageSize: number; fileName?: string },
): Promise<PageData<ReviewRecord>> {
  const extra: Record<string, string> = {}
  if (query.fileName) extra.fileName = query.fileName
  return deliveryJson(
    `/api/v1/sandboxes/governance/${encodeURIComponent(sandboxNo)}/reviews${pageQuery(query.pageNum, query.pageSize, extra)}`,
  )
}

/** G5 交付治理列表。 */
export function listGovernanceDeliveries(query: {
  pageNum: number
  pageSize: number
  contractNo?: string
  deliveryNo?: string
}): Promise<PageData<DeliveryRecord>> {
  const extra: Record<string, string> = {}
  if (query.contractNo) extra.contractNo = query.contractNo
  if (query.deliveryNo) extra.deliveryNo = query.deliveryNo
  return deliveryJson(
    `/api/v1/deliveries/governance${pageQuery(query.pageNum, query.pageSize, extra)}`,
  )
}

/** G6 交付治理详情。 */
export function getGovernanceDelivery(deliveryNo: string): Promise<DeliveryRecord> {
  return deliveryJson(`/api/v1/deliveries/governance/${encodeURIComponent(deliveryNo)}`)
}

/** G7a 合约动作留痕（参与方路径；读取零新增留痕；非参与方与不存在同码同文）。 */
export function listDeliveryActionLogs(query: {
  contractNo: string
  pageNum: number
  pageSize: number
}): Promise<PageData<ActionLogItem>> {
  return deliveryJson(
    `/api/v1/deliveries/${encodeURIComponent(query.contractNo)}/action-logs${pageQuery(query.pageNum, query.pageSize)}`,
  )
}

/** G7b 合约动作留痕（治理路径；读即留痕；无记录合约为空页）。 */
export function listGovernanceDeliveryActionLogs(query: {
  contractNo: string
  pageNum: number
  pageSize: number
}): Promise<PageData<ActionLogItem>> {
  return deliveryJson(
    `/api/v1/deliveries/governance/${encodeURIComponent(query.contractNo)}/action-logs${pageQuery(query.pageNum, query.pageSize)}`,
  )
}

/** G8 治理结果物元数据（WBS-3.5.6 评审修复加性读端点；读即留痕；未登记审核四字段为 null）。 */
export function listGovernanceSandboxResults(sandboxNo: string): Promise<SandboxResultItem[]> {
  return deliveryJson(
    `/api/v1/sandboxes/governance/${encodeURIComponent(sandboxNo)}/results`,
  )
}
