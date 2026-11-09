import { ApiError, getDemoSubject, makeDomainJson, pageQuery } from './client'
import { evidenceRolesHeader } from '../stores/demoIdentity'
import type { PageData } from './types'

/**
 * 存证域 API（WBS-3.8.5 hifi §1 / §3；端点零改动消费 3.8.2 治理 5 + 3.8.4 审计 5）：
 * - URL 单点：全部端点 URL 只出现在本文件（SourceGuard 扫描面）；
 * - 身份头：统一 `makeDomainJson(evidenceRolesHeader())`——运营档 admin / 普通档 requester
 *   （前置检查 P5 实测裁定；服务端映射仅 admin，requester 必拒 = fail-closed）；
 * - 分页：复用共享 `pageQuery`（DB-105：不建视图私有副本）；时间线双模端点以 filters 全参拼装
 *   （TRAIL 模式无分页参数，pageQuery 固定式不适用）；
 * - 出参类型以前置检查 P1 实测逐字锚定（E1 矩阵大写键 / anomalyType 18 字段 / TrailView 双模 /
 *   ReportView conclusionSummary 四键含 NOT_VERIFIED）；
 * - 错误：ApiError 原样抛出（治理族 403+1000C0005 / 审计族 400+1000C0003 同码同文，前端零转译）；
 * - retry 端点不消费（N9：界面无重试上链入口）。
 */
const evidenceJson = makeDomainJson(evidenceRolesHeader)

/** 存证记录行（治理读面出参原文口径：subjectDidDigest 为摘要，Q3 零加工渲染）。 */
export interface EvidenceRecordView {
  evidenceNo: string
  stageType: string
  businessNo: string
  operationType: string
  subjectDidDigest: string
  occurredAt: string
  contentDigest: string
  status: string
  chainTxId: string | null
  chainBlockNo: number | null
  retryCount: number
  lastErrorSummary: string | null
  createdAt: string
  updatedAt: string
}

/** 状态流转与核验留痕行（治理读面：actorSubjectNo 出原文）。 */
export interface EvidenceActionView {
  action: string
  fromStatus: string | null
  toStatus: string | null
  actorSubjectNo: string
  reasonCode: string
  detail: string | null
  createdAt: string
}

/** 存证详情（记录 + 留痕；抽屉与核验工具留痕表数据源）。 */
export interface EvidenceDetailView {
  record: EvidenceRecordView
  actions: EvidenceActionView[]
}

/** 核验结论（三态显式；HTTP 恒 200，结论不映射错误）。 */
export interface EvidenceVerifyView {
  conclusion: 'PASS' | 'MISMATCH' | 'NOT_ON_CHAIN'
  evidenceNo: string
  businessNo: string
  status: string
  expectedDigest: string
  actualDigest: string
  localDigest: string
  chainTxId: string | null
  verifiedBy: string
  verifiedAt: string
}

/** E1 审计总览（矩阵键 = 环节大写 × 状态大写计数，@JsonProperty 原样）。 */
export interface AuditOverview {
  matrix: Record<string, Record<string, number>>
  anomalies: { pendingCount: number; failedCount: number; mismatchCount: number }
  snapshotAt: string
}

/** E2 异常项行（记录 14 字段 + 异常与核验四字段；审计面 verifiedBy 出摘要）。 */
export interface AnomalyItemView extends EvidenceRecordView {
  anomalyType: string
  verifyConclusion: string | null
  verifiedBy: string | null
  verifiedAt: string | null
}

/** E3 时间线存证事件节点（审计面 actorDigest 出摘要）。 */
export interface TrailEvidenceAction {
  action: string
  fromStatus: string | null
  toStatus: string | null
  actorDigest: string | null
  reasonCode: string
  detail: string | null
  occurredAt: string
}

/** E3 时间线跨域业务留痕节点（sourceStatus = OK | UNAVAILABLE，降级显式）。 */
export interface TrailBusinessNode {
  source: string
  occurredAt: string
  actorDigest: string | null
  action: string
  result: string | null
  summary: string | null
  sourceStatus: 'OK' | 'UNAVAILABLE'
}

/** E3 双模响应：businessNo 命中 = TRAIL 单笔；其余 = LIST 清单（mode 判别）。 */
export interface TrailView {
  mode: 'TRAIL' | 'LIST'
  evidenceRecord: EvidenceRecordView | null
  evidenceActions: TrailEvidenceAction[] | null
  businessTrail: TrailBusinessNode[] | null
  timelineAvailable: boolean | null
  total: number | null
  pageNum: number | null
  pageSize: number | null
  totalPages: number | null
  list: EvidenceRecordView[] | null
  trailHint: string | null
}

/** E4 报告生成结果（conclusionSummary 实测四键含 NOT_VERIFIED）。 */
export interface AuditReportView {
  reportNo: string
  scope: Record<string, string>
  recordCount: number
  conclusionSummary: Record<string, number>
  anomalyCount: number
  contentDigest: string
  remark: string | null
  generatedBy: string
  generatedAt: string
}

/** 文件下载结果（Blob 另存由视图触发 a[download]）。 */
export interface AuditReportDownload {
  blob: Blob
  fileName: string
}

/** 存证清单检索条件（≥1 过滤条件由后端裁决；Q6-A 前端零复制）。 */
export interface EvidenceRecordQuery {
  pageNum: number
  pageSize: number
  stageType?: string
  businessNo?: string
  status?: string
  from?: string
  to?: string
}

/** E2 异常项检索条件。 */
export interface AuditAnomalyQuery {
  pageNum: number
  pageSize: number
  from?: string
  to?: string
}

/** E3 时间线检索条件（双模：businessNo = 单笔；其余条件 = 清单，≥1 由后端裁决）。 */
export interface AuditTrailQuery {
  stageType?: string
  businessNo?: string
  subjectDigest?: string
  from?: string
  to?: string
  pageNum?: number
  pageSize?: number
}

/** E4 报告生成请求体（条件 ≥1 由后端裁决；remark 可空）。 */
export interface AuditReportRequest {
  stageType?: string
  businessNo?: string
  subjectDigest?: string
  from?: string
  to?: string
  remark?: string
}

/** 过滤项拼装（空值不参与查询串；billing 同型私有助手，各域自有）。 */
function filters(entries: Record<string, string | number | undefined>): Record<string, string> {
  const extra: Record<string, string> = {}
  for (const [key, value] of Object.entries(entries)) {
    if (value !== undefined && value !== null && value !== '') {
      extra[key] = String(value)
    }
  }
  return extra
}

/** 治理面：存证清单分页（stageType / businessNo / status / from / to，P1 实测 ≥1 条件）。 */
export function listRecords(query: EvidenceRecordQuery): Promise<PageData<EvidenceRecordView>> {
  const extra = filters({
    stageType: query.stageType,
    businessNo: query.businessNo,
    status: query.status,
    from: query.from,
    to: query.to,
  })
  return evidenceJson(`/api/v1/evidence/records${pageQuery(query.pageNum, query.pageSize, extra)}`)
}

/** 治理面：存证详情（抽屉六要素 + 状态流转与核验留痕）。 */
export function getRecordDetail(evidenceNo: string): Promise<EvidenceDetailView> {
  return evidenceJson(`/api/v1/evidence/records/${encodeURIComponent(evidenceNo)}`)
}

/** 治理面：核验（只读比对 + 后端留痕；三态结论 HTTP 恒 200）。 */
export function verifyRecord(evidenceNo: string): Promise<EvidenceVerifyView> {
  return evidenceJson(`/api/v1/evidence/records/${encodeURIComponent(evidenceNo)}/verify`, {
    method: 'POST',
  })
}

/** E1 审计总览（from / to 可选 ISO-8601，窗口 ≤90 天由后端裁决）。 */
export function auditOverview(from?: string, to?: string): Promise<AuditOverview> {
  const params = new URLSearchParams()
  if (from) params.set('from', from)
  if (to) params.set('to', to)
  const qs = params.toString()
  return evidenceJson(`/api/v1/evidence/audit/overview${qs ? `?${qs}` : ''}`)
}

/** E2 异常项清单分页（三类并集；MISMATCH 行附最新核验结论与核验人）。 */
export function auditAnomalies(query: AuditAnomalyQuery): Promise<PageData<AnomalyItemView>> {
  const extra = filters({ from: query.from, to: query.to })
  return evidenceJson(`/api/v1/evidence/audit/anomalies${pageQuery(query.pageNum, query.pageSize, extra)}`)
}

/** E3 全链时间线与调阅（双模：businessNo 命中 = TRAIL；其余 = LIST，均 ≥1 条件由后端裁决）。 */
export function auditTrails(query: AuditTrailQuery): Promise<TrailView> {
  const extra = filters({
    stageType: query.stageType,
    businessNo: query.businessNo,
    subjectDigest: query.subjectDigest,
    from: query.from,
    to: query.to,
    pageNum: query.pageNum,
    pageSize: query.pageSize,
  })
  const params = new URLSearchParams(extra)
  const qs = params.toString()
  return evidenceJson(`/api/v1/evidence/audit/trails${qs ? `?${qs}` : ''}`)
}

/** E4 报告生成（重复生成 = 新编号不覆盖；后端唯一裁决条件与窗口）。 */
export function generateReport(body: AuditReportRequest): Promise<AuditReportView> {
  return evidenceJson('/api/v1/evidence/audit/reports', { method: 'POST', body: JSON.stringify(body) })
}

/**
 * 裸 fetch 豁免 №2（WBS-3.8.5 Q4-A；№1 = delivery.ts downloadDeliveryPackage）：文件响应非 JSON，须独立通道。
 * E5 报告下载：成功 = byte[] + Content-Disposition + text/csv | application/json → Blob 另存
 * （文件名解析沿 delivery 双正则，UTF-8 形式优先）；失败 = ApiResult JSON（前置检查 P8 实证
 * 形态：HTTP 400 + 1000C0003"记录不存在"；1012S0003 完整性失败同走 ApiResult）→ ApiError 原文。
 */
export async function downloadAuditReport(reportNo: string, format: 'csv' | 'json'): Promise<AuditReportDownload> {
  const response = await fetch(
    `/api/v1/evidence/audit/reports/${encodeURIComponent(reportNo)}/export?format=${format}`,
    {
      headers: {
        'X-Ctds-Subject': getDemoSubject(),
        'X-Ctds-Roles': evidenceRolesHeader(),
      },
    },
  )
  if (!response.ok) {
    let code = `HTTP_${response.status}`
    let message = `HTTP ${response.status}`
    try {
      const body = (await response.json()) as { code?: string; message?: string }
      if (body.code && body.message) {
        code = body.code
        message = body.message
      }
    } catch {
      // 非 JSON 失败体：按 HTTP 状态兜底，原文优先口径不变
    }
    throw new ApiError(code, message)
  }
  const disposition = response.headers.get('Content-Disposition') ?? ''
  const utf8Match = disposition.match(/filename\*=UTF-8''([^;]+)/)
  const plainMatch = disposition.match(/filename="?([^";]+)"?/)
  const fileName = decodeURIComponent(utf8Match?.[1] ?? plainMatch?.[1] ?? `${reportNo}.${format}`)
  return { blob: await response.blob(), fileName }
}
